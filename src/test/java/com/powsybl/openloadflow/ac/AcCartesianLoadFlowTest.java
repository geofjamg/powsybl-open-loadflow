/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.PowsyblException;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.equations.AcVoltageRepresentation;
import com.powsybl.openloadflow.ac.solver.FastDecoupledFactory;
import com.powsybl.openloadflow.network.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Check that the load flow with cartesian voltage variables gives the same results as the load flow with polar
 * voltage variables.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class AcCartesianLoadFlowTest {

    private static final double V_EPSILON = 1e-5;
    private static final double ANGLE_EPSILON = 1e-4; // degrees
    private static final double POWER_EPSILON = 1e-2; // MW or MVar

    private final LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));

    private LoadFlowResult run(Network network, AcVoltageRepresentation voltageRepresentation, Consumer<LoadFlowParameters> parametersConfigurer) {
        LoadFlowParameters parameters = new LoadFlowParameters();
        // tight convergence criteria so that results only differ by numerical noise
        OpenLoadFlowParameters.create(parameters)
                .setAcVoltageRepresentation(voltageRepresentation)
                .setNewtonRaphsonConvEpsPerEq(1e-10)
                .setSlackBusPMaxMismatch(1e-4);
        parametersConfigurer.accept(parameters);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(), voltageRepresentation + " load flow has not converged");
        return result;
    }

    private void compare(Supplier<Network> networkSupplier, Consumer<LoadFlowParameters> parametersConfigurer) {
        Network polarNetwork = networkSupplier.get();
        LoadFlowResult polarResult = run(polarNetwork, AcVoltageRepresentation.POLAR, parametersConfigurer);
        Network cartesianNetwork = networkSupplier.get();
        LoadFlowResult cartesianResult = run(cartesianNetwork, AcVoltageRepresentation.CARTESIAN, parametersConfigurer);

        assertEquals(polarResult.getComponentResults().size(), cartesianResult.getComponentResults().size());
        for (int i = 0; i < polarResult.getComponentResults().size(); i++) {
            assertEquals(polarResult.getComponentResults().get(i).getDistributedActivePower(),
                    cartesianResult.getComponentResults().get(i).getDistributedActivePower(), POWER_EPSILON);
        }

        for (Bus polarBus : polarNetwork.getBusView().getBuses()) {
            Bus cartesianBus = cartesianNetwork.getBusView().getBus(polarBus.getId());
            assertEquals(polarBus.getV(), cartesianBus.getV(), V_EPSILON * polarBus.getVoltageLevel().getNominalV(), "V of bus " + polarBus.getId());
            assertEquals(polarBus.getAngle(), cartesianBus.getAngle(), ANGLE_EPSILON, "Angle of bus " + polarBus.getId());
        }
        for (Branch<?> polarBranch : polarNetwork.getBranches()) {
            Branch<?> cartesianBranch = cartesianNetwork.getBranch(polarBranch.getId());
            for (TwoSides side : TwoSides.values()) {
                assertPowerEquals(polarBranch.getTerminal(side), cartesianBranch.getTerminal(side));
            }
        }
        for (ThreeWindingsTransformer polarTwt : polarNetwork.getThreeWindingsTransformers()) {
            ThreeWindingsTransformer cartesianTwt = cartesianNetwork.getThreeWindingsTransformer(polarTwt.getId());
            for (ThreeSides side : ThreeSides.values()) {
                assertPowerEquals(polarTwt.getTerminal(side), cartesianTwt.getTerminal(side));
            }
        }
        for (Generator polarGenerator : polarNetwork.getGenerators()) {
            assertPowerEquals(polarGenerator.getTerminal(), cartesianNetwork.getGenerator(polarGenerator.getId()).getTerminal());
        }
        for (HvdcConverterStation<?> polarStation : polarNetwork.getHvdcConverterStations()) {
            assertPowerEquals(polarStation.getTerminal(), cartesianNetwork.getHvdcConverterStation(polarStation.getId()).getTerminal());
        }
    }

    private static void assertPowerEquals(Terminal expected, Terminal actual) {
        String id = expected.getConnectable().getId();
        if (Double.isNaN(expected.getP())) {
            assertTrue(Double.isNaN(actual.getP()), "P of " + id);
        } else {
            assertEquals(expected.getP(), actual.getP(), POWER_EPSILON, "P of " + id);
        }
        if (Double.isNaN(expected.getQ())) {
            assertTrue(Double.isNaN(actual.getQ()), "Q of " + id);
        } else {
            assertEquals(expected.getQ(), actual.getQ(), POWER_EPSILON, "Q of " + id);
        }
    }

    private void compare(Supplier<Network> networkSupplier) {
        compare(networkSupplier, parameters -> { });
    }

    @Test
    void testEurostag() {
        compare(() -> EurostagFactory.fix(EurostagTutorialExample1Factory.create()));
    }

    @Test
    void testIeeeNetworks() {
        compare(IeeeCdfNetworkFactory::create14);
        compare(IeeeCdfNetworkFactory::create30);
        compare(IeeeCdfNetworkFactory::create57);
        compare(IeeeCdfNetworkFactory::create118);
        compare(IeeeCdfNetworkFactory::create300);
    }

    @Test
    void testDcValuesVoltageInitialization() {
        compare(IeeeCdfNetworkFactory::create118, parameters -> parameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES));
    }

    @Test
    void testOpenBranchSide() {
        compare(() -> {
            Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
            network.getLine("NHV1_NHV2_1").getTerminal2().disconnect();
            return network;
        });
        compare(() -> {
            Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
            network.getLine("NHV1_NHV2_1").getTerminal1().disconnect();
            return network;
        });
    }

    @Test
    void testRemoteVoltageControl() {
        compare(VoltageControlNetworkFactory::createWithGeneratorRemoteControl);
    }

    @Test
    void testTransformerVoltageControl() {
        compare(VoltageControlNetworkFactory::createNetworkWithT2wt, parameters -> {
            parameters.setTransformerVoltageControlOn(true);
            OpenLoadFlowParameters.get(parameters).setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.WITH_GENERATOR_VOLTAGE_CONTROL);
        });
        compare(VoltageControlNetworkFactory::createNetworkWithT3wt, parameters -> parameters.setTransformerVoltageControlOn(true));
    }

    @Test
    void testPhaseControl() {
        compare(() -> {
            Network network = PhaseControlFactory.createNetworkWithT2wt();
            network.getTwoWindingsTransformer("PS1").getPhaseTapChanger()
                    .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                    .setTargetDeadband(1)
                    .setRegulating(true)
                    .setTapPosition(1)
                    .setRegulationTerminal(network.getTwoWindingsTransformer("PS1").getTerminal1())
                    .setRegulationValue(83);
            return network;
        }, parameters -> parameters.setPhaseShifterRegulationOn(true));
    }

    @Test
    void testShuntVoltageControl() {
        compare(ShuntNetworkFactory::create, parameters -> parameters.setShuntCompensatorVoltageControlOn(true));
    }

    @Test
    void testZeroImpedanceBranches() {
        compare(ZeroImpedanceNetworkFactory::createWithVoltageControl);
        compare(ZeroImpedanceNetworkFactory::createWith3BusesNonImpedantSubNetwork);
    }

    @Test
    void testHvdcAcEmulation() {
        compare(HvdcNetworkFactory::createWithHvdcInAcEmulation, parameters -> parameters.setHvdcAcEmulation(true));
    }

    @Test
    void testLoadModel() {
        compare(() -> {
            Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
            network.getVoltageLevel("VLLOAD").newLoad()
                    .setId("ZIPLOAD")
                    .setBus("NLOAD")
                    .setP0(50)
                    .setQ0(30)
                    .newZipModel()
                        .setC0p(0.5)
                        .setC0q(0.55)
                        .setC1p(0.3)
                        .setC1q(0.35)
                        .setC2p(0.2)
                        .setC2q(0.1)
                        .add()
                    .add();
            return network;
        }, parameters -> OpenLoadFlowParameters.get(parameters).setUseLoadModel(true));
    }

    @Test
    void testFastDecoupledNotSupported() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setAcVoltageRepresentation(AcVoltageRepresentation.CARTESIAN)
                .setAcSolverType(FastDecoupledFactory.NAME);
        CompletionException e = assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, parameters));
        assertInstanceOf(PowsyblException.class, e.getCause());
    }
}
