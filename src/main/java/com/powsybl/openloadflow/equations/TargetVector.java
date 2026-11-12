/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.openloadflow.network.*;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class TargetVector<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractVector<V, E> implements AutoCloseable {

    public interface Initializer<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        void initialize(SingleEquation<V, E> equation, LfNetwork network, double[] targets);

        void initialize(EquationArray<V, E> equationArray, LfNetwork network, double[] targets);

        /**
         * Initialize the target of one element of an equation array.
         *
         * @return false if not supported (a full update is then done)
         */
        default boolean initialize(EquationArray<V, E> equationArray, int elementNum, LfNetwork network, double[] targets) {
            return false;
        }

        /**
         * @return true if the targets of the equations of the bus only depend on the bus itself, so that a change on
         * this bus can be handled by updating only its equations targets.
         */
        default boolean isBusLocal(LfBus bus) {
            return false;
        }
    }

    // partial update: buses whose equations targets have to be updated, and ghost columns change
    private final Set<Integer> dirtyBuses = new HashSet<>();
    private boolean ghostColumnsChanged = false;
    private int[] previousGhostColumns = new int[0];

    private void invalidateBus(LfBus bus) {
        if (bus != null && isValid() && initializer.isBusLocal(bus)) {
            dirtyBuses.add(bus.getNum());
        } else {
            invalidateValues();
        }
    }

    @Override
    protected void onGhostColumnsChange() {
        if (isValid()) {
            ghostColumnsChanged = true;
        } else {
            invalidateValues();
        }
    }

    private final LfNetwork network;

    private final Initializer<V, E> initializer;

    private final LfNetworkListener networkListener = new AbstractLfNetworkListener() {

        @Override
        public void onGeneratorVoltageControlTargetChange(GeneratorVoltageControl control, double newTargetVoltage) {
            invalidateValues();
        }

        @Override
        public void onTransformerVoltageControlTargetChange(TransformerVoltageControl transformerVoltageControl, double newTargetVoltage) {
            invalidateValues();
        }

        @Override
        public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
            invalidateBus(load.getBus());
        }

        @Override
        public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
            invalidateBus(load.getBus());
        }

        @Override
        public void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP) {
            invalidateBus(generator.getBus());
        }

        @Override
        public void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ) {
            invalidateValues();
        }

        @Override
        public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
            invalidateValues();
        }

        @Override
        public void onShuntSusceptanceChange(LfShunt shunt, double b) {
            invalidateValues();
        }

        @Override
        public void onDisableChange(LfElement element, boolean disabled) {
            for (var equationTerm : equationSystem.getEquationTerms(element.getType(), element.getNum())) {
                if (equationTerm.hasRhs()) {
                    invalidateValues();
                }
            }
        }

        @Override
        public void onHvdcAcEmulationStatusChange(LfHvdc hvdc, LfHvdc.AcEmulationControl.AcEmulationStatus acEmulationStatus) {
            invalidateValues();
        }
    };

    public TargetVector(LfNetwork network, EquationSystem<V, E> equationSystem, Initializer<V, E> initializer) {
        super(equationSystem);
        this.network = Objects.requireNonNull(network);
        this.initializer = Objects.requireNonNull(initializer);
        network.addListener(networkListener);
    }

    public static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double[] createArray(LfNetwork network, EquationSystem<V, E> equationSystem, Initializer<V, E> initializer) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(equationSystem);
        Objects.requireNonNull(initializer);
        List<SingleEquation<V, E>> sortedEquationsToSolve = equationSystem.getIndex().getSortedSingleEquationsToSolve();
        double[] array = new double[equationSystem.getIndex().getColumnCount()];
        for (SingleEquation<V, E> equation : sortedEquationsToSolve) {
            if (equation.isActive()) { // inactive equations to solve are ghosts: zero target
                initializer.initialize(equation, network, array);
            }
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            initializer.initialize(equationArray, network, array);
        }
        zeroGhostColumns(equationSystem, array);
        return array;
    }

    @Override
    protected double[] createArray() {
        clearPartialUpdate();
        return createArray(network, equationSystem, initializer);
    }

    private void clearPartialUpdate() {
        dirtyBuses.clear();
        ghostColumnsChanged = false;
        previousGhostColumns = equationSystem.getIndex().getGhostColumns();
    }

    @Override
    public double[] getArray() {
        double[] array = super.getArray();
        if (!dirtyBuses.isEmpty() || ghostColumnsChanged) {
            if (!updatePartially(array)) {
                updateArray(array);
            }
            clearPartialUpdate();
        }
        return array;
    }

    private boolean updateBusTargets(int busNum, double[] array) {
        for (SingleEquation<V, E> equation : equationSystem.getEquations(ElementType.BUS, busNum)) {
            if (equation.isActive() && equation.getColumn() != -1) {
                initializer.initialize(equation, network, array);
            }
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getType().getElementType() == ElementType.BUS && equationArray.isElementActive(busNum)
                    && !initializer.initialize(equationArray, busNum, network, array)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Update only targets of modified buses and of columns which became ghost or are not ghost anymore.
     *
     * @return false if not possible (a full update is needed)
     */
    private boolean updatePartially(double[] array) {
        for (int busNum : dirtyBuses) {
            if (!updateBusTargets(busNum, array)) {
                return false;
            }
        }
        if (ghostColumnsChanged) {
            int[] ghostColumns = equationSystem.getIndex().getGhostColumns();
            Set<Integer> toggledColumns = new HashSet<>();
            for (int column : previousGhostColumns) {
                toggledColumns.add(column);
            }
            for (int column : ghostColumns) {
                if (!toggledColumns.remove(column)) {
                    toggledColumns.add(column);
                }
            }
            for (int column : toggledColumns) {
                if (column >= array.length) {
                    return false;
                }
                Equation<V, E> equation = equationSystem.getIndex().getEquationAtColumn(column);
                if (equation.getType().getElementType() != ElementType.BUS || !updateBusTargets(equation.getElementNum(), array)) {
                    return false;
                }
            }
        }
        zeroGhostColumns(equationSystem, array);
        return true;
    }

    @Override
    protected void updateArray(double[] array) {
        List<SingleEquation<V, E>> sortedEquationsToSolve = equationSystem.getIndex().getSortedSingleEquationsToSolve();
        for (SingleEquation<V, E> equation : sortedEquationsToSolve) {
            if (equation.isActive()) { // inactive equations to solve are ghosts: zero target
                initializer.initialize(equation, network, array);
            }
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            initializer.initialize(equationArray, network, array);
        }
        zeroGhostColumns(equationSystem, array);
        clearPartialUpdate();
    }

    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> void zeroGhostColumns(EquationSystem<V, E> equationSystem, double[] array) {
        for (int column : equationSystem.getIndex().getGhostColumns()) {
            array[column] = 0;
        }
    }

    @Override
    public void close() {
        network.removeListener(networkListener);
    }
}
