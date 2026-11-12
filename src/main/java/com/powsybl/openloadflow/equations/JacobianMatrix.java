/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.powsybl.openloadflow.util.Markers.PERFORMANCE_MARKER;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class JacobianMatrix<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemIndexListener<V, E>, StateVectorListener, AutoCloseable {

    protected static final Logger LOGGER = LoggerFactory.getLogger(JacobianMatrix.class);

    protected final EquationSystem<V, E> equationSystem;

    protected final MatrixFactory matrixFactory;

    protected Matrix matrix;

    private LUDecomposition lu;

    /**
     * Sparse matrix (sorted, possibly with a larger structure) on which the LU decomposition is computed, see
     * {@link JacobianLuStructure}. Null if not supported (dense matrices, fast decoupled).
     */
    private JacobianLuStructure luStructure;

    /**
     * True if the LU decomposition has to be numerically refactorized before being used (LU structure kept after a
     * Jacobian structure change).
     */
    private boolean luRefactorizationNeeded = false;

    protected enum Status {
        VALID,
        VALUES_INVALID, // same structure but values have to be updated
        VALUES_AND_ZEROS_INVALID, // same structure but values have to be updated and non zero values might have changed
        STRUCTURE_INVALID, // structure has changed
    }

    private Status status = Status.STRUCTURE_INVALID;

    public JacobianMatrix(EquationSystem<V, E> equationSystem, MatrixFactory matrixFactory) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        equationSystem.getIndex().addListener(this);
        equationSystem.getStateVector().addListener(this);
    }

    public MatrixFactory getMatrixFactory() {
        return matrixFactory;
    }

    protected void updateStatus(Status status) {
        if (status.ordinal() > this.status.ordinal()) {
            this.status = status;
        }
    }

    @Override
    public void onEquationChange(SingleEquation<V, E> equation, ChangeType changeType) {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onVariableChange(Variable<V> variable, ChangeType changeType) {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term) {
        updateStatus(Status.VALUES_AND_ZEROS_INVALID);
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, ChangeType changeType) {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, ChangeType changeType) {
        updateStatus(Status.VALUES_AND_ZEROS_INVALID);
    }

    @Override
    public void onEquationIndexOrderChanged() {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onStateUpdate() {
        updateStatus(Status.VALUES_INVALID);
    }

    protected void initDer() {
        Stopwatch stopwatch = Stopwatch.createStarted();
        int rowCount = equationSystem.getIndex().getRowCount();
        int columnCount = equationSystem.getIndex().getColumnCount();
        if (rowCount != columnCount) {
            throw new PowsyblException("Expected to have same number of equations (" + columnCount
                    + ") and variables (" + rowCount + ")");
        }

        int estimatedNonZeroValueCount = rowCount * 3;
        matrix = matrixFactory.create(rowCount, columnCount, estimatedNonZeroValueCount);

        // When initializing the matrix, it must be filled in the column order (in case of SparseMatrix)
        //
        // SingleEquations are sorted by their column number
        // EquationArrays are sorted by their first column number (and all following column numbers are contiguous for a defined length)
        //
        // Example of EquationSystemIndex organization (e.g. in Fast Decoupled) with each index (corresponding to unique column number) :
        //   0    |   1    | ... | 12 | 13 | ... | 31 | 32 | 33 | ... | 51 |   52   |   53   | ... | 64 | ... | 83 | 84 | ... | 101 |  <-- index
        // Single | Single | ... |        Array       |        Array       | Single | Single | ... |     Array     |      Array     |

        Iterator<SingleEquation<V, E>> itSortedSingleEquation = equationSystem.getIndex().getSortedSingleEquationsToSolve().iterator();
        Iterator<EquationArray<V, E>> itSortedEquationArray = equationSystem.getIndex().getSortedEquationArraysToSolve().iterator();

        SingleEquation<V, E> eq = itSortedSingleEquation.hasNext() ? itSortedSingleEquation.next() : null;
        EquationArray<V, E> eqArray = itSortedEquationArray.hasNext() ? itSortedEquationArray.next() : null;
        int index = 0; // index is either the column number of SingleEquation, either the first column of EquationArray
        while (eq != null || eqArray != null) {
            while (eq != null && index == eq.getColumn()) { // Compute derivatives of all SingleEquations until next EquationArray
                final int column = index;
                eq.der((variable, value, matrixElementIndex) -> {
                    int row = variable.getRow();
                    return matrix.addAndGetIndex(row, column, value);
                });
                index++;
                eq = itSortedSingleEquation.hasNext() ? itSortedSingleEquation.next() : null;
            }
            while (eqArray != null && index == eqArray.getFirstColumn()) { // Compute derivatives of next EquationArrays
                eqArray.der((column, row, value, matrixElementIndex) ->
                        matrix.addAndGetIndex(row, column, value));
                index += eqArray.getLength();
                eqArray = itSortedEquationArray.hasNext() ? itSortedEquationArray.next() : null;
            }
        }

        LOGGER.debug(PERFORMANCE_MARKER, "Jacobian matrix built in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    private void clearLu() {
        if (lu != null) {
            lu.close();
        }
        lu = null;
    }

    /**
     * If true the LU decomposition is computed on a sorted copy of the sparse matrix.
     */
    protected boolean isLuStructureSupported() {
        return true;
    }

    /**
     * If true, the LU structure (and so the LU symbolic analysis) is kept when the Jacobian structure shrinks.
     */
    protected boolean isLuStructureReuseAllowed() {
        return equationSystem.getDiagonalVariableTypeProvider() != null;
    }

    private void initMatrix() {
        initDer();
        if (matrix instanceof SparseMatrix sparseMatrix && isLuStructureSupported()) {
            if (luStructure == null) {
                luStructure = new JacobianLuStructure(matrixFactory);
            }
            boolean rebuilt = luStructure.update(sparseMatrix, equationSystem.getIndex(), isLuStructureReuseAllowed() && lu != null);
            if (rebuilt) {
                clearLu();
            } else {
                luRefactorizationNeeded = true;
            }
        } else {
            luStructure = null;
            clearLu();
        }
    }

    protected void updateDer() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        matrix.reset();
        for (SingleEquation<V, E> eq : equationSystem.getIndex().getSortedSingleEquationsToSolve()) {
            eq.der((variable, value, matrixElementIndex) -> {
                matrix.addAtIndex(matrixElementIndex, value);
                return matrixElementIndex; // don't change element index
            });
        }
        double[] sparseValues = matrix instanceof SparseMatrix sparseMatrix ? sparseMatrix.getValues() : null;
        for (var eq : equationSystem.getIndex().getSortedEquationArraysToSolve()) {
            if (sparseValues != null && eq.isFastDerReady()) {
                // same structure as last full derivative computation: directly add values
                eq.derValues(sparseValues);
            } else {
                eq.der((column, row, value, matrixElementIndex) -> {
                    matrix.addAtIndex(matrixElementIndex, value);
                    return matrixElementIndex; // don't change element index
                });
            }
        }

        LOGGER.debug(PERFORMANCE_MARKER, "Jacobian matrix values updated in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    private void updateLu(boolean allowIncrementalUpdate) {
        if (lu != null) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            lu.update(allowIncrementalUpdate);

            LOGGER.debug(PERFORMANCE_MARKER, "LU decomposition updated in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
        }
    }

    private void updateValues(boolean allowIncrementalUpdate) {
        updateDer();
        if (luStructure != null) {
            luStructure.copyValues((SparseMatrix) matrix);
        }
        luRefactorizationNeeded = false;
        try {
            updateLu(allowIncrementalUpdate);
        } catch (MatrixException ex) {
            if (allowIncrementalUpdate) {
                // Try another time without incremental
                LOGGER.warn("Exception when updating LU matrix in incremental mode. Retrying without incremental mode");
                updateLu(false);
            } else {
                // Rethrow the exception
                throw ex;
            }
        }
    }

    public void forceUpdate() {
        update();
    }

    private void update() {
        if (status != Status.VALID) {
            switch (status) {
                case STRUCTURE_INVALID:
                    initMatrix();
                    break;

                case VALUES_INVALID:
                    updateValues(true);
                    break;

                case VALUES_AND_ZEROS_INVALID:
                    // the sparsity pattern is kept (deactivated terms leave explicit zeros), so we can try an incremental
                    // LU update (refactorization reusing the previous pivots); a full factorization is done if the pivot
                    // growth check fails or if the refactorization fails
                    updateValues(true);
                    break;

                default:
                    break;
            }
            status = Status.VALID;
        }
    }

    public Matrix getMatrix() {
        update();
        return matrix;
    }

    private LUDecomposition getLUDecomposition() {
        Matrix m = getMatrix();
        if (lu == null) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            lu = luStructure != null ? luStructure.getLuMatrix().decomposeLU() : m.decomposeLU();
            luRefactorizationNeeded = false;

            LOGGER.debug(PERFORMANCE_MARKER, "LU decomposition done in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
        } else if (luRefactorizationNeeded) {
            luRefactorizationNeeded = false;
            refactorLu();
        }
        return lu;
    }

    private void refactorLu() {
        try {
            updateLu(true);
        } catch (MatrixException ex) {
            LOGGER.warn("Exception when updating LU matrix in incremental mode. Retrying without incremental mode");
            updateLu(false);
        }
    }

    private boolean isLuSameNumbering() {
        return luStructure == null || luStructure.isSameNumbering();
    }

    // Jacobian matrix rows are variables and columns are equations

    public void solve(double[] b) {
        LUDecomposition luDecomposition = getLUDecomposition();
        if (isLuSameNumbering()) {
            luDecomposition.solve(b);
        } else {
            double[] luB = luStructure.toLu(b, false);
            luDecomposition.solve(luB);
            luStructure.fromLu(luB, b, true);
        }
    }

    public void solveTransposed(double[] b) {
        LUDecomposition luDecomposition = getLUDecomposition();
        if (isLuSameNumbering()) {
            luDecomposition.solveTransposed(b);
        } else {
            double[] luB = luStructure.toLu(b, true);
            luDecomposition.solveTransposed(luB);
            luStructure.fromLu(luB, b, false);
        }
    }

    public void solve(DenseMatrix b) {
        LUDecomposition luDecomposition = getLUDecomposition();
        if (isLuSameNumbering()) {
            luDecomposition.solve(b);
        } else {
            DenseMatrix luB = luStructure.toLu(b, false);
            luDecomposition.solve(luB);
            luStructure.fromLu(luB, b, true);
        }
    }

    public void solveTransposed(DenseMatrix b) {
        LUDecomposition luDecomposition = getLUDecomposition();
        if (isLuSameNumbering()) {
            luDecomposition.solveTransposed(b);
        } else {
            DenseMatrix luB = luStructure.toLu(b, true);
            luDecomposition.solveTransposed(luB);
            luStructure.fromLu(luB, b, false);
        }
    }

    @Override
    public void close() {
        equationSystem.getIndex().removeListener(this);
        equationSystem.getStateVector().removeListener(this);
        matrix = null;
        luStructure = null;
        clearLu();
    }
}
