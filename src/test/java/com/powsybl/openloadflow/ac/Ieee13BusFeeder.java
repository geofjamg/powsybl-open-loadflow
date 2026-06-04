/**
 * Copyright (c) 2023, Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * Copyright (c) 2023, Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.GeneratorFortescueAdder;
import com.powsybl.iidm.network.extensions.LineFortescueAdder;
import com.powsybl.iidm.network.extensions.LoadAsymmetricalAdder;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;

/**
 * Factory for the IEEE 13-bus test feeder (Kersting, "Distribution System Modeling and Analysis", 4th ed.).
 *
 * <p>The feeder operates at 4.16 kV (line-to-line) and is used to exercise the asymmetric
 * (Fortescue-based) AC load-flow implementation.
 * Delta-connected loads are not yet supported and are omitted.
 *
 * @author Jean-Baptiste Heyberger {@literal <jbheyberger at gmail.com>}
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at gmail.com>}
 */
public final class Ieee13BusFeeder {

    /** Nominal line-to-line voltage of the IEEE 13-bus feeder, in kV. */
    public static final double NOMINAL_VOLTAGE_KV = 4.16;

    /** Nominal voltage of the 480 V bus (Bus 634), in kV. */
    public static final double NOMINAL_VOLTAGE_634_KV = 0.48;

