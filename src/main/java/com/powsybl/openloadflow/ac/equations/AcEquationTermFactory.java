/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.ac.equations.cartesian.CartesianAcEquationTermFactory;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;

/**
 * Factory of the AC equation terms that depend on the bus voltage representation (polar or cartesian).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface AcEquationTermFactory {

    static AcEquationTermFactory of(AcVoltageRepresentation voltageRepresentation) {
        return switch (voltageRepresentation) {
            case POLAR -> PolarAcEquationTermFactory.INSTANCE;
            case CARTESIAN -> CartesianAcEquationTermFactory.INSTANCE;
        };
    }

    EquationTerm<AcVariableType, AcEquationType> createBusVoltageMagnitudeTerm(LfBus bus, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createBusVoltageAngleTerm(LfBus bus, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                      boolean deriveA1, boolean deriveR1);

    EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                        boolean deriveA1, boolean deriveR1);

    SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                  boolean deriveA1, boolean deriveR1);

    EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                      boolean deriveA1, boolean deriveR1);

    EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                        boolean deriveA1, boolean deriveR1);

    SingleEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                                                  boolean deriveA1, boolean deriveR1);

    /**
     * Active flow at side 2 of a branch open at side 1.
     */
    EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet);

    /**
     * Reactive flow at side 2 of a branch open at side 1.
     */
    EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet);

    /**
     * Current magnitude at side 2 of a branch open at side 1.
     */
    SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1CurrentMagnitudeTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet);

    /**
     * Active flow at side 1 of a branch open at side 2.
     */
    EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet);

    /**
     * Reactive flow at side 1 of a branch open at side 2.
     */
    EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet);

    /**
     * Current magnitude at side 1 of a branch open at side 2.
     */
    SingleEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2CurrentMagnitudeTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet,
                                                                                                boolean deriveR1);

    EquationTerm<AcVariableType, AcEquationType> createShuntActiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createShuntReactiveFlowTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, boolean deriveB);

    EquationTerm<AcVariableType, AcEquationType> createLoadModelActiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createLoadModelReactiveFlowTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide1ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet);

    EquationTerm<AcVariableType, AcEquationType> createHvdcAcEmulationSide2ActiveFlowTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet);
}
