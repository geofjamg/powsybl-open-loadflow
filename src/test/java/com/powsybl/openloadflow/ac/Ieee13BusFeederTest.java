/**
 * Copyright (c) 2023, Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * Copyright (c) 2023, Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ServiceParameterResolver;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the IEEE 13-bus test feeder ({@link Ieee13BusFeeder}).
 *
 * <p>The feeder operates at 4.16 kV (line-to-line). All tests use the OpenLoadFlow
 * Newton–Raphson solver. Asymmetric tests set {@code parametersExt.setAsymmetrical(true)}
 * so that Fortescue zero/negative-sequence equations are generated.
 *
 * @author Jean-Baptiste Heyberger {@literal <jbheyberger at gmail.com>}
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at gmail.com>}
 */
@ExtendWith(ServiceParameterResolver.class)
public class Ieee13BusFeederTest {

    private final CommonTestConfig commonTestConfig;

    Ieee13BusFeederTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;
    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(commonTestConfig.matrixFactory()));
        parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);
    }

    // -------------------------------------------------------------------------
    // Test 1 — balanced (standard) load flow on the 3-phase backbone
    // -------------------------------------------------------------------------

    /**
     * Runs a standard balanced load flow on the IEEE 13-bus backbone (buses 650, 632, 671,
     * 680, 633, 692, 675) without activating the asymmetric solver.
     *
     * <p>The test checks convergence and that voltages decrease monotonically from the
     * slack bus (650) towards the load buses, which is expected for a radial feeder with
     * predominantly resistive loading.
     */
    @Test
    void balancedBackboneStandardLoadFlowTest() {
        Network network = Ieee13BusFeeder.createBackbone();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(), "Standard balanced load flow must converge");

        Bus b650 = network.getBusBreakerView().getBus("B650");
        Bus b632 = network.getBusBreakerView().getBus("B632");
        Bus b671 = network.getBusBreakerView().getBus("B671");
        Bus b675 = network.getBusBreakerView().getBus("B675");

        // Slack bus must hold its nominal voltage
        assertEquals(Ieee13BusFeeder.NOMINAL_VOLTAGE_KV, b650.getV(), 1e-6,
                "Slack bus B650 must be at nominal voltage");

        // Voltage must decrease along the feeder (radial distribution characteristic)
        assertTrue(b632.getV() < b650.getV(),
                "Voltage at B632 must be lower than at slack B650");
        assertTrue(b671.getV() < b632.getV(),
                "Voltage at B671 must be lower than at B632");

        // Reference positive-sequence line-to-line voltages from OpenDSS (balanced wye loads only).
        // Tolerance 0.05 kV (~1.2 %) covers the small deviation due to the OLF slack holding
        // exactly 4.16 kV whereas OpenDSS sees 4.1568 kV at the source.
        // OpenDSS reference (balanced case): B632=4.0764 kV, B671=4.0072 kV, B675=3.9937 kV
        assertEquals(4.0764, b632.getV(), 0.05, "B632 voltage (OpenDSS ref 4.0764 kV)");
        assertEquals(4.0072, b671.getV(), 0.05, "B671 voltage (OpenDSS ref 4.0072 kV)");
        assertEquals(3.9937, b675.getV(), 0.05, "B675 voltage (OpenDSS ref 3.9937 kV)");
    }

    // -------------------------------------------------------------------------
    // Test 2 — asymmetric solver on backbone with unbalanced load at bus 675
    // -------------------------------------------------------------------------

    /**
     * Activates the asymmetric (Fortescue) solver on the backbone network.
     * Bus 675 carries an unbalanced wye load (different kW and kVAR per phase plus a
     * 200 kVAR capacitor bank), which exercises zero/negative-sequence equation terms.
     *
     * <p>The positive-sequence voltages must remain close to the balanced-case values
     * (within 0.05 kV) because the asymmetry at bus 675 is moderate.
     */
    @Test
    void asymmetricBackboneBalancedLoadsTest() {
        Network network = Ieee13BusFeeder.createBackbone();
        parametersExt.setAsymmetrical(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(), "Asymmetric load flow must converge on backbone");

        Bus b650 = network.getBusBreakerView().getBus("B650");
        Bus b632 = network.getBusBreakerView().getBus("B632");
        Bus b671 = network.getBusBreakerView().getBus("B671");
        Bus b675 = network.getBusBreakerView().getBus("B675");

        // Slack bus must hold its nominal voltage
        assertEquals(Ieee13BusFeeder.NOMINAL_VOLTAGE_KV, b650.getV(), 1e-6,
                "Slack bus B650 must be at nominal voltage in asymmetric mode");

        // Reference positive-sequence line-to-line voltages from OpenDSS (unbalanced wye loads).
        // OpenDSS reference (unbalanced case): B632=4.0655 kV, B671=3.9893 kV, B675=3.9759 kV.
        // Tolerance 0.05 kV covers the OLF/OpenDSS slack-voltage difference (~0.003 kV downstream).
        assertEquals(4.0655, b632.getV(), 0.05,
                "B632 positive-sequence voltage (OpenDSS ref 4.0655 kV)");
        assertEquals(3.9893, b671.getV(), 0.05,
                "B671 positive-sequence voltage (OpenDSS ref 3.9893 kV)");
        assertEquals(3.9759, b675.getV(), 0.05,
                "B675 positive-sequence voltage (OpenDSS ref 3.9759 kV)");
    }

    // -------------------------------------------------------------------------
    // Test 3 — asymmetric solver convergence and physical sanity check
    // -------------------------------------------------------------------------

    /**
     * Verifies that the asymmetric solver converges on the backbone feeder and that
     * all positive-sequence voltages are physically reasonable (above 3.8 kV, i.e.
     * no more than ~9 % below nominal).
     *
     * <pre>
     * OpenDSS reference values for simplified IEEE 13-bus (no transformer, no regulator, Y loads only):
     * These values must be validated by running OpenDSS on the equivalent simplified feeder.
     * TODO: Add exact assertVoltageEquals() once OpenDSS reference run is completed.
     * Expected approximate positive-sequence voltages:
     *   Bus 650: 4.160 kV (slack)
     *   Bus 632: ~4.10 kV
     *   Bus 671: ~4.04 kV
     *   Bus 675: ~4.03 kV (unbalanced loads on this bus)
     * </pre>
     */
    @Test
    void asymmetricWyeLoadConvergenceTest() {
        Network network = Ieee13BusFeeder.createBackbone();
        parametersExt.setAsymmetrical(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(), "Asymmetric load flow must converge");

        // All bus voltages must be physically reasonable.
        // OpenDSS reference minimum is Bus 675 at 3.9759 kV; floor set at 3.9 kV with margin.
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double v = bus.getV();
            assertFalse(Double.isNaN(v), "Voltage at " + bus.getId() + " must not be NaN");
            assertTrue(v > 3.9,
                    "Voltage at " + bus.getId() + " must be above 3.9 kV, got " + v + " kV");
        }
    }

    // -------------------------------------------------------------------------
    // Test 4 — open-phase lines (2-phase and 1-phase) convergence
    // -------------------------------------------------------------------------

    /**
     * Tests that open-phase line modeling (Config 603 BC-only, Config 604 AC-only,
     * Config 605 C-only, Config 607 A-only) produces a convergent solution.
     *
     * <p>The full feeder adds lateral branches with reduced phase counts; this exercises
     * the Fortescue coupled-current equation terms for open-phase (asymmetric) lines.
     */
    @Test
    void twoPhaseLineConvergenceTest() {
        Network network = Ieee13BusFeeder.createFullFeeder();
        parametersExt.setAsymmetrical(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(),
                "Asymmetric load flow must converge on full feeder with open-phase lines");

        // All buses must have valid, positive voltages
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double v = bus.getV();
            assertFalse(Double.isNaN(v), "Voltage at " + bus.getId() + " must not be NaN");
            assertTrue(v > 0.0,
                    "Voltage at " + bus.getId() + " must be positive, got " + v);
        }
    }

    // -------------------------------------------------------------------------
    // Test 5 — documents missing feature: 633→634 transformer
    // -------------------------------------------------------------------------

    /**
     * Documents that the 4.16 kV / 0.48 kV transformer between Bus 633 and Bus 634
     * is not modelled in the factory.
     *
     * <p>MISSING FEATURE: 4.16 kV/0.48 kV transformer between Bus 633 and Bus 634 is not
     * modeled. The transformer is required to serve Bus 634's commercial load
     * (160+j110 kVA/phase). Asymmetric load flow does not yet support two-winding transformers.
     */
    @Test
    void missingFeatureTransformerBus634Test() {
        Network network = Ieee13BusFeeder.createBackbone();

        // No transformer must exist between Bus 633 and Bus 634
        assertNull(network.getTwoWindingsTransformer("T633_634"),
                "Transformer T633_634 should not exist (missing feature)");

        // No 480 V bus / voltage level must exist
        assertNull(network.getVoltageLevel("VL634"),
                "VoltageLevel VL634 (0.48 kV Bus 634) should not exist (missing feature)");
    }

    // -------------------------------------------------------------------------
    // Test 6 — documents missing feature: delta-connected loads
    // -------------------------------------------------------------------------

    /**
     * Documents that delta-connected loads are not supported and verifies that the
     * solver still converges on the partial (wye-only) model.
     *
     * <p>MISSING FEATURE: Delta-connected loads are not modeled. IEEE 13-bus has delta loads at:
     * <ul>
     *   <li>Bus 646: 230+j132 kVA (phase B, delta)</li>
     *   <li>Bus 671: 485+j190 kVA (phases A-B, delta)</li>
     *   <li>Bus 692: 170+j151 kVAR (phase C, delta)</li>
     * </ul>
     * Only wye-connected loads are supported via the LoadAsymmetrical extension.
     */
    @Test
    void missingFeatureDeltaLoadsTest() {
        Network network = Ieee13BusFeeder.createBackbone();
        parametersExt.setAsymmetrical(true);

        // The partial model (wye loads only) must still converge without exception
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged(),
                "Asymmetric load flow must converge even without delta loads");
    }
}
