/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.network.LfElement;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class EquationArray<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    private final E type;

    private final int elementCount;

    private final EquationSystem<V, E> equationSystem;

    private final boolean[] elementActive;

    private final boolean[] hasSingleEquationTerms;

    private int firstColumn = -1;

    private int[] elementNumToColumn;

    private TIntIntMap columnToElementNum;

    private int length;

    // All terms that are in a vectorized view (in EquationTermArrays)
    private final List<EquationTermArray<V, E>> termArrays = new ArrayList<>();

    // All additional terms that are not vectorized (SingleEquationTerms) stored in different views
    private final Map<Integer, List<SingleEquationTerm<V, E>>> singleTermsByTermElementNum = new TreeMap<>();
    private final Map<Integer, AdditionalSingleTermsByEquation> singleTermsByEquationElementNum = new TreeMap<>();

    private final int[] equationDerivativeVectorStartIndices;
    private EquationDerivativeVector equationDerivativeVector;

    // Input to output mapping used by eval() to scatter term values to equation values. For each term array, the
    // term element numbers to read (sorted, so that the term value vector is read sequentially) and the equation
    // column to write to. Inactive terms and terms of inactive equations are filtered out at build time, so that
    // the scatter loop is branch free.
    private int[][] evalScatterTermElementNums;
    private int[][] evalScatterColumns;

    // Same thing for the single (non vectorized) terms: a flat list of the terms of the active equations, with the
    // column they contribute to, to avoid walking the maps they are stored in.
    private SingleEquationTerm<V, E>[] evalSingleTerms;
    private int[] evalSingleTermColumns;

    // Same thing for the complementary equations that currently occupy the column of their paired element.
    private SingleEquation<V, E>[] evalComplementaryEquations;
    private int[] evalComplementaryColumns;

    // Flat view of the single terms used by der(): for each equation element, the [start, end[ range of its distinct
    // derivative variables in singleTermDerVariables, and for each of those variables the terms depending on it.
    private int[] singleTermDerVariableRanges;
    private Variable<V>[] singleTermDerVariables;
    private SingleEquationTerm<V, E>[][] singleTermDerTerms;
    private int[] singleTermDerVariableRows;

    private final class AdditionalSingleTermsByEquation {
        private final List<SingleEquationTerm<V, E>> terms = new ArrayList<>();
        private final TreeMap<Variable<V>, List<SingleEquationTerm<V, E>>> termsByVariable = new TreeMap<>();

        void addSingleTerm(SingleEquationTerm<V, E> termImpl, Equation<V, E> equation) {
            invalidateSingleTermDerIndexes();
            invalidateEvalScatterIndexes();
            terms.add(termImpl);
            singleTermsByTermElementNum.computeIfAbsent(termImpl.getElementNum(), k -> new ArrayList<>())
                    .add(termImpl);
            for (Variable<V> v : termImpl.getVariables()) {
                termsByVariable.computeIfAbsent(v, k -> new ArrayList<>())
                        .add(termImpl);
            }
            termImpl.setEquation(equation);
            equationSystem.addEquationTerm(termImpl);
            matrixElementIndexes.reset();
            equationSystem.notifyEquationTermChange(termImpl, EquationTermEventType.EQUATION_TERM_ADDED);
            if (termImpl.hasRhs()) {
                throw new UnsupportedOperationException("Rhs not supported yet");
            }
        }
    }

    static class MatrixElementIndexes {
        private int[] indexes = new int[0];
        private int size = 0;

        private int get(int i) {
            if (i >= size) {
                if (i >= indexes.length) {
                    indexes = Arrays.copyOf(indexes, Math.max(16, Math.max(i + 1, indexes.length * 2)));
                }
                Arrays.fill(indexes, size, i + 1, -1);
                size = i + 1;
            }
            return indexes[i];
        }

        private void set(int i, int index) {
            indexes[i] = index;
        }

        void reset() {
            size = 0;
        }
    }

    private final MatrixElementIndexes matrixElementIndexes = new MatrixElementIndexes();

    // reusable buffer, to avoid allocating one per equation having single terms at each der() call
    private int[] computedRowsBuffer;

    // Complementary equations: a single equation paired with an element of this array, both sharing the same column,
    // with the invariant that at most one of the two is active at a time (a PV/PQ switch is such a pair: BUS_TARGET_V
    // and BUS_TARGET_Q of a same bus). The column stays allocated as long as one of the two is active, and the matrix
    // structure of that column is the union of the two patterns, so toggling the pair is only a value change: no
    // column renumbering, no Jacobian structure rebuild and no symbolic LU factorization.
    private SingleEquation<V, E>[] complementaryEquations;
    private boolean[] complementaryActive;
    private Variable<V>[] complementaryVariables;
    // occupancy of each paired slot as of the last column allocation, to detect the changes that are real structure
    // changes (both equations of the pair becoming inactive, or the slot being re-occupied)
    private boolean[] allocatedOccupied;
    private TIntArrayList pairedDirtyElements;

    public EquationArray(E type, int elementCount, EquationSystem<V, E> equationSystem) {
        this.type = Objects.requireNonNull(type);
        this.elementCount = elementCount;
        this.equationSystem = Objects.requireNonNull(equationSystem);
        elementActive = new boolean[elementCount];
        Arrays.fill(elementActive, true);
        hasSingleEquationTerms = new boolean[elementCount];
        Arrays.fill(hasSingleEquationTerms, false);
        this.length = elementCount; // all activated initially
        this.equationDerivativeVectorStartIndices = new int[elementCount + 1];
    }

    public E getType() {
        return type;
    }

    public int getElementCount() {
        return elementCount;
    }

    public List<SingleEquationTerm<V, E>> getSingleEquationTerms(int elementNum) {
        if (hasSingleEquationTerms[elementNum]) {
            return singleTermsByEquationElementNum.get(elementNum).terms;
        }
        return Collections.emptyList();
    }

    public int[] getElementNumToColumn() {
        if (elementNumToColumn == null) {
            elementNumToColumn = new int[elementCount];
            int column = firstColumn;
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                // the allocated occupancy, not the live one: it is what length was computed from, the two have to
                // stay consistent. They are brought back together by syncPairedElements().
                if (allocatedOccupied == null ? elementActive[elementNum] : allocatedOccupied[elementNum]) {
                    elementNumToColumn[elementNum] = column++;
                } else {
                    elementNumToColumn[elementNum] = -1;
                }
                if (allocatedOccupied != null) {
                    SingleEquation<V, E> complementary = complementaryEquations[elementNum];
                    if (complementary != null) {
                        // keep the paired single equation column in sync, it is part of the equation API
                        complementary.setColumn(complementaryActive[elementNum] ? elementNumToColumn[elementNum] : -1);
                    }
                }
            }
        }
        return elementNumToColumn;
    }

    public int getElementNumToColumn(int elementNum) {
        return getElementNumToColumn()[elementNum];
    }

    public int getColumnToElementNum(int column) {
        if (columnToElementNum == null) {
            columnToElementNum = new TIntIntHashMap(elementCount);
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                int c = getElementNumToColumn(elementNum);
                if (c != -1) {
                    columnToElementNum.put(c, elementNum);
                }
            }
        }
        return columnToElementNum.get(column);
    }

    private void invalidateElementNumToColumn() {
        elementNumToColumn = null;
        columnToElementNum = null;
        matrixElementIndexes.reset();
        invalidateEvalScatterIndexes();
    }

    void invalidateEvalScatterIndexes() {
        evalScatterTermElementNums = null;
        evalScatterColumns = null;
        evalSingleTerms = null;
        evalSingleTermColumns = null;
        evalComplementaryEquations = null;
        evalComplementaryColumns = null;
    }

    public EquationSystem<V, E> getEquationSystem() {
        return equationSystem;
    }

    public int getFirstColumn() {
        return firstColumn;
    }

    public void setFirstColumn(int firstColumn) {
        this.firstColumn = firstColumn;
        invalidateElementNumToColumn();
    }

    public int getLength() {
        return length;
    }

    public boolean isElementActive(int elementNum) {
        return elementActive[elementNum];
    }

    public void setElementActive(int elementNum, boolean active) {
        if (active != this.elementActive[elementNum]) {
            this.elementActive[elementNum] = active;
            if (isPaired(elementNum)) {
                onPairedActivationChange(elementNum);
                return;
            }
            if (allocatedOccupied != null) {
                allocatedOccupied[elementNum] = active;
            }
            if (active) {
                length++;
            } else {
                length--;
            }
            invalidateElementNumToColumn();
            equationSystem.notifyEquationArrayChange(this, elementNum,
                    active ? EquationEventType.EQUATION_ACTIVATED : EquationEventType.EQUATION_DEACTIVATED);
        }
    }

    /**
     * Pair {@code equation} with the element {@code elementNum} of this array: both share the same column and at most
     * one of the two is active at a time. The equation must be inactive and have a single derivative variable.
     */
    @SuppressWarnings("unchecked")
    public void setComplementaryEquation(int elementNum, SingleEquation<V, E> equation) {
        Objects.requireNonNull(equation);
        if (equation.isActive()) {
            throw new PowsyblException("A complementary equation has to be inactive when it is paired: " + equation);
        }
        Set<Variable<V>> variables = equation.getTermsByVariable().keySet();
        if (variables.size() != 1) {
            throw new PowsyblException("Only single variable complementary equations are supported: " + equation);
        }
        if (complementaryEquations == null) {
            complementaryEquations = new SingleEquation[elementCount];
            complementaryActive = new boolean[elementCount];
            complementaryVariables = new Variable[elementCount];
            allocatedOccupied = Arrays.copyOf(elementActive, elementCount);
            pairedDirtyElements = new TIntArrayList();
        }
        complementaryEquations[elementNum] = equation;
        complementaryVariables[elementNum] = variables.iterator().next();
        complementaryActive[elementNum] = false;
        equation.setComplementaryArray(this);
        invalidateElementNumToColumn();
    }

    public boolean isPaired(int elementNum) {
        return complementaryEquations != null && complementaryEquations[elementNum] != null;
    }

    private boolean isElementOccupied(int elementNum) {
        return elementActive[elementNum] || complementaryActive != null && complementaryActive[elementNum];
    }

    /**
     * The type of the equation that currently occupies the column of {@code elementNum}: the type of this array,
     * or the type of the complementary equation when the pair has switched to it.
     */
    public E getOccupantType(int elementNum) {
        return complementaryActive != null && complementaryActive[elementNum]
                ? complementaryEquations[elementNum].getType()
                : type;
    }

    void setComplementaryActive(int elementNum, boolean active) {
        if (active != complementaryActive[elementNum]) {
            complementaryActive[elementNum] = active;
            pairedDirtyElements.add(elementNum);
            invalidateEvalScatterIndexes();
            equationSystem.notifyComplementaryEquationChange(this, elementNum,
                    active ? EquationEventType.EQUATION_ACTIVATED : EquationEventType.EQUATION_DEACTIVATED);
        }
    }

    public SingleEquation<V, E> getComplementaryEquation(int elementNum) {
        return complementaryEquations == null ? null : complementaryEquations[elementNum];
    }

    /**
     * Undo the pairing of an element, permanently: the complementary equation goes back to being an ordinary single
     * equation with its own column. Used as a fallback when the two equations turn out not to be complementary.
     */
    private void unpair(int elementNum) {
        SingleEquation<V, E> complementary = complementaryEquations[elementNum];
        boolean wasActive = complementaryActive[elementNum];
        if (wasActive) {
            // give its variables back to the index before it forgets about the pairing
            equationSystem.notifyComplementaryEquationChange(this, elementNum, EquationEventType.EQUATION_DEACTIVATED);
        }
        complementaryEquations[elementNum] = null;
        complementaryVariables[elementNum] = null;
        complementaryActive[elementNum] = false;
        complementary.setComplementaryArray(null);
        complementary.setColumn(-1);
        if (allocatedOccupied[elementNum] != elementActive[elementNum]) {
            allocatedOccupied[elementNum] = elementActive[elementNum];
            length += elementActive[elementNum] ? 1 : -1;
        }
        invalidateElementNumToColumn();
        invalidateEvalScatterIndexes();
        if (wasActive) {
            // and let it be indexed as an ordinary equation
            equationSystem.notifyEquationChange(complementary, EquationEventType.EQUATION_ACTIVATED);
        }
    }

    private void onPairedActivationChange(int elementNum) {
        pairedDirtyElements.add(elementNum);
        invalidateEvalScatterIndexes();
        // As long as the slot stays occupied the column and the matrix structure do not change, only the values and
        // their zero pattern do. Whether it is a real structure change is decided later, in syncPairedElements(),
        // because the two activation changes of a switch are notified one after the other and the slot can be
        // transiently empty in between.
        equationSystem.notifyEquationArrayValuesChange(this, elementNum);
    }

    /**
     * Notify the structure changes of the paired elements that survived the pending activation changes: a slot that
     * became empty, or an empty slot that got occupied again. A slot that just switched from one equation of the pair
     * to the other is not a structure change and is not notified.
     */
    void syncPairedElements() {
        if (pairedDirtyElements == null || pairedDirtyElements.isEmpty()) {
            return;
        }
        int[] dirtyElements = pairedDirtyElements.toArray();
        pairedDirtyElements.resetQuick();
        for (int elementNum : dirtyElements) {
            if (elementActive[elementNum] && complementaryActive[elementNum]) {
                // the two equations are not complementary anymore, which can happen when a contingency changes the
                // voltage control structure: give the complementary equation its own column back
                unpair(elementNum);
                continue;
            }
            boolean occupied = isElementOccupied(elementNum);
            if (occupied != allocatedOccupied[elementNum]) {
                allocatedOccupied[elementNum] = occupied;
                if (occupied) {
                    length++;
                } else {
                    length--;
                }
                invalidateElementNumToColumn();
                // only the column layout changes here, the variables of the element and of its complementary equation
                // have already been reference counted when their activation changed
                equationSystem.notifyEquationArrayColumnChange(this, elementNum,
                        occupied ? EquationEventType.EQUATION_ACTIVATED : EquationEventType.EQUATION_DEACTIVATED);
            }
        }
    }

    public void updateElementEquation(LfElement element, boolean enable) {
        if (getType().getElementType() == element.getType()) {
            setElementActive(element.getNum(), enable);
        }
        for (var termArray : getTermArrays()) {
            if (termArray.getElementType() == element.getType() && termArray.hasTermElement(element.getNum())) {
                termArray.setTermElementActive(element.getNum(), enable);
            }
        }
        if (singleTermsByTermElementNum.containsKey(element.getNum())) {
            for (var singleTerm : singleTermsByTermElementNum.get(element.getNum())) {
                if (singleTerm.getElementType() == element.getType()) {
                    singleTerm.setActive(enable);
                }
            }
        }
    }

    public List<EquationTermArray<V, E>> getTermArrays() {
        return termArrays;
    }

    public void addTermArray(EquationTermArray<V, E> termArray) {
        Objects.requireNonNull(termArray);
        termArray.setEquationArray(this);
        termArrays.add(termArray);
        invalidateEquationDerivativeVectors();
        invalidateEvalScatterIndexes();
    }

    public Equation<V, E> getElement(int elementNum) {
        return new Equation<>() {
            @Override
            public E getType() {
                return EquationArray.this.getType();
            }

            @Override
            public int getElementNum() {
                return elementNum;
            }

            @Override
            public boolean isActive() {
                return isElementActive(elementNum);
            }

            @Override
            public void setActive(boolean active) {
                setElementActive(elementNum, active);
            }

            @Override
            public int getColumn() {
                return getElementNumToColumn(elementNum);
            }

            @Override
            public Equation<V, E> addTerm(EquationTerm<V, E> term) {
                // Either the term is in an EquationTermArray (vectorized term)
                if (term instanceof EquationTermArray.EquationTermArrayElementImpl<V, E> termArrayElement) {
                    termArrayElement.setEquation(this);
                    termArrayElement.equationTermArray.addTerm(elementNum, termArrayElement.termElementNum);
                // Either the term is an additional single term that is related to specific equations (single term)
                } else if (term instanceof SingleEquationTerm<V, E> singleEquationTerm) {
                    if (singleEquationTerm.getEquation() != null) {
                        throw new PowsyblException("Equation term already added to another equation: "
                                + term.getEquation());
                    }
                    singleTermsByEquationElementNum.computeIfAbsent(elementNum, k -> new AdditionalSingleTermsByEquation())
                            .addSingleTerm(singleEquationTerm, this);
                    hasSingleEquationTerms[elementNum] = true;
                } else {
                    throw new IllegalArgumentException("Unsupported EquationTerm");
                }
                return this;
            }

            @Override
            public <T extends EquationTerm<V, E>> Equation<V, E> addTerms(List<T> terms) {
                for (T term : terms) {
                    addTerm(term);
                }
                return this;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends EquationTerm<V, E>> List<T> getTerms() {
                List<T> terms = new ArrayList<>();
                for (EquationTermArray<V, E> termArray : termArrays) {
                    int[] termNumsConcatenatedStartIndices = termArray.getTermNumsConcatenatedStartIndices();
                    int iStart = termNumsConcatenatedStartIndices[elementNum];
                    int iEnd = termNumsConcatenatedStartIndices[elementNum + 1];
                    var termNums = termArray.getTermNumsConcatenated();
                    for (int i = iStart; i < iEnd; i++) {
                        int termNum = termNums.getQuick(i);
                        int termElementNum = termArray.getTermElementNum(termNum);
                        terms.add((T) new EquationTermArray.EquationTermArrayElementImpl<>(termArray, termElementNum));
                    }
                }
                if (singleTermsByEquationElementNum.containsKey(elementNum)) {
                    for (SingleEquationTerm<V, E> singleTerm : singleTermsByEquationElementNum.get(elementNum).terms) {
                        terms.add((T) singleTerm);
                    }
                }
                return terms;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends EquationTerm<V, E>> Map<Variable<V>, List<T>> getTermsByVariable() {
                Map<Variable<V>, List<T>> termsByVariable = new TreeMap<>();
                for (EquationTerm<V, E> term : this.getTerms()) {
                    for (Variable<V> v : term.getVariables()) {
                        termsByVariable.computeIfAbsent(v, k -> new ArrayList<>()).add((T) term);
                    }
                }
                return termsByVariable;
            }

            @Override
            public EquationSystem<V, E> getEquationSystem() {
                return equationSystem;
            }

            @Override
            public double eval() {
                double value = 0;
                for (EquationTermArray<V, E> termArray : termArrays) {
                    int[] termNumsConcatenatedStartIndices = termArray.getTermNumsConcatenatedStartIndices();
                    int iStart = termNumsConcatenatedStartIndices[elementNum];
                    int iEnd = termNumsConcatenatedStartIndices[elementNum + 1];
                    var termNums = termArray.getTermNumsConcatenated();
                    for (int i = iStart; i < iEnd; i++) {
                        int termNum = termNums.getQuick(i);
                        // skip inactive terms
                        if (termArray.isTermActive(termNum)) {
                            int termElementNum = termArray.getTermElementNum(termNum);
                            value += termArray.eval(termElementNum);
                        }
                    }
                }

                if (hasSingleEquationTerms[elementNum]) {
                    for (SingleEquationTerm<V, E> singleTerm : singleTermsByEquationElementNum.get(elementNum).terms) {
                        if (singleTerm.isActive()) {
                            value += singleTerm.eval();
                        }
                    }
                }
                return value;
            }

            @Override
            public String toString() {
                return "EquationFromEquationArray(elementNum=" + elementNum +
                        ", type=" + type +
                        ", column=" + getColumn() + ")";
            }
        };
    }

    private void updateEvalScatterIndexes() {
        if (evalScatterTermElementNums != null) {
            return;
        }
        int[] elementNumToColumnArray = getEvalScatterColumns();
        int termArrayCount = termArrays.size();
        evalScatterTermElementNums = new int[termArrayCount][];
        evalScatterColumns = new int[termArrayCount][];
        for (int termArrayNum = 0; termArrayNum < termArrayCount; termArrayNum++) {
            EquationTermArray<V, E> termArray = termArrays.get(termArrayNum);
            int[] sortedTermNums = termArray.getTermNumsSortedByTermElementNum();
            int[] termElementNums = new int[sortedTermNums.length];
            int[] columns = new int[sortedTermNums.length];
            int count = 0;
            for (int termNum : sortedTermNums) {
                // skip inactive terms
                if (!termArray.isTermActive(termNum)) {
                    continue;
                }
                // skip inactive equations
                int column = elementNumToColumnArray[termArray.getEquationElementNum(termNum)];
                if (column == -1) {
                    continue;
                }
                termElementNums[count] = termArray.getTermElementNum(termNum);
                columns[count] = column;
                count++;
            }
            evalScatterTermElementNums[termArrayNum] = Arrays.copyOf(termElementNums, count);
            evalScatterColumns[termArrayNum] = Arrays.copyOf(columns, count);
        }

        List<SingleEquationTerm<V, E>> singleTerms = new ArrayList<>();
        TIntArrayList singleTermColumns = new TIntArrayList();
        for (Map.Entry<Integer, AdditionalSingleTermsByEquation> e : singleTermsByEquationElementNum.entrySet()) {
            int column = elementNumToColumnArray[e.getKey()];
            if (column == -1) { // skip inactive equations
                continue;
            }
            for (SingleEquationTerm<V, E> singleTerm : e.getValue().terms) {
                singleTerms.add(singleTerm);
                singleTermColumns.add(column);
            }
        }
        evalSingleTerms = singleTerms.toArray(new SingleEquationTerm[0]);
        evalSingleTermColumns = singleTermColumns.toArray();

        updateEvalComplementaryIndexes();
    }

    /**
     * Columns to scatter the terms of this array to: the column of the element, or -1 when the element is inactive or
     * when its column is currently occupied by its complementary equation (the terms of this array do not contribute
     * to the column in that case).
     */
    private int[] getEvalScatterColumns() {
        int[] columns = getElementNumToColumn();
        if (complementaryActive == null) {
            return columns;
        }
        int[] scatterColumns = Arrays.copyOf(columns, elementCount);
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            if (complementaryActive[elementNum]) {
                scatterColumns[elementNum] = -1;
            }
        }
        return scatterColumns;
    }

    @SuppressWarnings("unchecked")
    private void updateEvalComplementaryIndexes() {
        if (complementaryActive == null) {
            evalComplementaryEquations = new SingleEquation[0];
            evalComplementaryColumns = new int[0];
            return;
        }
        int[] elementNumToColumnArray = getElementNumToColumn();
        List<SingleEquation<V, E>> equations = new ArrayList<>();
        TIntArrayList columns = new TIntArrayList();
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            if (complementaryActive[elementNum]) {
                equations.add(complementaryEquations[elementNum]);
                columns.add(elementNumToColumnArray[elementNum]);
            }
        }
        evalComplementaryEquations = equations.toArray(new SingleEquation[0]);
        evalComplementaryColumns = columns.toArray();
    }

    public void eval(double[] values) {
        updateEvalScatterIndexes();
        for (int termArrayNum = 0; termArrayNum < termArrays.size(); termArrayNum++) {
            // read the term value vector sequentially and scatter the values to the equation values
            double[] termValues = termArrays.get(termArrayNum).eval();
            int[] termElementNums = evalScatterTermElementNums[termArrayNum];
            int[] columns = evalScatterColumns[termArrayNum];
            for (int i = 0; i < termElementNums.length; i++) {
                values[columns[i]] += termValues[termElementNums[i]];
            }
        }
        for (int i = 0; i < evalSingleTerms.length; i++) {
            SingleEquationTerm<V, E> singleTerm = evalSingleTerms[i];
            if (singleTerm.isActive()) {
                values[evalSingleTermColumns[i]] += singleTerm.eval();
            }
        }
        for (int i = 0; i < evalComplementaryEquations.length; i++) {
            values[evalComplementaryColumns[i]] += evalComplementaryEquations[i].evalLhs();
        }
    }

    public interface DerHandler {

        int onDer(int column, int row, double value, int matrixElementIndex);
    }

    private void updateEquationDerivativeVectors() {
        if (equationDerivativeVector == null) {
            List<EquationDerivativeElement<?>> allTerms = new ArrayList<>();
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                equationDerivativeVectorStartIndices[elementNum] = allTerms.size();
                addEquationDerivativeVectorSortedTerms(elementNum, allTerms);
            }
            equationDerivativeVectorStartIndices[elementCount] = allTerms.size();
            equationDerivativeVector = new EquationDerivativeVector(allTerms, this);
        }
    }

    private void addEquationDerivativeVectorSortedTerms(int elementNum, List<EquationDerivativeElement<?>> allTerms) {
        // vectorize terms to evaluate
        List<EquationDerivativeElement<?>> terms = new ArrayList<>();
        for (int termArrayNum = 0; termArrayNum < termArrays.size(); termArrayNum++) {
            EquationTermArray<V, E> termArray = termArrays.get(termArrayNum);
            int[] termNumsConcatenatedStartIndices = termArray.getTermNumsConcatenatedStartIndices();
            int iStart = termNumsConcatenatedStartIndices[elementNum];
            int iEnd = termNumsConcatenatedStartIndices[elementNum + 1];
            var termNums = termArray.getTermNumsConcatenated();
            for (int i = iStart; i < iEnd; i++) {
                int termNum = termNums.getQuick(i);
                // for each term of each, add an entry for each derivative operation we need
                var termDerivatives = termArray.getTermDerivatives(termNum);
                for (Derivative<V> derivative : termDerivatives) {
                    terms.add(new EquationDerivativeElement<>(termArrayNum, termNum, derivative));
                }
            }
        }
        // Terms are sorted with variable comparator
        terms.sort(Comparator.comparing(o -> o.derivative.getVariable()));

        allTerms.addAll(terms);
    }

    void invalidateEquationDerivativeVectors() {
        equationDerivativeVector = null;
        matrixElementIndexes.reset();
    }

    private void invalidateSingleTermDerIndexes() {
        singleTermDerVariableRanges = null;
        singleTermDerVariables = null;
        singleTermDerTerms = null;
        singleTermDerVariableRows = null;
    }

    @SuppressWarnings("unchecked")
    private void updateSingleTermDerIndexes() {
        if (singleTermDerVariableRanges != null) {
            return;
        }
        singleTermDerVariableRanges = new int[elementCount + 1];
        List<Variable<V>> variables = new ArrayList<>();
        List<SingleEquationTerm<V, E>[]> terms = new ArrayList<>();
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            singleTermDerVariableRanges[elementNum] = variables.size();
            if (hasSingleEquationTerms[elementNum]) {
                for (var e : singleTermsByEquationElementNum.get(elementNum).termsByVariable.entrySet()) {
                    variables.add(e.getKey());
                    terms.add(e.getValue().toArray(new SingleEquationTerm[0]));
                }
            }
        }
        singleTermDerVariableRanges[elementCount] = variables.size();
        singleTermDerVariables = variables.toArray(new Variable[0]);
        singleTermDerTerms = terms.toArray(new SingleEquationTerm[0][]);
        singleTermDerVariableRows = new int[variables.size()];
    }

    public void der(DerHandler handler) {
        Objects.requireNonNull(handler);

        updateEquationDerivativeVectors();
        updateSingleTermDerIndexes();
        equationDerivativeVector.update();

        int[] rows = equationDerivativeVector.rows;
        double[] values = equationDerivativeVector.values;
        int[] elementNumToColumnArray = getElementNumToColumn();

        // calculate all derivative values
        // process column by column so equation by equation of the array
        int valueIndex = 0;
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            // skip elements that have no column (neither the element nor its complementary equation is active)
            int column = elementNumToColumnArray[elementNum];
            if (column == -1) {
                continue;
            }

            // for each equation of the array we already have the list of terms to derive and its variable sorted
            // by variable row (required by solvers)
            int iStart = this.equationDerivativeVectorStartIndices[elementNum];
            int iEnd = this.equationDerivativeVectorStartIndices[elementNum + 1];

            if (isPaired(elementNum)) {
                valueIndex = derPaired(handler, elementNum, column, iStart, iEnd, rows, values, valueIndex);
            } else if (hasSingleEquationTerms[elementNum]) {
                valueIndex = derWithSingleTerms(handler, elementNum, column, iStart, iEnd, rows, values, valueIndex);
            } else {
                // fast path: only vectorized terms, values of a same row are contiguous and just have to be summed
                double value = 0;
                int prevRow = -1;
                for (int i = iStart; i < iEnd; i++) {
                    // the derivative variable row
                    int row = rows[i];

                    // if an element at (row, column) is complete (we switch to another row), notify
                    if (row != prevRow) {
                        if (prevRow != -1) {
                            valueIndex = onDer(handler, column, prevRow, value, valueIndex);
                            value = 0;
                        }
                        prevRow = row;
                    }
                    value += values[i];
                }

                // remaining notif
                if (prevRow != -1) {
                    valueIndex = onDer(handler, column, prevRow, value, valueIndex);
                }
            }
        }
    }

    /**
     * Derivatives of a column shared by an element of this array and its complementary equation. The same rows are
     * always notified, whichever of the two is active: the union of the two patterns, which is the pattern of this
     * array element plus, when it is not already one of its rows, the row of the complementary equation variable.
     * Keeping the notified rows identical in both modes is what makes the switch a value only change for the solver.
     * The contribution of the equation that is not active is zero.
     */
    private int derPaired(DerHandler handler, int elementNum, int column, int iStart, int iEnd,
                          int[] rows, double[] values, int startValueIndex) {
        int valueIndex = startValueIndex;
        boolean complementary = complementaryActive[elementNum];
        Variable<V> complementaryVariable = complementaryVariables[elementNum];
        int complementaryRow = complementaryVariable.getRow();
        double complementaryValue = 0;
        if (complementary) {
            for (EquationTerm<V, E> term : complementaryEquations[elementNum].<EquationTerm<V, E>>getTerms()) {
                if (term.isActive()) {
                    complementaryValue += term.der(complementaryVariable);
                }
            }
        }
        boolean complementaryRowDone = complementaryRow == -1;

        int vStart = singleTermDerVariableRanges[elementNum];
        int vEnd = singleTermDerVariableRanges[elementNum + 1];
        for (int j = vStart; j < vEnd; j++) {
            singleTermDerVariableRows[j] = singleTermDerVariables[j].getRow();
        }
        int[] computedRows = getComputedRowsBuffer(iEnd - iStart + 1);
        int computedRowCount = 0;

        double value = 0;
        int prevRow = -1;
        for (int i = iStart; i < iEnd; i++) {
            int row = rows[i];
            // a variable of the array element can have been removed from the index while the column is occupied by
            // the complementary equation, it has no row anymore and so no matrix element
            if (row == -1) {
                continue;
            }
            if (prevRow != -1 && row != prevRow) {
                computedRows[computedRowCount++] = prevRow;
                value += evalSingleTermsDer(vStart, vEnd, prevRow);
                valueIndex = onDer(handler, column, prevRow,
                        pairedValue(value, complementary, prevRow, complementaryRow, complementaryValue), valueIndex);
                complementaryRowDone |= prevRow == complementaryRow;
                value = 0;
            }
            prevRow = row;
            value += values[i];
        }
        if (prevRow != -1) {
            computedRows[computedRowCount++] = prevRow;
            value += evalSingleTermsDer(vStart, vEnd, prevRow);
            valueIndex = onDer(handler, column, prevRow,
                    pairedValue(value, complementary, prevRow, complementaryRow, complementaryValue), valueIndex);
            complementaryRowDone |= prevRow == complementaryRow;
        }

        // single terms with a variable that has not been seen in the vectorized terms
        for (int j = vStart; j < vEnd; j++) {
            int row = singleTermDerVariableRows[j];
            if (row != -1 && !contains(computedRows, computedRowCount, row)) {
                value = 0;
                for (var term : singleTermDerTerms[j]) {
                    if (term.isActive()) {
                        value += term.der(singleTermDerVariables[j]);
                    }
                }
                valueIndex = onDer(handler, column, row,
                        pairedValue(value, complementary, row, complementaryRow, complementaryValue), valueIndex);
                complementaryRowDone |= row == complementaryRow;
            }
        }

        // the complementary equation variable is not one of the rows of this array element: it is an extra row of the
        // union, always notified so that the structure of the column does not depend on which equation is active
        if (!complementaryRowDone) {
            valueIndex = onDer(handler, column, complementaryRow, complementary ? complementaryValue : 0, valueIndex);
        }
        return valueIndex;
    }

    private static double pairedValue(double value, boolean complementary, int row, int complementaryRow, double complementaryValue) {
        if (!complementary) {
            return value;
        }
        return row == complementaryRow ? complementaryValue : 0;
    }

    private int derWithSingleTerms(DerHandler handler, int elementNum, int column, int iStart, int iEnd,
                                   int[] rows, double[] values, int startValueIndex) {
        int valueIndex = startValueIndex;
        int vStart = singleTermDerVariableRanges[elementNum];
        int vEnd = singleTermDerVariableRanges[elementNum + 1];
        for (int j = vStart; j < vEnd; j++) {
            singleTermDerVariableRows[j] = singleTermDerVariables[j].getRow();
        }
        int[] computedRows = getComputedRowsBuffer(iEnd - iStart + 1);
        int computedRowCount = 0;

        // process term by term
        double value = 0;
        int prevRow = -1;
        for (int i = iStart; i < iEnd; i++) {

            // the derivative variable row
            int row = rows[i];

            // if an element at (row, column) is complete (we switch to another row), notify
            if (prevRow != -1 && row != prevRow) {
                computedRows[computedRowCount++] = prevRow;
                value += evalSingleTermsDer(vStart, vEnd, prevRow);
                valueIndex = onDer(handler, column, prevRow, value, valueIndex);
                value = 0;
            }
            prevRow = row;
            value += values[i];
        }

        // remaining notif
        if (prevRow != -1) {
            computedRows[computedRowCount++] = prevRow;
            value += evalSingleTermsDer(vStart, vEnd, prevRow);
            valueIndex = onDer(handler, column, prevRow, value, valueIndex);
        }

        // single terms with a variable that has not been seen in the vectorized terms
        for (int j = vStart; j < vEnd; j++) {
            int row = singleTermDerVariableRows[j];
            if (row != -1 && !contains(computedRows, computedRowCount, row)) {
                value = 0;
                for (var term : singleTermDerTerms[j]) {
                    if (term.isActive()) {
                        value += term.der(singleTermDerVariables[j]);
                    }
                }
                valueIndex = onDer(handler, column, row, value, valueIndex);
            }
        }
        return valueIndex;
    }

    private int[] getComputedRowsBuffer(int size) {
        if (computedRowsBuffer == null || computedRowsBuffer.length < size) {
            computedRowsBuffer = new int[size];
        }
        return computedRowsBuffer;
    }

    private static boolean contains(int[] values, int count, int value) {
        for (int i = 0; i < count; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    private double evalSingleTermsDer(int vStart, int vEnd, int row) {
        double value = 0;
        for (int j = vStart; j < vEnd; j++) {
            if (singleTermDerVariableRows[j] == row) {
                for (var term : singleTermDerTerms[j]) {
                    if (term.isActive()) {
                        value += term.der(singleTermDerVariables[j]);
                    }
                }
            }
        }
        return value;
    }

    private int onDer(DerHandler handler, int column, int row, double value, int valueIndex) {
        int matrixElementIndex = handler.onDer(column, row, value, matrixElementIndexes.get(valueIndex));
        matrixElementIndexes.set(valueIndex, matrixElementIndex);
        return valueIndex + 1;
    }

    public void write(Writer writer, boolean writeInactiveEquations) throws IOException {
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            if (writeInactiveEquations || isElementActive(elementNum)) {
                if (!isElementActive(elementNum)) {
                    writer.write("[ ");
                }
                writer.append(type.getSymbol())
                        .append("[")
                        .append(String.valueOf(elementNum))
                        .append("] = ");
                boolean first = true;
                for (EquationTermArray<V, E> termArray : termArrays) {
                    if (termArray.write(writer, writeInactiveEquations, elementNum, first)) {
                        first = false;
                    }
                }
                if (hasSingleEquationTerms[elementNum]) {
                    List<SingleEquationTerm<V, E>> activeTerms = writeInactiveEquations ?
                        getSingleEquationTerms(elementNum) :
                        getSingleEquationTerms(elementNum).stream().filter(SingleEquationTerm::isActive).toList();
                    for (SingleEquationTerm<V, E> term : activeTerms) {
                        if (!first) {
                            writer.append(" + ");
                        }
                        if (!term.isActive()) {
                            writer.write("[ ");
                        }
                        term.write(writer);
                        if (!term.isActive()) {
                            writer.write(" ]");
                        }
                    }
                }
                if (!isElementActive(elementNum)) {
                    writer.write(" ]");
                }
                writer.append(System.lineSeparator());
            }
        }
    }
}
