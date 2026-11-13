/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrix;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TLongIntHashMap;

import java.util.Arrays;
import java.util.Objects;

/**
 * Sparse matrix on which the LU decomposition of a Jacobian matrix is computed.
 * <p>
 * It has two purposes:
 * <ul>
 *     <li>Row indices are sorted inside each column: sparse LU solvers (KLU block triangular form and fill-reducing
 *     ordering) give much better results with sorted row indices, whereas derivatives are not always emitted in row
 *     order.</li>
 *     <li>Its structure can be kept when the structure of the Jacobian matrix shrinks (equations and variables are
 *     removed, for instance when equations of some elements are deactivated): the values of the smaller Jacobian are
 *     mapped on the larger structure, removed equations and variables being replaced by identity (1 on the diagonal,
 *     zero right hand side). This is the same system (it is block diagonal with the identity block) but the LU
 *     symbolic analysis can be reused (only a numerical refactorization is needed) and when the Jacobian structure
 *     comes back to the initial one (equations reactivated), the mapping is the identity.</li>
 * </ul>
 * Equations and variables are identified across structure changes by their type and element number.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
final class JacobianLuStructure {

    private static final int NO_ENTRY = -1;

    private final MatrixFactory matrixFactory;

    private SparseMatrix luMatrix;

    private int luSize;

    private int luValueCount;

    private TLongIntHashMap luColumnByKey;

    private TLongIntHashMap luRowByKey;

    // mapping of the current Jacobian matrix on the LU matrix

    private int[] valueIndex; // value index in the Jacobian matrix -> value index in the LU matrix

    private int[] identityValueIndex; // value indexes of the LU matrix to set to 1 (removed equations)

    private boolean zeroFillNeeded; // true if some values of the LU matrix are not covered by the Jacobian matrix

    private int[] columnToLuColumn; // null if same numbering

    private int[] rowToLuRow; // null if same numbering

    JacobianLuStructure(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    SparseMatrix getLuMatrix() {
        return luMatrix;
    }

    private static long key(Enum<?> type, int elementNum) {
        return ((long) type.ordinal() << 32) | (elementNum & 0xFFFFFFFFL);
    }

    private record Keys(long[] columnKeys, long[] rowKeys) {
    }

    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> Keys computeKeys(EquationSystemIndex<V, E> index, int size) {
        long[] columnKeys = new long[size];
        long[] rowKeys = new long[size];
        for (SingleEquation<V, E> equation : index.getSortedSingleEquationsToSolve()) {
            columnKeys[equation.getColumn()] = key(equation.getType(), equation.getElementNum());
        }
        for (EquationArray<V, E> equationArray : index.getSortedEquationArraysToSolve()) {
            int[] elementNumToColumn = equationArray.getElementNumToColumn();
            for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
                if (equationArray.hasColumn(elementNum)) {
                    columnKeys[elementNumToColumn[elementNum]] = key(equationArray.getType(), elementNum);
                }
            }
        }
        for (Variable<V> variable : index.getSortedVariablesToFind()) {
            rowKeys[variable.getRow()] = key(variable.getType(), variable.getElementNum());
        }
        return new Keys(columnKeys, rowKeys);
    }

    /**
     * Update the LU structure after a change of the Jacobian matrix structure.
     *
     * @param allowReuse if true, try to keep the current LU structure (if the Jacobian structure is included in it)
     * @return true if the LU structure has been rebuilt (LU decomposition has to be recomputed from scratch), false
     * if the current structure has been kept (only a numerical refactorization is needed)
     */
    <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> boolean update(SparseMatrix matrix, EquationSystemIndex<V, E> index,
                                                                                    boolean allowReuse) {
        Keys keys = computeKeys(index, matrix.getColumnCount());
        if (allowReuse && luMatrix != null && map(matrix, keys)) {
            copyValues(matrix);
            return false;
        }
        build(matrix, keys);
        if (!map(matrix, keys)) {
            throw new IllegalStateException("Jacobian matrix cannot be mapped on its own LU structure");
        }
        copyValues(matrix);
        return true;
    }

    private void build(SparseMatrix matrix, Keys keys) {
        int size = matrix.getColumnCount();
        int[] columnStart = matrix.getColumnStart();
        int[] rowIndices = matrix.getRowIndices();
        int valueCount = columnStart[size];
        Matrix newLuMatrix = matrixFactory.create(matrix.getRowCount(), size, valueCount);
        // fill in column order with row indices sorted inside each column
        long[] sortKeys = new long[0];
        for (int column = 0; column < size; column++) {
            int start = columnStart[column];
            int length = columnStart[column + 1] - start;
            if (sortKeys.length < length) {
                sortKeys = new long[length];
            }
            for (int k = 0; k < length; k++) {
                sortKeys[k] = ((long) rowIndices[start + k] << 32) | k;
            }
            Arrays.sort(sortKeys, 0, length);
            for (int k = 0; k < length; k++) {
                newLuMatrix.addAndGetIndex((int) (sortKeys[k] >>> 32), column, 0);
            }
        }
        luMatrix = (SparseMatrix) newLuMatrix;
        luSize = size;
        luValueCount = valueCount;
        luColumnByKey = new TLongIntHashMap(size * 2, 0.5f, Long.MIN_VALUE, NO_ENTRY);
        luRowByKey = new TLongIntHashMap(size * 2, 0.5f, Long.MIN_VALUE, NO_ENTRY);
        for (int i = 0; i < size; i++) {
            luColumnByKey.put(keys.columnKeys()[i], i);
            luRowByKey.put(keys.rowKeys()[i], i);
        }
    }

    private int findLuValueIndex(int luColumn, int luRow) {
        int[] luColumnStart = luMatrix.getColumnStart();
        int i = Arrays.binarySearch(luMatrix.getRowIndices(), luColumnStart[luColumn], luColumnStart[luColumn + 1], luRow);
        return i >= 0 ? i : NO_ENTRY;
    }

    /**
     * Try to map the Jacobian matrix on the LU matrix structure.
     */
    private boolean map(SparseMatrix matrix, Keys keys) {
        int size = matrix.getColumnCount();
        if (size > luSize) {
            return false;
        }
        int[] newColumnToLuColumn = new int[size];
        int[] newRowToLuRow = new int[size];
        boolean[] luColumnUsed = new boolean[luSize];
        boolean[] luRowUsed = new boolean[luSize];
        boolean sameNumbering = size == luSize;
        for (int i = 0; i < size; i++) {
            int luColumn = luColumnByKey.get(keys.columnKeys()[i]);
            int luRow = luRowByKey.get(keys.rowKeys()[i]);
            if (luColumn == NO_ENTRY || luRow == NO_ENTRY) {
                return false;
            }
            newColumnToLuColumn[i] = luColumn;
            newRowToLuRow[i] = luRow;
            luColumnUsed[luColumn] = true;
            luRowUsed[luRow] = true;
            sameNumbering &= luColumn == i && luRow == i;
        }

        // removed equations and variables are replaced by identity, which requires they are paired on the diagonal
        TIntArrayList newIdentityValueIndex = new TIntArrayList();
        for (int i = 0; i < luSize; i++) {
            if (luColumnUsed[i] != luRowUsed[i]) {
                return false;
            }
            if (!luColumnUsed[i]) {
                int diagonalValueIndex = findLuValueIndex(i, i);
                if (diagonalValueIndex == NO_ENTRY) {
                    return false;
                }
                newIdentityValueIndex.add(diagonalValueIndex);
            }
        }

        int[] columnStart = matrix.getColumnStart();
        int[] rowIndices = matrix.getRowIndices();
        int valueCount = columnStart[size];
        int[] newValueIndex = new int[valueCount];
        for (int column = 0; column < size; column++) {
            int luColumn = newColumnToLuColumn[column];
            for (int i = columnStart[column]; i < columnStart[column + 1]; i++) {
                int index = findLuValueIndex(luColumn, newRowToLuRow[rowIndices[i]]);
                if (index == NO_ENTRY) {
                    return false;
                }
                newValueIndex[i] = index;
            }
        }

        valueIndex = newValueIndex;
        identityValueIndex = newIdentityValueIndex.toArray();
        zeroFillNeeded = valueCount + identityValueIndex.length != luValueCount;
        columnToLuColumn = sameNumbering ? null : newColumnToLuColumn;
        rowToLuRow = sameNumbering ? null : newRowToLuRow;
        return true;
    }

    /**
     * Copy Jacobian matrix values to the LU matrix.
     */
    void copyValues(SparseMatrix matrix) {
        double[] luValues = luMatrix.getValues();
        if (zeroFillNeeded) {
            Arrays.fill(luValues, 0, luValueCount, 0);
        }
        double[] values = matrix.getValues();
        for (int i = 0; i < valueIndex.length; i++) {
            luValues[valueIndex[i]] = values[i];
        }
        for (int i : identityValueIndex) {
            luValues[i] = 1;
        }
    }

    boolean isSameNumbering() {
        return columnToLuColumn == null;
    }

    /**
     * Map a vector indexed by Jacobian columns (if byColumn) or rows to a vector indexed by LU matrix columns or rows.
     */
    double[] toLu(double[] b, boolean byColumn) {
        int[] mapping = byColumn ? columnToLuColumn : rowToLuRow;
        double[] luB = new double[luSize];
        for (int i = 0; i < mapping.length; i++) {
            luB[mapping[i]] = b[i];
        }
        return luB;
    }

    void fromLu(double[] luB, double[] b, boolean byColumn) {
        int[] mapping = byColumn ? columnToLuColumn : rowToLuRow;
        for (int i = 0; i < mapping.length; i++) {
            b[i] = luB[mapping[i]];
        }
    }

    DenseMatrix toLu(DenseMatrix b, boolean byColumn) {
        int[] mapping = byColumn ? columnToLuColumn : rowToLuRow;
        DenseMatrix luB = new DenseMatrix(luSize, b.getColumnCount());
        for (int j = 0; j < b.getColumnCount(); j++) {
            for (int i = 0; i < mapping.length; i++) {
                luB.set(mapping[i], j, b.get(i, j));
            }
        }
        return luB;
    }

    void fromLu(DenseMatrix luB, DenseMatrix b, boolean byColumn) {
        int[] mapping = byColumn ? columnToLuColumn : rowToLuRow;
        for (int j = 0; j < b.getColumnCount(); j++) {
            for (int i = 0; i < mapping.length; i++) {
                b.set(i, j, luB.get(mapping[i], j));
            }
        }
    }
}
