/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;

/**
 * AC equation terms factory for the polar (v, phi) voltage representation.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class PolarAcEquationTermFactory implements AcEquationTermFactory {

    public static final PolarAcEquationTermFactory INSTANCE = new PolarAcEquationTermFactory();

    private PolarAcEquationTermFactory() {
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createBusVoltageMagnitudeTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        return variableSet.getVariable(bus.getNum(), AcVariableType.BUS_V).createTerm();
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createBusVoltageAngleTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        return variableSet.getVariable(bus.getNum(), AcVariableType.BUS_PHI).createTerm();
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                             boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                               boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                         boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                             boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                               boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                         boolean deriveA1, boolean deriveR1) {
        return new ClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchSide1ActiveFlowEquationTerm(branch, bus2, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, variableSet);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchSide1CurrentMagnitudeEquationTerm(branch, bus2, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchSide2ActiveFlowEquationTerm(branch, bus1, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet) {
        return new OpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, variableSet);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet,
                                                                                                       boolean deriveR1) {
        return new OpenBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, variableSet, deriveR1);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createShuntActiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet) {
        return new ShuntCompensatorActiveFlowEquationTerm(shunt, bus, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createShuntReactiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, boolean deriveB) {
        return new ShuntCompensatorReactiveFlowEquationTerm(shunt, bus, variableSet, deriveB);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createLoadModelActiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet) {
        return new LoadModelActiveFlowEquationTerm(bus, load, loadModel, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createLoadModelReactiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet) {
        return new LoadModelReactiveFlowEquationTerm(bus, load, loadModel, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide1ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new HvdcAcEmulationSide1ActiveFlowEquationTerm(hvdc, bus1, bus2, variableSet);
    }

    @Override
    public EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide2ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        return new HvdcAcEmulationSide2ActiveFlowEquationTerm(hvdc, bus1, bus2, variableSet);
    }
}
