/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.AcEquationTermFactory;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;

/**
 * AC equation terms factory for the cartesian (e, f) voltage representation.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class CartesianAcEquationTermFactory implements AcEquationTermFactory {

    public static final CartesianAcEquationTermFactory INSTANCE = new CartesianAcEquationTermFactory();

    private CartesianAcEquationTermFactory() {
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createBusVoltageMagnitudeTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        return new BusVoltageMagnitudeCartesianEquationTerm(bus, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createBusVoltageAngleTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        return new BusVoltageAngleCartesianEquationTerm(bus, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                             boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.ACTIVE_POWER);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                               boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.REACTIVE_POWER);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                         boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                             boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.ACTIVE_POWER);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                               boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.REACTIVE_POWER);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                         boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchCartesianEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1, TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchCartesianEquationTerm(branch, bus2, variableSet, TwoSides.ONE, OpenBranchCartesianEquationTerm.Type.ACTIVE_POWER);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchCartesianEquationTerm(branch, bus2, variableSet, TwoSides.ONE, OpenBranchCartesianEquationTerm.Type.REACTIVE_POWER);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchCartesianEquationTerm(branch, bus2, variableSet, TwoSides.ONE, OpenBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchCartesianEquationTerm(branch, bus1, variableSet, TwoSides.TWO, OpenBranchCartesianEquationTerm.Type.ACTIVE_POWER);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchCartesianEquationTerm(branch, bus1, variableSet, TwoSides.TWO, OpenBranchCartesianEquationTerm.Type.REACTIVE_POWER);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet,
                                                                                                       boolean deriveR1) {
        // as for the polar formulation, derivative with respect to r1 is not supported
        return new OpenBranchCartesianEquationTerm(branch, bus1, variableSet, TwoSides.TWO, OpenBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createShuntActiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet) {
        return new ShuntCompensatorActiveFlowCartesianEquationTerm(shunt, bus, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createShuntReactiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, boolean deriveB) {
        return new ShuntCompensatorReactiveFlowCartesianEquationTerm(shunt, bus, variableSet, deriveB);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createLoadModelActiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet) {
        return new LoadModelCartesianEquationTerm(bus, load, loadModel, variableSet, false);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createLoadModelReactiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet) {
        return new LoadModelCartesianEquationTerm(bus, load, loadModel, variableSet, true);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide1ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new HvdcAcEmulationCartesianEquationTerm(hvdc, bus1, bus2, variableSet, TwoSides.ONE);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide2ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new HvdcAcEmulationCartesianEquationTerm(hvdc, bus1, bus2, variableSet, TwoSides.TWO);
    }
}
