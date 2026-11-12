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

    // for fast derivative values update (same matrix structure): matrix value index of each derivative vector entry
    // and of each additional single term variable, recorded during last full derivative computation
    private int[] entryMatrixIndex;
    private final Map<Integer, int[]> additionalMatrixIndexByElementNum = new HashMap<>();
    private boolean fastDerReady = false;

    // ghost mode: a deactivated element keeps its column (see EquationSystemIndex ghost mode)
    private boolean ghostMode = false;
    private boolean[] elementGhost;
    private int ghostCount = 0;
    private boolean[] elementInStructure; // elements having a column at last index build
    private int newActiveCount = 0; // active elements without column at last index build

    // for fast evaluation: for each term array, terms of elements having a column as flat (term num, term element num, column)
    private record FlatEvalTable(int[] termNums, int[] termElementNums, int[] columns) {
    }

    private List<FlatEvalTable> flatEvalTables;

    private List<FlatEvalTable> getFlatEvalTables() {
        if (flatEvalTables == null) {
            int[] columns = getElementNumToColumn();
            List<FlatEvalTable> tables = new ArrayList<>(termArrays.size());
            for (EquationTermArray<V, E> termArray : termArrays) {
                int[] startIndices = termArray.getTermNumsConcatenatedStartIndices();
                var concatenatedTermNums = termArray.getTermNumsConcatenated();
                TIntArrayList termNums = new TIntArrayList();
                TIntArrayList termElementNums = new TIntArrayList();
                TIntArrayList termColumns = new TIntArrayList();
                for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                    // ghost elements are included (their columns are zeroed after evaluation) so that the table remains
                    // valid when they are reactivated
                    if (hasColumn(elementNum)) {
                        for (int i = startIndices[elementNum]; i < startIndices[elementNum + 1]; i++) {
                            int termNum = concatenatedTermNums.getQuick(i);
                            termNums.add(termNum);
                            termElementNums.add(termArray.getTermElementNum(termNum));
                            termColumns.add(columns[elementNum]);
                        }
                    }
                }
                tables.add(new FlatEvalTable(termNums.toArray(), termElementNums.toArray(), termColumns.toArray()));
            }
            flatEvalTables = tables;
        }
        return flatEvalTables;
    }

    private final class AdditionalSingleTermsByEquation {
        private final List<SingleEquationTerm<V, E>> terms = new ArrayList<>();
        private final TreeMap<Variable<V>, List<SingleEquationTerm<V, E>>> termsByVariable = new TreeMap<>();

        void addSingleTerm(SingleEquationTerm<V, E> termImpl, Equation<V, E> equation) {
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
        private final TIntArrayList indexes = new TIntArrayList();

        private int get(int i) {
            if (i >= indexes.size()) {
                indexes.add(-1);
            }
            return indexes.getQuick(i);
        }

        private void set(int i, int index) {
            indexes.setQuick(i, index);
        }

        void reset() {
            indexes.clear();
        }
    }

    private final MatrixElementIndexes matrixElementIndexes = new MatrixElementIndexes();

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
                if (hasColumn(elementNum)) {
                    elementNumToColumn[elementNum] = column++;
                } else {
                    elementNumToColumn[elementNum] = -1;
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
        flatEvalTables = null;
        columnToElementNum = null;
        if (!ghostMode) {
            // in ghost mode, column changes can be transient (activation then deactivation of an element without
            // column) and the structure kept: recorded matrix element indexes must not be lost (they are anyway
            // overwritten when the Jacobian matrix is rebuilt)
            matrixElementIndexes.reset();
        }
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
            if (active && elementGhost != null && elementGhost[elementNum]) {
                // a ghost element is reactivated: it already has a column
                elementGhost[elementNum] = false;
                ghostCount--;
            } else if (!active && ghostMode && elementInStructure[elementNum]) {
                // keep the column of the element as a ghost
                elementGhost[elementNum] = true;
                ghostCount++;
            } else {
                if (active) {
                    length++;
                } else {
                    length--;
                }
                if (ghostMode && !elementInStructure[elementNum]) {
                    newActiveCount += active ? 1 : -1;
                }
                invalidateElementNumToColumn();
            }
            equationSystem.notifyEquationArrayChange(this, elementNum,
                    active ? EquationEventType.EQUATION_ACTIVATED : EquationEventType.EQUATION_DEACTIVATED);
        }
    }

    /**
     * True if the element has a column in the equation system: active or ghost.
     */
    public boolean hasColumn(int elementNum) {
        return elementActive[elementNum] || elementGhost != null && elementGhost[elementNum];
    }

    public boolean isElementGhost(int elementNum) {
        return elementGhost != null && elementGhost[elementNum];
    }

    int getGhostCount() {
        return ghostCount;
    }

    /**
     * Number of active elements which had no column at last index build (structure change).
     */
    int getNewActiveCount() {
        return newActiveCount;
    }

    void setGhostMode(boolean ghostMode) {
        this.ghostMode = ghostMode;
        if (ghostMode && elementGhost == null) {
            elementGhost = new boolean[elementCount];
            elementInStructure = new boolean[elementCount];
        }
        snapshotStructure();
    }

    /**
     * Record elements having a column (called when the equation system index is built).
     */
    void snapshotStructure() {
        if (ghostMode) {
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                elementInStructure[elementNum] = hasColumn(elementNum);
            }
            newActiveCount = 0;
        }
    }

    /**
     * Convert ghost elements into really removed elements (they lose their column).
     */
    void removeGhosts() {
        if (ghostCount > 0) {
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                if (elementGhost[elementNum]) {
                    elementGhost[elementNum] = false;
                    length--;
                }
            }
            ghostCount = 0;
            invalidateElementNumToColumn();
        }
    }

    /**
     * Matrix value index of the derivative of the element equation with respect to the variable at the given row, as
     * recorded during last full derivative computation, or -1 if not found.
     */
    int findMatrixIndex(int elementNum, int row) {
        if (!fastDerReady || equationDerivativeVector == null) {
            return -1;
        }
        int iStart = equationDerivativeVectorStartIndices[elementNum];
        int iEnd = equationDerivativeVectorStartIndices[elementNum + 1];
        for (int i = iStart; i < iEnd; i++) {
            if (equationDerivativeVector.rows[i] == row) {
                return entryMatrixIndex[i];
            }
        }
        return -1;
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
        flatEvalTables = null;
        invalidateEquationDerivativeVectors();
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

    public void eval(double[] values) {
        List<FlatEvalTable> tables = getFlatEvalTables();
        for (int t = 0; t < termArrays.size(); t++) {
            EquationTermArray<V, E> termArray = termArrays.get(t);
            double[] termValues = termArray.eval();
            FlatEvalTable table = tables.get(t);
            int[] termNums = table.termNums();
            int[] termElementNums = table.termElementNums();
            int[] columns = table.columns();
            for (int k = 0; k < termNums.length; k++) {
                // skip inactive terms
                if (termArray.isTermActive(termNums[k])) {
                    values[columns[k]] += termValues[termElementNums[k]];
                }
            }
        }
        for (Map.Entry<Integer, AdditionalSingleTermsByEquation> singleTermsEntry : singleTermsByEquationElementNum.entrySet()) {
            if (!elementActive[singleTermsEntry.getKey()]) {
                continue;
            }
            for (SingleEquationTerm<V, E> singleTerm : singleTermsEntry.getValue().terms) {
                if (singleTerm.isActive()) {
                    values[getElementNumToColumn(singleTermsEntry.getKey())] += singleTerm.eval();
                }
            }
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
        fastDerReady = false;
        flatEvalTables = null;
    }

    public void der(DerHandler handler) {
        Objects.requireNonNull(handler);

        updateEquationDerivativeVectors();
        equationDerivativeVector.update(this);

        int entryCount = equationDerivativeVectorStartIndices[elementCount];
        if (entryMatrixIndex == null || entryMatrixIndex.length != entryCount) {
            entryMatrixIndex = new int[entryCount];
        }
        Arrays.fill(entryMatrixIndex, -1);
        additionalMatrixIndexByElementNum.clear();

        // calculate all derivative values
        // process column by column so equation by equation of the array
        int valueIndex = 0;
        // derivatives of additional single terms of current element, by variable row
        int[] additionalRows = new int[4];
        double[] additionalValues = new double[4];
        boolean[] additionalDone = new boolean[4];
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            // skip elements without column (ghost elements keep their structure, with inactive terms)
            if (!hasColumn(elementNum)) {
                continue;
            }

            int column = getElementNumToColumn(elementNum);

            // pre-compute derivatives of additional single terms (not vectorized) of this element
            int additionalCount = 0;
            if (hasSingleEquationTerms[elementNum]) {
                AdditionalSingleTermsByEquation additionalTerms = singleTermsByEquationElementNum.get(elementNum);
                for (var e : additionalTerms.termsByVariable.entrySet()) {
                    Variable<V> v = e.getKey();
                    int vRow = v.getRow();
                    if (vRow == -1) {
                        continue;
                    }
                    double d = 0;
                    for (var term : e.getValue()) {
                        if (term.isActive()) {
                            d += term.der(v);
                        }
                    }
                    if (additionalCount == additionalRows.length) {
                        additionalRows = Arrays.copyOf(additionalRows, additionalCount * 2);
                        additionalValues = Arrays.copyOf(additionalValues, additionalCount * 2);
                        additionalDone = Arrays.copyOf(additionalDone, additionalCount * 2);
                    }
                    additionalRows[additionalCount] = vRow;
                    additionalValues[additionalCount] = d;
                    additionalDone[additionalCount] = false;
                    additionalCount++;
                }
            }

            // for each equation of the array we already have the list of terms to derive and its variable sorted
            // by variable (so that all derivatives of a same variable are contiguous)
            int[] additionalMatrixIndex = additionalCount > 0 ? new int[additionalCount] : null;
            double value = 0;
            int prevRow = -1;
            int segmentStart = -1;
            int iStart = this.equationDerivativeVectorStartIndices[elementNum];
            int iEnd = this.equationDerivativeVectorStartIndices[elementNum + 1];
            for (int i = iStart; i < iEnd; i++) {

                // the derivative variable row
                int row = equationDerivativeVector.rows[i];

                // if an element at (row, column) is complete (we switch to another row), notify
                if (prevRow != -1 && row != prevRow) {
                    value += consumeAdditionalValue(prevRow, additionalRows, additionalValues, additionalDone, additionalCount);
                    int matrixIndex = onDer(handler, column, prevRow, value, valueIndex);
                    recordMatrixIndex(matrixIndex, segmentStart, i, prevRow, additionalRows, additionalMatrixIndex, additionalCount);
                    valueIndex++;
                    value = 0;
                }
                if (row != prevRow) {
                    segmentStart = i;
                }
                prevRow = row;
                value += equationDerivativeVector.values[i];
            }

            // remaining notif
            if (prevRow != -1) {
                value += consumeAdditionalValue(prevRow, additionalRows, additionalValues, additionalDone, additionalCount);
                int matrixIndex = onDer(handler, column, prevRow, value, valueIndex);
                recordMatrixIndex(matrixIndex, segmentStart, iEnd, prevRow, additionalRows, additionalMatrixIndex, additionalCount);
                valueIndex++;
            }

            // additional single terms derivatives on variables not already notified
            for (int k = 0; k < additionalCount; k++) {
                if (!additionalDone[k]) {
                    additionalMatrixIndex[k] = onDer(handler, column, additionalRows[k], additionalValues[k], valueIndex);
                    valueIndex++;
                }
            }
            if (additionalMatrixIndex != null) {
                additionalMatrixIndexByElementNum.put(elementNum, additionalMatrixIndex);
            }
        }
        fastDerReady = true;
    }

    private void recordMatrixIndex(int matrixIndex, int segmentStart, int segmentEnd, int row,
                                   int[] additionalRows, int[] additionalMatrixIndex, int additionalCount) {
        for (int i = segmentStart; i < segmentEnd; i++) {
            entryMatrixIndex[i] = matrixIndex;
        }
        for (int k = 0; k < additionalCount; k++) {
            if (additionalRows[k] == row) {
                additionalMatrixIndex[k] = matrixIndex;
            }
        }
    }

    /**
     * True if derivative values can be directly added to the matrix values with {@link #derValues(double[])}, i.e.
     * a full derivative computation with {@link #der(DerHandler)} has been done with the current structure.
     */
    boolean isFastDerReady() {
        return fastDerReady && equationDerivativeVector != null;
    }

    /**
     * Add derivative values to the given matrix values array, using matrix value indexes recorded during last call to
     * {@link #der(DerHandler)}. The matrix structure must not have changed since.
     */
    void derValues(double[] matrixValues) {
        equationDerivativeVector.addValues(this, entryMatrixIndex, matrixValues);
        for (var e : additionalMatrixIndexByElementNum.entrySet()) {
            int elementNum = e.getKey();
            if (!elementActive[elementNum]) {
                continue;
            }
            int[] additionalMatrixIndex = e.getValue();
            int k = 0;
            for (var e2 : singleTermsByEquationElementNum.get(elementNum).termsByVariable.entrySet()) {
                Variable<V> v = e2.getKey();
                if (v.getRow() == -1) {
                    continue;
                }
                double d = 0;
                for (var term : e2.getValue()) {
                    if (term.isActive()) {
                        d += term.der(v);
                    }
                }
                matrixValues[additionalMatrixIndex[k++]] += d;
            }
        }
    }

    private static double consumeAdditionalValue(int row, int[] additionalRows, double[] additionalValues, boolean[] additionalDone,
                                                 int additionalCount) {
        double value = 0;
        for (int k = 0; k < additionalCount; k++) {
            if (additionalRows[k] == row) {
                additionalDone[k] = true;
                value += additionalValues[k];
            }
        }
        return value;
    }

    private int onDer(DerHandler handler, int column, int row, double value, int valueIndex) {
        int matrixElementIndex = handler.onDer(column, row, value, matrixElementIndexes.get(valueIndex));
        matrixElementIndexes.set(valueIndex, matrixElementIndex);
        return matrixElementIndex;
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
