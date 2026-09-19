/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcVectorizedEquationSystemCreator extends AcEquationSystemCreator {

    protected AcNetworkVector networkVector;

    private EquationTermArray<AcVariableType, AcEquationType> closedP1Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedP2Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedQ1Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedQ2Array;

    private EquationTermArray<AcVariableType, AcEquationType> shuntPArray;

    private EquationTermArray<AcVariableType, AcEquationType> shuntQArray;

    private EquationArray<AcVariableType, AcEquationType> qArray;

    /**
     * Pair the voltage target equation of a bus with the reactive power target equation of the same bus: they are
     * complementary (a PV/PQ switch activates one and deactivates the other), so they can share a column and the
     * switch does not change the Jacobian structure anymore.
     */
    private static final boolean COMPLEMENTARY_VQ = Boolean.parseBoolean(System.getProperty("olf.complementaryVq", "true"));

    private final boolean complementaryEquations;

    public AcVectorizedEquationSystemCreator(LfNetwork network) {
        this(network, new AcEquationSystemCreationParameters());
    }

    public AcVectorizedEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        this(network, creationParameters, true);
    }

    public AcVectorizedEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters,
                                             boolean complementaryEquations) {
        super(network, creationParameters);
        this.complementaryEquations = complementaryEquations;
    }

    @Override
    protected void onBusEquationsCreated(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        if (COMPLEMENTARY_VQ && complementaryEquations && isVoltageTargetComplementaryWithReactiveTarget(bus)
                && equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_V).orElseThrow()
                        instanceof SingleEquation<AcVariableType, AcEquationType> vEq) {
            qArray.setComplementaryEquation(bus.getNum(), vEq);
        }
    }

    /**
     * The voltage target equation of a bus and its reactive power target equation are strictly complementary (exactly
     * one of the two is active at a time) only when the bus is controlled in voltage by its own generators alone: a
     * PV/PQ switch then activates one and deactivates the other. With a remote control the voltage target equation is
     * at the controlled bus while the reactive power target equations are at the controller buses, so both equations
     * of a same bus can be active at the same time and they cannot share a column.
     */
    private static boolean isVoltageTargetComplementaryWithReactiveTarget(LfBus bus) {
        if (bus.hasGeneratorsWithSlope() || bus.hasGeneratorReactivePowerControl()) {
            // extra terms in the voltage target equation, or another control driving the reactive power target equation
            return false;
        }
        List<VoltageControl<?>> voltageControls = bus.getVoltageControls();
        if (voltageControls.size() != 1) {
            return false;
        }
        VoltageControl<?> voltageControl = voltageControls.get(0);
        return voltageControl.getType() == VoltageControl.Type.GENERATOR
                && voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN
                && voltageControl.getControlledBus() == bus
                && voltageControl.getMergedControlledBuses().size() == 1
                && voltageControl.getMergedControllerElements().equals(List.of(bus));
    }

    @Override
    protected void create(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector = new AcNetworkVector(network, equationSystem, creationParameters);

        EquationArray<AcVariableType, AcEquationType> pArray = equationSystem.createEquationArray(AcEquationType.BUS_TARGET_P);
        qArray = equationSystem.createEquationArray(AcEquationType.BUS_TARGET_Q);

        closedP1Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide1ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP1Array);
        closedP2Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide2ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP2Array);
        closedQ1Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide1ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(closedQ1Array);
        closedQ2Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide2ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(closedQ2Array);

        shuntPArray = new EquationTermArray<>(ElementType.SHUNT_COMPENSATOR,
            new ShuntCompensatorActiveFlowEquationTermArrayEvaluator(networkVector.getShuntVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(shuntPArray);
        shuntQArray = new EquationTermArray<>(ElementType.SHUNT_COMPENSATOR,
            new ShuntCompensatorReactiveFlowEquationTermArrayEvaluator(networkVector.getShuntVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(shuntQArray);

        networkVector.startListening();

        super.create(equationSystem);

        closedP1Array.compress();
        closedP2Array.compress();
        closedQ1Array.compress();
        closedQ2Array.compress();
        shuntPArray.compress();
        shuntQArray.compress();
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createShuntCompensatorActiveFlowEquationTerm(LfShunt shunt, LfBus bus,
                                                                                                        EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector.getShuntVector().setBusNum(shunt.getNum(), bus);
        return shuntPArray.getElement(shunt.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createShuntCompensatorReactiveFlowEquationTerm(LfShunt shunt, LfBus bus, boolean deriveB,
                                                                                                          EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        var shuntVector = networkVector.getShuntVector();
        shuntVector.setBusNum(shunt.getNum(), bus);
        shuntVector.setDeriveB(shunt.getNum(), deriveB);
        return shuntQArray.getElement(shunt.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                         boolean deriveA1, boolean deriveR1,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedP1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                           boolean deriveA1, boolean deriveR1,
                                                                                                           EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedQ1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                         boolean deriveA1, boolean deriveR1,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedP2Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                           boolean deriveA1, boolean deriveR1,
                                                                                                           EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedQ2Array.getElement(branch.getNum());
    }
}