    private Ieee13BusFeeder() {
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a per-mile impedance value to Ohms for a given distance expressed in feet.
     */
    private static double ohmsFromMile(double perMile, int feet) {
        return perMile * feet / 5280.0;
    }

    /**
     * Creates a Substation + VoltageLevel pair and returns the VoltageLevel.
     *
     * @param network   the target network
     * @param substId   the substation id (e.g. "S650")
     * @param vlId      the voltage-level id (e.g. "VL650")
     */
    private static VoltageLevel createVoltageLevel(Network network, String substId, String vlId) {
        return createVoltageLevel(network, substId, vlId, NOMINAL_VOLTAGE_KV);
    }

    private static VoltageLevel createVoltageLevel(Network network, String substId, String vlId, double nominalV) {
        Substation substation = network.newSubstation()
                .setId(substId)
                .add();
        return substation.newVoltageLevel()
                .setId(vlId)
                .setNominalV(nominalV)
                .setLowVoltageLimit(0)
                .setHighVoltageLimit(nominalV * 2)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
    }

    /**
     * Adds a Line between two buses and attaches a {@code LineFortescue} extension.
     *
     * <p>All lines in the asymmetric model must have the Fortescue extension so that
     * the equation system can include zero/negative-sequence terms.
     *
     * @param network   the target network
     * @param id        line id
     * @param vlId1     voltage-level id at side 1
     * @param vlId2     voltage-level id at side 2
     * @param r         positive-sequence resistance (Ω)
     * @param x         positive-sequence reactance (Ω)
     * @param rz        zero-sequence resistance (Ω)
     * @param xz        zero-sequence reactance (Ω)
     * @param openA     true if phase A is open
     * @param openB     true if phase B is open
     * @param openC     true if phase C is open
     */
    private static void addLine(Network network, String id,
                                String vlId1, String vlId2,
                                double r, double x,
                                double rz, double xz,
                                boolean openA, boolean openB, boolean openC) {
        VoltageLevel vl1 = network.getVoltageLevel(vlId1);
        VoltageLevel vl2 = network.getVoltageLevel(vlId2);
        String busId1 = vlId1.replace("VL", "B");
        String busId2 = vlId2.replace("VL", "B");

        Line line = network.newLine()
                .setId(id)
                .setVoltageLevel1(vl1.getId())
                .setBus1(busId1)
                .setConnectableBus1(busId1)
                .setVoltageLevel2(vl2.getId())
                .setBus2(busId2)
                .setConnectableBus2(busId2)
                .setR(r)
                .setX(x)
                .setG1(0.0)
                .setB1(0.0)
                .setG2(0.0)
                .setB2(0.0)
                .add();

        line.newExtension(LineFortescueAdder.class)
                .withRz(rz)
                .withXz(xz)
                .withOpenPhaseA(openA)
                .withOpenPhaseB(openB)
                .withOpenPhaseC(openC)
                .add();
    }

    // -------------------------------------------------------------------------
    // Line configuration impedances (per-mile values, converted on the fly)
    // -------------------------------------------------------------------------

    // Config 601 — 3-phase overhead
    private static final double C601_R1 = 0.1860;
    private static final double C601_X1 = 0.5968;
    private static final double C601_R0 = 0.6535;
    private static final double C601_X0 = 1.9070;

    // Config 602 — 3-phase overhead
    private static final double C602_R1 = 0.5921;
    private static final double C602_X1 = 0.7603;
    private static final double C602_R0 = 1.0596;
    private static final double C602_X0 = 2.0705;

    // Config 606 — 3-phase underground
    private static final double C606_R1 = 0.7153;
    private static final double C606_X1 = 0.2342;
    private static final double C606_R0 = 1.5590;
    private static final double C606_X0 = 0.2042;

    // Config 603 — phases B,C only (openPhaseA)
    private static final double C603_R1 = 0.5921;
    private static final double C603_X1 = 0.8199;
    private static final double C603_R0 = 0.8991;
    private static final double C603_X0 = 1.5897;

    // Config 604 — phases A,C only (openPhaseB)
    private static final double C604_R1 = 0.5921;
    private static final double C604_X1 = 0.7603;
    private static final double C604_R0 = 1.0596;
    private static final double C604_X0 = 2.0705;

    // Config 605 — phase C only (openPhaseA, openPhaseB)
    private static final double C605_R1 = 1.3292;
    private static final double C605_X1 = 1.3475;
    private static final double C605_R0 = 1.3292;
    private static final double C605_X0 = 1.3475;

    // Config 607 — phase A only (openPhaseB, openPhaseC)
    private static final double C607_R1 = 1.3292;
    private static final double C607_X1 = 1.3475;
    private static final double C607_R0 = 1.3292;
    private static final double C607_X0 = 1.3475;

    // -------------------------------------------------------------------------
    // Public factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates the three-phase "backbone" of the IEEE 13-bus feeder.
     *
     * <p>Buses included: 650, 632, 671, 680, 633, 692, 675.
     * Lines: all three-phase segments (601, 602, 606 configurations plus the 671-692 switch).
     * Loads: distributed load at Bus 633 (balanced), spot load at Bus 671 (balanced),
     * and unbalanced wye load at Bus 675 (with Fortescue extension).
     *
     * <p>Also includes Bus 634 (0.48 kV) and the 4.16 kV/0.48 kV transformer T633_634
     * (500 kVA, YG-YG, %R=1.1%, %X=2%) with its three-phase wye load.
     * Delta-connected loads are omitted (not yet supported).
     */
    public static Network createBackbone() {
        Network network = Network.create("ieee13-backbone", "ieee13");

        // --- Buses ---
        VoltageLevel vl650 = createVoltageLevel(network, "S650", "VL650");
        Bus b650 = vl650.getBusBreakerView().newBus().setId("B650").add();
        b650.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl632 = createVoltageLevel(network, "S632", "VL632");
        Bus b632 = vl632.getBusBreakerView().newBus().setId("B632").add();
        b632.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl671 = createVoltageLevel(network, "S671", "VL671");
        Bus b671 = vl671.getBusBreakerView().newBus().setId("B671").add();
        b671.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl680 = createVoltageLevel(network, "S680", "VL680");
        Bus b680 = vl680.getBusBreakerView().newBus().setId("B680").add();
        b680.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl633 = createVoltageLevel(network, "S633", "VL633");
        Bus b633 = vl633.getBusBreakerView().newBus().setId("B633").add();
        b633.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl692 = createVoltageLevel(network, "S692", "VL692");
        Bus b692 = vl692.getBusBreakerView().newBus().setId("B692").add();
        b692.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl675 = createVoltageLevel(network, "S675", "VL675");
        Bus b675 = vl675.getBusBreakerView().newBus().setId("B675").add();
        b675.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        // VL634 is added to S633 (IIDM requires both TWT windings in the same substation)
        Substation s633 = network.getSubstation("S633");
        VoltageLevel vl634 = s633.newVoltageLevel()
                .setId("VL634")
                .setNominalV(NOMINAL_VOLTAGE_634_KV)
                .setLowVoltageLimit(0)
                .setHighVoltageLimit(NOMINAL_VOLTAGE_634_KV * 2)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        Bus b634 = vl634.getBusBreakerView().newBus().setId("B634").add();
        b634.setV(NOMINAL_VOLTAGE_634_KV).setAngle(0.0);

        // --- Slack generator at Bus 650 ---
        Generator gen650 = vl650.newGenerator()
                .setId("G650")
                .setBus("B650")
                .setConnectableBus("B650")
                .setMinP(0.0)
                .setMaxP(5000.0)
                .setTargetP(10.0)
                .setTargetV(NOMINAL_VOLTAGE_KV)
                .setVoltageRegulatorOn(true)
                .add();
        // Stiff-source Fortescue: zero-impedance positive-sequence, small zero/neutral
        gen650.newExtension(GeneratorFortescueAdder.class)
                .withRz(0.0)
                .withXz(0.001)
                .withRn(0.0)
                .withXn(0.001)
                .add();

        // --- Lines (backbone — all three-phase) ---

        // 650 → 632 : config 601, 2000 ft
        addLine(network, "L650_632", "VL650", "VL632",
                ohmsFromMile(C601_R1, 2000), ohmsFromMile(C601_X1, 2000),
                ohmsFromMile(C601_R0, 2000), ohmsFromMile(C601_X0, 2000),
                false, false, false);

        // 632 → 671 : config 601, 2000 ft
        addLine(network, "L632_671", "VL632", "VL671",
                ohmsFromMile(C601_R1, 2000), ohmsFromMile(C601_X1, 2000),
                ohmsFromMile(C601_R0, 2000), ohmsFromMile(C601_X0, 2000),
                false, false, false);

        // 671 → 680 : config 601, 1000 ft
        addLine(network, "L671_680", "VL671", "VL680",
                ohmsFromMile(C601_R1, 1000), ohmsFromMile(C601_X1, 1000),
                ohmsFromMile(C601_R0, 1000), ohmsFromMile(C601_X0, 1000),
                false, false, false);

        // 632 → 633 : config 602, 500 ft
        addLine(network, "L632_633", "VL632", "VL633",
                ohmsFromMile(C602_R1, 500), ohmsFromMile(C602_X1, 500),
                ohmsFromMile(C602_R0, 500), ohmsFromMile(C602_X0, 500),
                false, false, false);

        // 671 → 692 : near-zero-impedance switch (closed), 0.001 Ω
        addLine(network, "L671_692", "VL671", "VL692",
                0.001, 0.001,
                0.001, 0.001,
                false, false, false);

        // 692 → 675 : config 606, 500 ft
        addLine(network, "L692_675", "VL692", "VL675",
                ohmsFromMile(C606_R1, 500), ohmsFromMile(C606_X1, 500),
                ohmsFromMile(C606_R0, 500), ohmsFromMile(C606_X0, 500),
                false, false, false);

        // 633 → 634 : 500 kVA, 4.16/0.48 kV transformer, YG-YG, %R=1.1%, %X=2%
        // Zbase (side 2) = 0.48² / 0.5 = 0.4608 Ω → R=0.005069 Ω, X=0.009216 Ω (referred to side 2)
        // For YG-YG: zero-sequence impedance = positive-sequence impedance
        TwoWindingsTransformer twt633634 = s633.newTwoWindingsTransformer()
                .setId("T633_634")
                .setVoltageLevel1("VL633")
                .setBus1("B633")
                .setConnectableBus1("B633")
                .setVoltageLevel2("VL634")
                .setBus2("B634")
                .setConnectableBus2("B634")
                .setRatedU1(NOMINAL_VOLTAGE_KV)
                .setRatedU2(NOMINAL_VOLTAGE_634_KV)
                .setRatedS(0.5)
                .setR(0.005069)
                .setX(0.009216)
                .setG(0.0)
                .setB(0.0)
                .add();
        twt633634.newExtension(TwoWindingsTransformerFortescueAdder.class)
                .withRz(0.005069)
                .withXz(0.009216)
                .withConnectionType1(WindingConnectionType.Y_GROUNDED)
                .withConnectionType2(WindingConnectionType.Y_GROUNDED)
                .add();

        // --- Loads ---

        // Bus 633: small distributed load — balanced (no Fortescue extension needed)
        // P0 = 0.051 MW, Q0 = 0.030 MVAR
        vl633.newLoad()
                .setId("LOAD_633")
                .setBus("B633")
                .setConnectableBus("B633")
                .setP0(0.051)
                .setQ0(0.030)
                .add();

        // Bus 671: spot load — balanced (no Fortescue extension)
        // 385+j220 kVA/phase → total 1.155 MW + j0.660 MVAR
        vl671.newLoad()
                .setId("LOAD_671")
                .setBus("B671")
                .setConnectableBus("B671")
                .setP0(1.155)
                .setQ0(0.660)
                .add();

        // Bus 675: unbalanced wye load + 200 kVAR capacitor bank per phase
        // Phase A: 485 kW  - 10 kVAR   (cap = 200 kVAR, reactive from load = 190 kVAR → net = -10 kVAR)
        // Phase B:  68 kW  - 140 kVAR  (cap = 200 kVAR, reactive from load =  60 kVAR → net = -140 kVAR)
        // Phase C: 290 kW  + 12 kVAR   (cap = 200 kVAR, reactive from load = 212 kVAR → net =  +12 kVAR)
        // P0 = (485+68+290)/1000 = 0.843 MW
        // Q0 = (-10-140+12)/1000 = -0.138 MVAR
        // deltaPa = 3*0.485 - 0.843 =  0.612 MW
        // deltaPb = 3*0.068 - 0.843 = -0.639 MW
        // deltaPc = 3*0.290 - 0.843 =  0.027 MW
        // deltaQa = 3*(-0.010) - (-0.138) =  0.108 MVAR
        // deltaQb = 3*(-0.140) - (-0.138) = -0.282 MVAR
        // deltaQc = 3*(0.012)  - (-0.138) =  0.174 MVAR
        Load load675 = vl675.newLoad()
                .setId("LOAD_675")
                .setBus("B675")
                .setConnectableBus("B675")
                .setP0(0.843)
                .setQ0(-0.138)
                .add();
        load675.newExtension(LoadAsymmetricalAdder.class)
                .withDeltaPa(0.612)
                .withDeltaQa(0.108)
                .withDeltaPb(-0.639)
                .withDeltaQb(-0.282)
                .withDeltaPc(0.027)
                .withDeltaQc(0.174)
                .add();

        // Bus 634: balanced 3-phase wye load — 160+j110 kVA/phase → total 0.48 MW + j0.33 MVAR
        vl634.newLoad()
                .setId("LOAD_634")
                .setBus("B634")
                .setConnectableBus("B634")
                .setP0(0.48)
                .setQ0(0.33)
                .add();

        return network;
    }

    /**
     * Creates the full IEEE 13-bus feeder, including two-phase and single-phase lateral branches.
     *
     * <p>In addition to everything in {@link #createBackbone()}, this method adds:
     * <ul>
     *   <li>Bus 645 and Bus 646 (branch 632-645-646, config 603, openPhaseA)</li>
     *   <li>Bus 684, Bus 611, Bus 652 (branch 671-684-611 and 684-652)</li>
     *   <li>Config 604 (openPhaseB), config 605 (openPhaseA+B), config 607 (openPhaseB+C)</li>
     *   <li>Asymmetric wye loads at buses 645, 611, 652</li>
     * </ul>
     *
     * <p>Bus 646 has a delta load in the original feeder which is not supported; no load is added.
     */
    public static Network createFullFeeder() {
        // Start from the backbone and extend it
        Network network = createBackbone();

        // --- Extra buses ---

        VoltageLevel vl645 = createVoltageLevel(network, "S645", "VL645");
        Bus b645 = vl645.getBusBreakerView().newBus().setId("B645").add();
        b645.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl646 = createVoltageLevel(network, "S646", "VL646");
        Bus b646 = vl646.getBusBreakerView().newBus().setId("B646").add();
        b646.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl684 = createVoltageLevel(network, "S684", "VL684");
        Bus b684 = vl684.getBusBreakerView().newBus().setId("B684").add();
        b684.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl611 = createVoltageLevel(network, "S611", "VL611");
        Bus b611 = vl611.getBusBreakerView().newBus().setId("B611").add();
        b611.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        VoltageLevel vl652 = createVoltageLevel(network, "S652", "VL652");
        Bus b652 = vl652.getBusBreakerView().newBus().setId("B652").add();
        b652.setV(NOMINAL_VOLTAGE_KV).setAngle(0.0);

        // --- Extra lines ---

        // 632 → 645 : config 603, 500 ft (openPhaseA — B,C only)
        addLine(network, "L632_645", "VL632", "VL645",
                ohmsFromMile(C603_R1, 500), ohmsFromMile(C603_X1, 500),
                ohmsFromMile(C603_R0, 500), ohmsFromMile(C603_X0, 500),
                true, false, false);

        // 645 → 646 : config 603, 300 ft (openPhaseA — B,C only)
        addLine(network, "L645_646", "VL645", "VL646",
                ohmsFromMile(C603_R1, 300), ohmsFromMile(C603_X1, 300),
                ohmsFromMile(C603_R0, 300), ohmsFromMile(C603_X0, 300),
                true, false, false);

        // 671 → 684 : config 604, 300 ft (openPhaseB — A,C only)
        addLine(network, "L671_684", "VL671", "VL684",
                ohmsFromMile(C604_R1, 300), ohmsFromMile(C604_X1, 300),
                ohmsFromMile(C604_R0, 300), ohmsFromMile(C604_X0, 300),
                false, true, false);

        // 684 → 611 : config 605, 300 ft (openPhaseA+B — C only)
        addLine(network, "L684_611", "VL684", "VL611",
                ohmsFromMile(C605_R1, 300), ohmsFromMile(C605_X1, 300),
                ohmsFromMile(C605_R0, 300), ohmsFromMile(C605_X0, 300),
                true, true, false);

        // 684 → 652 : config 607, 800 ft (openPhaseB+C — A only)
        addLine(network, "L684_652", "VL684", "VL652",
                ohmsFromMile(C607_R1, 800), ohmsFromMile(C607_X1, 800),
                ohmsFromMile(C607_R0, 800), ohmsFromMile(C607_X0, 800),
                false, true, true);

        // --- Loads for extra buses ---

        // Bus 645: phase B reactive only — 0+j170 kVAR total (wye)
        // P0 = 0, Q0 = 0.170 MVAR
        // deltaPa = 0,  deltaQa = 3*0     - 0.170 = -0.170 MVAR
        // deltaPb = 0,  deltaQb = 3*0.170 - 0.170 =  0.340 MVAR
        // deltaPc = 0,  deltaQc = 3*0     - 0.170 = -0.170 MVAR
        Load load645 = vl645.newLoad()
                .setId("LOAD_645")
                .setBus("B645")
                .setConnectableBus("B645")
                .setP0(0.0)
                .setQ0(0.170)
                .add();
        load645.newExtension(LoadAsymmetricalAdder.class)
                .withDeltaPa(0.0)
                .withDeltaQa(-0.170)
                .withDeltaPb(0.0)
                .withDeltaQb(0.340)
                .withDeltaPc(0.0)
                .withDeltaQc(-0.170)
                .add();

        // Bus 646: delta load in original feeder — NOT SUPPORTED; no load added.
        // (Original: 230+j132 kVA phase B, delta connection)

        // Bus 611: phase C — 170 kW + (80-100) kVAR (cap = 100 kVAR → net reactive = -20 kVAR)
        // P0 = 0.170 MW, Q0 = -0.020 MVAR
        // deltaPa = 3*0     - 0.170 = -0.170 MW
        // deltaPb = 3*0     - 0.170 = -0.170 MW
        // deltaPc = 3*0.170 - 0.170 =  0.340 MW
        // deltaQa = 3*0          - (-0.020) =  0.020 MVAR
        // deltaQb = 3*0          - (-0.020) =  0.020 MVAR
        // deltaQc = 3*(-0.020)   - (-0.020) = -0.040 MVAR
        Load load611 = vl611.newLoad()
                .setId("LOAD_611")
                .setBus("B611")
                .setConnectableBus("B611")
                .setP0(0.170)
                .setQ0(-0.020)
                .add();
        load611.newExtension(LoadAsymmetricalAdder.class)
                .withDeltaPa(-0.170)
                .withDeltaQa(0.020)
                .withDeltaPb(-0.170)
                .withDeltaQb(0.020)
                .withDeltaPc(0.340)
                .withDeltaQc(-0.040)
                .add();

        // Bus 652: phase A — 128+j86 kVA (wye)
        // P0 = 0.128 MW, Q0 = 0.086 MVAR
        // deltaPa = 3*0.128 - 0.128 =  0.256 MW
        // deltaPb = 3*0     - 0.128 = -0.128 MW
        // deltaPc = 3*0     - 0.128 = -0.128 MW
        // deltaQa = 3*0.086 - 0.086 =  0.172 MVAR
        // deltaQb = 3*0     - 0.086 = -0.086 MVAR
        // deltaQc = 3*0     - 0.086 = -0.086 MVAR
        Load load652 = vl652.newLoad()
                .setId("LOAD_652")
                .setBus("B652")
                .setConnectableBus("B652")
                .setP0(0.128)
                .setQ0(0.086)
                .add();
        load652.newExtension(LoadAsymmetricalAdder.class)
                .withDeltaPa(0.256)
                .withDeltaQa(0.172)
                .withDeltaPb(-0.128)
                .withDeltaQb(-0.086)
                .withDeltaPc(-0.128)
                .withDeltaQc(-0.086)
                .add();

        return network;
    }
}
