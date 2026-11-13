/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.PowsyblException;
import gnu.trove.map.hash.TLongObjectHashMap;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class EquationSystemIndex<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemListener<V, E> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EquationSystemIndex.class);

    private final EquationSystem<V, E> equationSystem;

    private final Set<SingleEquation<V, E>> sortedSetEquationsToSolve = new TreeSet<>();

    // variable reference counting in equation terms
    private final Map<Variable<V>, MutableInt> sortedMapVariablesToFindRefCount = new TreeMap<>();

    private List<SingleEquation<V, E>> sortedSingleEquationsToSolve = Collections.emptyList();

    private List<EquationArray<V, E>> sortedEquationArraysToSolve = Collections.emptyList();

    private List<Variable<V>> sortedVariablesToFind = Collections.emptyList();

    private int columnCount = 0;

    private int columnCountFromArrayEquations = 0;

    private int rowCount = 0;

    private boolean equationsIndexValid = false;

    private boolean variablesIndexValid = false;

    private final List<EquationSystemIndexListener<V, E>> listeners = new ArrayList<>();

    // ghost mode: deactivated equations keep their column and variables not referenced anymore keep their row, as long
    // as each ghost equation is paired on the diagonal with an orphan variable. The Jacobian structure is then kept
    // (identity on ghost columns), which avoids rebuilding the equation system index, the Jacobian matrix and its LU
    // decomposition each time equations are temporarily deactivated and then reactivated.
    private boolean ghostMode = false;
    private final Set<SingleEquation<V, E>> ghostSingleEquations = new LinkedHashSet<>();
    private final Set<Variable<V>> orphanVariables = new LinkedHashSet<>();
    private boolean structureCheckNeeded = false; // ghost mode: changes to be checked at next update
    private boolean forceRebuild = false; // ghost mode: a change requires a rebuild (equation creation or removal)
    private final Set<SingleEquation<V, E>> newEquations = new HashSet<>(); // ghost mode: active single equations without column
    private final Set<Variable<V>> newVariables = new HashSet<>(); // ghost mode: variables to find without row
    private int[] ghostColumns = new int[0];

    public EquationSystemIndex(EquationSystem<V, E> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        equationSystem.addListener(this);
    }

    public void addListener(EquationSystemIndexListener<V, E> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(EquationSystemIndexListener<V, E> listener) {
        listeners.remove(Objects.requireNonNull(listener));
    }

    private void notifyEquationChange(SingleEquation<V, E> equation, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationChange(equation, changeType));
    }

    private void notifyVariableChange(Variable<V> variable, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onVariableChange(variable, changeType));
    }

    private void notifyEquationTermChange(SingleEquationTerm<V, E> term) {
        listeners.forEach(listener -> listener.onEquationTermChange(term));
    }

    private void notifyEquationArrayChange(EquationArray<V, E> equationArray, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationArrayChange(equationArray, changeType));
    }

    private void notifyEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationTermArrayChange(equationTermArray, termNum, changeType));
    }

    private void notifyEquationIndexOrderChange() {
        listeners.forEach(EquationSystemIndexListener::onEquationIndexOrderChanged);
    }

    private void updateEquationColumns(Collection<SingleEquation<V, E>> singleEquations, Collection<EquationArray<V, E>> equationArrays) {
        for (SingleEquation<V, E> equation : singleEquations) {
            equation.setColumn(columnCount++);
        }
        for (EquationArray<V, E> equationArray : equationArrays) {
            equationArray.setFirstColumn(columnCount);
            columnCount += equationArray.getLength();
            columnCountFromArrayEquations += equationArray.getLength();
            sortedEquationArraysToSolve.add(equationArray);
        }
    }

    private void updateEquationsToSolve() {
        updateEquationsToSolve(null);
    }

    private void updateEquationsToSolve(Predicate<E> isSeparatedInFirstPart) {
        sortedSingleEquationsToSolve = sortedSetEquationsToSolve.stream().toList();
        sortedEquationArraysToSolve = new ArrayList<>();
        columnCount = 0;
        columnCountFromArrayEquations = 0;
        if (isSeparatedInFirstPart == null) {
            // If there is no need of separating, columns are arranged in this order :
            // - Single equations
            // - Equation arrays
            updateEquationColumns(sortedSingleEquationsToSolve, equationSystem.getEquationArrays());
        } else {
            // If there is a separating predicate (Used for Fast Decoupled), columns are arranged in this order :
            // - First part of single equations
            // - First part of equation arrays
            // - Second part of single equations
            // - Second part of equation arrays
            Map<Boolean, List<SingleEquation<V, E>>> separatedSingleEquationsToSolve = sortedSingleEquationsToSolve.stream()
                    .collect(Collectors.partitioningBy(e -> isSeparatedInFirstPart.test(e.getType())));
            Map<Boolean, List<EquationArray<V, E>>> separatedEquationArrays = equationSystem.getEquationArrays().stream()
                    .collect(Collectors.partitioningBy(e -> isSeparatedInFirstPart.test(e.getType())));
            updateEquationColumns(separatedSingleEquationsToSolve.get(true), separatedEquationArrays.get(true)); // Filling first part
            updateEquationColumns(separatedSingleEquationsToSolve.get(false), separatedEquationArrays.get(false)); // Filling second part
            sortedSingleEquationsToSolve = Stream.concat(separatedSingleEquationsToSolve.get(true).stream(), separatedSingleEquationsToSolve.get(false).stream()).toList();
        }
        equationsIndexValid = true;
        if (ghostMode) {
            for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
                equationArray.snapshotStructure();
            }
        }
        if (isSeparatedInFirstPart == null && equationSystem.getDiagonalVariableTypeProvider() != null) {
            // variable rows are aligned on equation columns
            variablesIndexValid = false;
        }
        notifyEquationIndexOrderChange();
        LOGGER.debug("Equations index updated ({} columns including {} from array equations)",
                columnCount, columnCountFromArrayEquations);

    }

    private void updateVariablesToFind() {
        updateVariablesToFind(null);
    }

    void invalidateVariablesIndex() {
        variablesIndexValid = false;
    }

    private static long variableKey(Enum<?> type, int elementNum) {
        return ((long) type.ordinal() << 32) | (elementNum & 0xFFFFFFFFL);
    }

    /**
     * Find the variable to find that is naturally paired with the equation (same element, type given by the
     * equation system diagonal variable type provider), or null if there is not.
     */
    private Variable<V> getDiagonalVariable(Function<E, V> diagonalVariableTypeProvider, TLongObjectHashMap<Variable<V>> variablesByKey,
                                            int elementNum, E equationType) {
        V variableType = diagonalVariableTypeProvider.apply(equationType);
        if (variableType == null) {
            return null;
        }
        return variablesByKey.get(variableKey(variableType, elementNum));
    }

    /**
     * Number variables so that the variable naturally paired with the equation of column i is at row i. Remaining
     * variables fill the remaining rows in the variable natural order. This gives a Jacobian matrix with a structurally
     * non-zero diagonal and an almost symmetric pattern, much better for sparse LU fill-in than numbering equations
     * (single equations, then equation arrays) and variables (sorted by element) independently.
     *
     * @return false if the numbering cannot be aligned (not a square system)
     */
    private boolean updateAlignedVariablesToFind(Function<E, V> diagonalVariableTypeProvider) {
        if (!equationsIndexValid) {
            updateEquationsToSolve();
        }
        int variableCount = sortedMapVariablesToFindRefCount.size();
        if (variableCount != columnCount) {
            return false;
        }
        List<Variable<V>> rowToVariable = new ArrayList<>(Collections.nCopies(variableCount, (Variable<V>) null));
        // remember previous rows, to keep the state vector consistent with the new numbering
        int[] previousRows = new int[variableCount];
        TLongObjectHashMap<Variable<V>> variablesByKey = new TLongObjectHashMap<>(variableCount * 2);
        int k = 0;
        for (Variable<V> variable : sortedMapVariablesToFindRefCount.keySet()) {
            previousRows[k++] = variable.getRow();
            variable.setRow(-1);
            variablesByKey.put(variableKey(variable.getType(), variable.getElementNum()), variable);
        }
        for (SingleEquation<V, E> equation : sortedSingleEquationsToSolve) {
            Variable<V> variable = getDiagonalVariable(diagonalVariableTypeProvider, variablesByKey, equation.getElementNum(), equation.getType());
            if (variable != null && variable.getRow() == -1) {
                variable.setRow(equation.getColumn());
                rowToVariable.set(equation.getColumn(), variable);
            }
        }
        for (EquationArray<V, E> equationArray : sortedEquationArraysToSolve) {
            int[] elementNumToColumn = equationArray.getElementNumToColumn();
            for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
                if (equationArray.hasColumn(elementNum)) {
                    int column = elementNumToColumn[elementNum];
                    Variable<V> variable = getDiagonalVariable(diagonalVariableTypeProvider, variablesByKey, elementNum, equationArray.getType());
                    if (variable != null && variable.getRow() == -1) {
                        variable.setRow(column);
                        rowToVariable.set(column, variable);
                    }
                }
            }
        }
        // remaining variables fill free rows
        int freeRow = 0;
        for (Variable<V> variable : sortedMapVariablesToFindRefCount.keySet()) {
            if (variable.getRow() == -1) {
                while (rowToVariable.get(freeRow) != null) {
                    freeRow++;
                }
                variable.setRow(freeRow);
                rowToVariable.set(freeRow, variable);
            }
        }
        sortedVariablesToFind = Collections.unmodifiableList(rowToVariable);
        rowCount = variableCount;
        permuteStateVector(previousRows);
        return true;
    }

    /**
     * As variable rows depend on equation columns, they may change while the state vector is valid (for instance when
     * an outer loop switches equations). Permute in place the state vector values so that terms evaluated before the
     * next state vector initialization still read the right values. Values of variables which had no row are left
     * unchanged (as before when the variable set changes).
     */
    private void permuteStateVector(int[] previousRows) {
        double[] x = equationSystem.getStateVector().get();
        if (x == null || x.length != previousRows.length) {
            return;
        }
        double[] previousX = x.clone();
        int k = 0;
        for (Variable<V> variable : sortedMapVariablesToFindRefCount.keySet()) {
            int previousRow = previousRows[k++];
            if (previousRow >= 0 && previousRow < previousX.length) {
                x[variable.getRow()] = previousX[previousRow];
            }
        }
    }

    private void updateVariablesToFind(Predicate<V> isSeparatedInFirstPart) {
        Function<E, V> diagonalVariableTypeProvider = equationSystem.getDiagonalVariableTypeProvider();
        if (isSeparatedInFirstPart == null && diagonalVariableTypeProvider != null
                && updateAlignedVariablesToFind(diagonalVariableTypeProvider)) {
            variablesIndexValid = true;
            LOGGER.debug("Variables index updated ({} rows, aligned with equations)", rowCount);
            return;
        }
        sortedVariablesToFind = sortedMapVariablesToFindRefCount.keySet().stream().sorted().toList();
        rowCount = 0;
        if (isSeparatedInFirstPart == null) {
            for (Variable<V> variable : sortedVariablesToFind) {
                variable.setRow(rowCount++);
            }
        } else {
            // If there is a separating predicate (Used for Fast Decoupled), rows are arranged in two parts :
            Map<Boolean, List<Variable<V>>> separatedVariables = sortedVariablesToFind.stream()
                    .collect(Collectors.partitioningBy(v -> isSeparatedInFirstPart.test(v.getType())));
            for (boolean isFirstPart : List.of(true, false)) {
                for (Variable<V> variable : separatedVariables.get(isFirstPart)) {
                    variable.setRow(rowCount++);
                }
            }
            sortedVariablesToFind = Stream.concat(separatedVariables.get(true).stream(), separatedVariables.get(false).stream()).toList();
        }
        variablesIndexValid = true;
        LOGGER.debug("Variables index updated ({} rows)", rowCount);
    }

    private void onGhostModeChange() {
        structureCheckNeeded = true;
    }

    public boolean isGhostMode() {
        return ghostMode;
    }

    /**
     * Enable or disable ghost mode (see field comment). Disabling it removes existing ghosts.
     */
    public void setGhostMode(boolean ghostMode) {
        if (ghostMode == this.ghostMode) {
            return;
        }
        update();
        if (!ghostMode) {
            boolean hadGhosts = hasGhosts();
            removeGhosts();
            if (hadGhosts) {
                equationsIndexValid = false;
                variablesIndexValid = false;
            }
        }
        this.ghostMode = ghostMode;
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            equationArray.setGhostMode(ghostMode);
        }
        structureCheckNeeded = false;
        forceRebuild = false;
        newEquations.clear();
        newVariables.clear();
    }

    private boolean hasGhosts() {
        if (!ghostSingleEquations.isEmpty() || !orphanVariables.isEmpty()) {
            return true;
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getGhostCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNewEquationsOrVariables() {
        if (!newEquations.isEmpty() || !newVariables.isEmpty()) {
            return true;
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getNewActiveCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Each ghost equation must be paired on the diagonal (same column and row) with an orphan variable, and each orphan
     * variable with a ghost equation.
     *
     * @return ghost columns if valid, null otherwise
     */
    private int[] validateGhosts() {
        int size = columnCount;
        if (sortedVariablesToFind.size() != size) {
            return null;
        }
        boolean[] ghostColumn = new boolean[size];
        List<Integer> columns = new ArrayList<>();
        for (SingleEquation<V, E> equation : ghostSingleEquations) {
            int column = equation.getColumn();
            if (column < 0 || column >= size) {
                return null;
            }
            ghostColumn[column] = true;
            columns.add(column);
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getGhostCount() > 0) {
                int[] elementNumToColumn = equationArray.getElementNumToColumn();
                for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
                    if (equationArray.isElementGhost(elementNum)) {
                        int column = elementNumToColumn[elementNum];
                        ghostColumn[column] = true;
                        columns.add(column);
                    }
                }
            }
        }
        for (int column : columns) {
            if (!orphanVariables.contains(sortedVariablesToFind.get(column))) {
                return null;
            }
        }
        for (Variable<V> variable : orphanVariables) {
            int row = variable.getRow();
            if (row < 0 || row >= size || !ghostColumn[row]) {
                return null;
            }
        }
        return columns.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * Really remove ghost equations and orphan variables (listeners are notified by the index rebuild).
     */
    private void removeGhosts() {
        for (SingleEquation<V, E> equation : ghostSingleEquations) {
            equation.setColumn(-1);
            sortedSetEquationsToSolve.remove(equation);
        }
        ghostSingleEquations.clear();
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            equationArray.removeGhosts();
        }
        for (Variable<V> variable : orphanVariables) {
            variable.setRow(-1);
            sortedMapVariablesToFindRefCount.remove(variable);
        }
        orphanVariables.clear();
        ghostColumns = new int[0];
    }

    /**
     * Ghost single equations (they keep their column).
     */
    public Collection<SingleEquation<V, E>> getGhostSingleEquations() {
        update();
        return Collections.unmodifiableCollection(ghostSingleEquations);
    }

    /**
     * Columns of ghost equations (identity in the Jacobian matrix, zero in equation and target vectors).
     */
    public int[] getGhostColumns() {
        update();
        return ghostColumns;
    }

    /**
     * Ghost mode: decide, from the net effect of the changes since last update, if the structure is kept (only ghosts
     * have changed) or if the index has to be rebuilt (ghosts are then really removed).
     */
    private void updateGhosts() {
        if (!structureCheckNeeded) {
            return;
        }
        structureCheckNeeded = false;
        int[] columns = null;
        if (!forceRebuild && !hasNewEquationsOrVariables() && equationsIndexValid && variablesIndexValid) {
            columns = hasGhosts() ? validateGhosts() : new int[0];
        }
        if (columns != null) {
            ghostColumns = columns;
            listeners.forEach(EquationSystemIndexListener::onGhostChange);
        } else {
            removeGhosts();
            forceRebuild = false;
            newEquations.clear();
            newVariables.clear();
            equationsIndexValid = false;
            variablesIndexValid = false;
        }
    }

    /**
     * Make sure pending changes (ghosts) are resolved, so that listeners status are up to date.
     */
    public void validate() {
        update();
    }

    private void update() {
        updateGhosts();
        if (!equationsIndexValid) {
            updateEquationsToSolve();
        }

        if (!variablesIndexValid) {
            updateVariablesToFind();
        }
    }

    public void updateWithSeparation(Predicate<E> isEquationSeparatedInFirstPart, Predicate<V> isVariableSeparatedInFirstPart) {
        // Sort equations to solve with a column order that separates in two parts
        updateEquationsToSolve(isEquationSeparatedInFirstPart);
        // Sort variables to find with a row order that separates in two parts
        updateVariablesToFind(isVariableSeparatedInFirstPart);
    }

    private void addTerm(SingleEquationTerm<V, E> term) {
        notifyEquationTermChange(term);
        addVariables(term.getVariables());
    }

    private void addVariables(List<Variable<V>> variables) {
        for (Variable<V> variable : variables) {
            MutableInt variableRefCount = sortedMapVariablesToFindRefCount.get(variable);
            if (variableRefCount == null) {
                variableRefCount = new MutableInt(1);
                sortedMapVariablesToFindRefCount.put(variable, variableRefCount);
                if (ghostMode) {
                    newVariables.add(variable);
                    onGhostModeChange();
                } else {
                    variablesIndexValid = false;
                    notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.ADDED);
                }
            } else {
                if (variableRefCount.intValue() == 0 && orphanVariables.remove(variable)) {
                    onGhostModeChange();
                }
                variableRefCount.increment();
            }
        }
    }

    private void addEquationTerms(SingleEquation<V, E> equation) {
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                addTerm(term);
            }
        }
    }

    private void removeEquationTerms(SingleEquation<V, E> equation) {
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                removeTerm(term);
            }
        }
    }

    private void addEquation(SingleEquation<V, E> equation) {
        if (ghostMode) {
            if (!ghostSingleEquations.remove(equation)) {
                // new equation, without column
                sortedSetEquationsToSolve.add(equation);
                newEquations.add(equation);
            }
            addEquationTerms(equation);
            onGhostModeChange();
            return;
        }
        sortedSetEquationsToSolve.add(equation);
        equationsIndexValid = false;
        addEquationTerms(equation);
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.ADDED);
    }

    private void removeTerm(SingleEquationTerm<V, E> term) {
        notifyEquationTermChange(term);
        removeVariables(term.getVariables());
    }

    private void removeVariables(List<Variable<V>> variables) {
        for (Variable<V> variable : variables) {
            MutableInt variableRefCount = sortedMapVariablesToFindRefCount.get(variable);
            if (variableRefCount != null) {
                variableRefCount.decrement();
                if (variableRefCount.intValue() == 0) {
                    if (ghostMode) {
                        if (newVariables.remove(variable)) {
                            // variable added since last update
                            sortedMapVariablesToFindRefCount.remove(variable);
                        } else if (variable.getRow() != -1) {
                            // keep the row as an orphan variable (to be paired with a ghost equation)
                            orphanVariables.add(variable);
                        } else {
                            sortedMapVariablesToFindRefCount.remove(variable);
                            forceRebuild = true;
                        }
                        onGhostModeChange();
                    } else {
                        variable.setRow(-1);
                        sortedMapVariablesToFindRefCount.remove(variable);
                        variablesIndexValid = false;
                        notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.REMOVED);
                    }
                }
            }
        }
    }

    /**
     * Equation deactivation (or removal if permanent).
     */
    private void removeEquation(SingleEquation<V, E> equation, boolean permanent) {
        if (ghostMode) {
            boolean newEquation = newEquations.remove(equation);
            if (permanent) {
                // equation removed from the system (note that its column has already been reset)
                forceRebuild = true;
                equation.setColumn(-1);
                sortedSetEquationsToSolve.remove(equation);
            } else if (newEquation) {
                // equation added since last update: no structure change
                sortedSetEquationsToSolve.remove(equation);
            } else if (equation.getColumn() != -1) {
                // keep the column, as a ghost
                ghostSingleEquations.add(equation);
            } else {
                forceRebuild = true;
                sortedSetEquationsToSolve.remove(equation);
            }
            removeEquationTerms(equation);
            onGhostModeChange();
            return;
        }
        equation.setColumn(-1);
        sortedSetEquationsToSolve.remove(equation);
        equationsIndexValid = false;
        removeEquationTerms(equation);
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
    }

    @Override
    public void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_REMOVED:
                if (equation.isActive()) {
                    removeEquation(equation, true);
                } else if (ghostSingleEquations.remove(equation)) {
                    // removal of a ghost equation (its terms have already been removed from the index)
                    equation.setColumn(-1);
                    sortedSetEquationsToSolve.remove(equation);
                    forceRebuild = true;
                    onGhostModeChange();
                }
                break;

            case EQUATION_DEACTIVATED:
                removeEquation(equation, false);
                break;

            case EQUATION_CREATED:
                if (equation.isActive()) {
                    addEquation(equation);
                }
                break;

            case EQUATION_ACTIVATED:
                addEquation(equation);
                break;

            default:
                throw new IllegalStateException("Event type not supported: " + eventType);
        }
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType) {
        if (term.getEquation().isActive()) {
            switch (eventType) {
                case EQUATION_TERM_ADDED:
                    if (term.isActive()) {
                        addTerm(term);
                    }
                    break;

                case EQUATION_TERM_ACTIVATED:
                    addTerm(term);
                    break;

                case EQUATION_TERM_DEACTIVATED:
                    removeTerm(term);
                    break;

                default:
                    throw new IllegalStateException("Event type not supported: " + eventType);
            }
        }
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_DEACTIVATED:
                for (var equationTermArray : equationArray.getTermArrays()) {
                    for (int termNum : equationTermArray.getTermNumsForEquationElementNum(elementNum).toArray()) {
                        if (equationTermArray.isTermActive(termNum)) {
                            List<Variable<V>> variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
                            removeVariables(variables);
                        }
                    }
                }
                for (var singleTerm : equationArray.getSingleEquationTerms(elementNum)) {
                    if (singleTerm.isActive()) {
                        List<Variable<V>> variables = singleTerm.getVariables();
                        removeVariables(variables);
                    }
                }
                if (ghostMode) {
                    onGhostModeChange();
                } else {
                    equationsIndexValid = false;
                    notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.REMOVED);
                }
                break;

            case EQUATION_ACTIVATED:
                for (var equationTermArray : equationArray.getTermArrays()) {
                    int[] termNumsConcatenatedStartIndices = equationTermArray.getTermNumsConcatenatedStartIndices();
                    int iStart = termNumsConcatenatedStartIndices[elementNum];
                    int iEnd = termNumsConcatenatedStartIndices[elementNum + 1];
                    var termNums = equationTermArray.getTermNumsConcatenated();
                    for (int i = iStart; i < iEnd; i++) {
                        int termNum = termNums.get(i);
                        if (equationTermArray.isTermActive(termNum)) {
                            List<Variable<V>> variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
                            addVariables(variables);
                        }
                    }
                }
                for (var singleTerm : equationArray.getSingleEquationTerms(elementNum)) {
                    if (singleTerm.isActive()) {
                        List<Variable<V>> variables = singleTerm.getVariables();
                        addVariables(variables);
                    }
                }
                if (ghostMode) {
                    onGhostModeChange();
                } else {
                    equationsIndexValid = false;
                    notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.ADDED);
                }
                break;

            default:
                throw new IllegalStateException("Event type not supported: " + eventType);
        }
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationTermEventType eventType) {
        var variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
        int equationElementNum = equationTermArray.getEquationElementNum(termNum);
        if (equationTermArray.getEquationArray().isElementActive(equationElementNum)) {
            switch (eventType) {
                case EQUATION_TERM_ADDED:
                    if (equationTermArray.isTermActive(termNum)) {
                        addVariables(variables);
                    }
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.ADDED);
                    break;

                case EQUATION_TERM_ACTIVATED:
                    addVariables(variables);
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.ADDED);
                    break;

                case EQUATION_TERM_DEACTIVATED:
                    removeVariables(variables);
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.REMOVED);
                    break;

                default:
                    throw new IllegalStateException("Event type not supported: " + eventType);
            }
        }
    }

    public List<SingleEquation<V, E>> getSortedSingleEquationsToSolve() {
        update();
        return sortedSingleEquationsToSolve;
    }

    public List<EquationArray<V, E>> getSortedEquationArraysToSolve() {
        update();
        return sortedEquationArraysToSolve;
    }

    public Equation<V, E> getEquationAtColumn(int column) {
        update();
        if (sortedEquationArraysToSolve.isEmpty()) {
            return sortedSingleEquationsToSolve.get(column);
        }
        int equationsFromArrayExplored = 0;
        for (EquationArray<V, E> equationArray : sortedEquationArraysToSolve) {
            if (column < equationArray.getFirstColumn()) {
                return sortedSingleEquationsToSolve.get(column - equationsFromArrayExplored);
            }
            if (column >= equationArray.getFirstColumn()
                    && column < equationArray.getFirstColumn() + equationArray.getLength()) {
                int elementNum = equationArray.getColumnToElementNum(column);
                return equationArray.getElement(elementNum);
            }
            equationsFromArrayExplored += equationArray.getLength();
        }
        throw new PowsyblException("Equation of column " + column + " not found");
    }

    public List<Variable<V>> getSortedVariablesToFind() {
        update();
        return sortedVariablesToFind;
    }

    private List<Variable<V>> variablesByElementSource;
    private List<Variable<V>> variablesByElement;

    /**
     * Variables to find sorted by element number (and then type), instead of row order. Iterating in this order to
     * read or write the values of the elements accesses them sequentially in memory, which is much faster for large
     * systems than the row order (that is aligned with the equations).
     */
    public List<Variable<V>> getVariablesToFindSortedByElement() {
        update();
        if (variablesByElementSource != sortedVariablesToFind) {
            List<Variable<V>> variables = new ArrayList<>(sortedVariablesToFind);
            Collections.sort(variables);
            variablesByElement = Collections.unmodifiableList(variables);
            variablesByElementSource = sortedVariablesToFind;
        }
        return variablesByElement;
    }

    public int getColumnCount() {
        update();
        return columnCount;
    }

    public int getRowCount() {
        update();
        return rowCount;
    }
}
