/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * State of all buses of a network (with their generators, loads and shunts) stored in flat arrays indexed by bus,
 * generator and load position, instead of one state object per element. It restores exactly the same values, with
 * the same setter calls in the same order, as {@link BusState}, but with much better memory locality (this is the
 * hot spot of security analysis contingency loops).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class BusesVectorState {

    private final LfBus[] buses;

    // bus
    private final boolean[] disabled;
    private final double[] angle;
    private final double[] voltage;
    private final double[] generationTargetQ;
    private final boolean[] generationTargetQFrozen;
    private final boolean[] voltageControlEnabled;
    private final boolean[] reactiveControlEnabled;
    private final byte[] shuntVoltageControlEnabled; // -1: no controller shunt, 0: false, 1: true
    private final double[] controllerShuntB;
    private final double[] controllerShuntG;
    private final double[] shuntB;
    private final double[] shuntG;
    private final double[] svcShuntB;
    private final LfBus.QLimitType[] qLimitType;
    // only used to check incremental restore: shunts are enabled or disabled with their bus
    private final byte[] shuntsDisabled; // bit 0: shunt disabled, bit 1: controller shunt disabled

    // generators, bus generators are in [generatorStart[busIndex], generatorStart[busIndex + 1])
    private final int[] generatorStart;
    private final LfGenerator[] generators;
    private final double[] generatorTargetP;
    private final double[] generatorInitialTargetP;
    private final boolean[] generatorParticipating;
    private final boolean[] generatorDisabled;
    private final LfGenerator.GeneratorControlType[] generatorControlType;

    // loads, bus loads are in [loadStart[busIndex], loadStart[busIndex + 1])
    private final int[] loadStart;
    private final LfLoad[] loads;
    private final double[] loadTargetP;
    private final double[] loadAbsVariableTargetP;
    private final double[] loadTargetQ;
    private final String[][] originalLoadIds;
    private final boolean[][] originalLoadsDisabled;
    private final int[] originalLoadsDisablingStatusVersion;

    private BusesVectorState(List<LfBus> busList) {
        int busCount = busList.size();
        buses = busList.toArray(new LfBus[0]);
        disabled = new boolean[busCount];
        angle = new double[busCount];
        voltage = new double[busCount];
        generationTargetQ = new double[busCount];
        generationTargetQFrozen = new boolean[busCount];
        voltageControlEnabled = new boolean[busCount];
        reactiveControlEnabled = new boolean[busCount];
        shuntVoltageControlEnabled = new byte[busCount];
        controllerShuntB = new double[busCount];
        controllerShuntG = new double[busCount];
        shuntB = new double[busCount];
        shuntG = new double[busCount];
        svcShuntB = new double[busCount];
        qLimitType = new LfBus.QLimitType[busCount];
        shuntsDisabled = new byte[busCount];
        generatorStart = new int[busCount + 1];
        loadStart = new int[busCount + 1];
        int generatorCount = 0;
        int loadCount = 0;
        for (int i = 0; i < busCount; i++) {
            generatorStart[i] = generatorCount;
            loadStart[i] = loadCount;
            generatorCount += buses[i].getGenerators().size();
            loadCount += buses[i].getLoads().size();
        }
        generatorStart[busCount] = generatorCount;
        loadStart[busCount] = loadCount;
        generators = new LfGenerator[generatorCount];
        generatorTargetP = new double[generatorCount];
        generatorInitialTargetP = new double[generatorCount];
        generatorParticipating = new boolean[generatorCount];
        generatorDisabled = new boolean[generatorCount];
        generatorControlType = new LfGenerator.GeneratorControlType[generatorCount];
        loads = new LfLoad[loadCount];
        loadTargetP = new double[loadCount];
        loadAbsVariableTargetP = new double[loadCount];
        loadTargetQ = new double[loadCount];
        originalLoadIds = new String[loadCount][];
        originalLoadsDisabled = new boolean[loadCount][];
        originalLoadsDisablingStatusVersion = new int[loadCount];
        for (int i = 0; i < busCount; i++) {
            saveBus(i);
        }
    }

    private void saveBus(int i) {
        LfBus bus = buses[i];
        disabled[i] = bus.isDisabled();
        angle[i] = bus.getAngle();
        voltage[i] = bus.getV();
        generationTargetQ[i] = bus.getGenerationTargetQ();
        generationTargetQFrozen[i] = bus.isGenerationTargetQFrozen();
        voltageControlEnabled[i] = bus.isGeneratorVoltageControlEnabled();
        reactiveControlEnabled[i] = bus.isGeneratorReactivePowerControlEnabled();
        LfShunt controllerShunt = bus.getControllerShunt().orElse(null);
        shuntVoltageControlEnabled[i] = controllerShunt == null ? -1 : (byte) (controllerShunt.isVoltageControlEnabled() ? 1 : 0);
        controllerShuntB[i] = controllerShunt != null ? controllerShunt.getB() : Double.NaN;
        controllerShuntG[i] = controllerShunt != null ? controllerShunt.getG() : Double.NaN;
        LfShunt shunt = bus.getShunt().orElse(null);
        shuntB[i] = shunt != null ? shunt.getB() : Double.NaN;
        shuntG[i] = shunt != null ? shunt.getG() : Double.NaN;
        LfShunt svcShunt = bus.getSvcShunt().orElse(null);
        svcShuntB[i] = svcShunt != null ? svcShunt.getB() : Double.NaN;
        qLimitType[i] = bus.getQLimitType().orElse(null);
        shuntsDisabled[i] = (byte) ((shunt != null && shunt.isDisabled() ? 1 : 0) | (controllerShunt != null && controllerShunt.isDisabled() ? 2 : 0));

        List<LfGenerator> busGenerators = bus.getGenerators();
        for (int k = 0; k < busGenerators.size(); k++) {
            int g = generatorStart[i] + k;
            LfGenerator generator = busGenerators.get(k);
            generators[g] = generator;
            generatorTargetP[g] = generator.getTargetP();
            generatorInitialTargetP[g] = generator.getInitialTargetP();
            generatorParticipating[g] = generator.isParticipating();
            generatorDisabled[g] = generator.isDisabled();
            generatorControlType[g] = generator.getGeneratorControlType();
        }

        List<LfLoad> busLoads = bus.getLoads();
        for (int k = 0; k < busLoads.size(); k++) {
            int l = loadStart[i] + k;
            LfLoad load = busLoads.get(k);
            loads[l] = load;
            loadTargetP[l] = load.getTargetP();
            loadAbsVariableTargetP[l] = load.getAbsVariableTargetP();
            loadTargetQ[l] = load.getTargetQ();
            Map<String, Boolean> loadsDisablingStatus = load.getOriginalLoadsDisablingStatus();
            String[] ids = new String[loadsDisablingStatus.size()];
            boolean[] status = new boolean[loadsDisablingStatus.size()];
            int j = 0;
            for (Map.Entry<String, Boolean> e : loadsDisablingStatus.entrySet()) {
                ids[j] = e.getKey();
                status[j] = e.getValue();
                j++;
            }
            originalLoadIds[l] = ids;
            originalLoadsDisabled[l] = status;
            originalLoadsDisablingStatusVersion[l] = load.getOriginalLoadsDisablingStatusVersion();
        }
    }

    public static BusesVectorState save(List<LfBus> buses) {
        return new BusesVectorState(buses);
    }

    private void restoreLoad(int l) {
        LfLoad load = loads[l];
        load.setTargetP(loadTargetP[l]);
        load.setAbsVariableTargetP(loadAbsVariableTargetP[l]);
        int version = load.getOriginalLoadsDisablingStatusVersion();
        if (version == -1 || version != originalLoadsDisablingStatusVersion[l]) {
            // restore values in the load own map (saved state is not shared with the load)
            Map<String, Boolean> loadsDisablingStatus = load.getOriginalLoadsDisablingStatus();
            String[] ids = originalLoadIds[l];
            boolean[] status = originalLoadsDisabled[l];
            if (loadsDisablingStatus.size() != ids.length) {
                loadsDisablingStatus.clear();
            }
            for (int j = 0; j < ids.length; j++) {
                loadsDisablingStatus.put(ids[j], status[j]);
            }
            originalLoadsDisablingStatusVersion[l] = load.getOriginalLoadsDisablingStatusVersion();
        }
        load.setTargetQ(loadTargetQ[l]);
    }

    /**
     * Same setter calls in the same order as {@link BusState#restore()}.
     */
    private void restoreBus(int i) {
        LfBus bus = buses[i];
        bus.setDisabled(disabled[i]);
        for (int g = generatorStart[i]; g < generatorStart[i + 1]; g++) {
            LfGenerator generator = generators[g];
            generator.setTargetP(generatorTargetP[g]);
            generator.setInitialTargetP(generatorInitialTargetP[g]);
            generator.setParticipating(generatorParticipating[g]);
            generator.setDisabled(generatorDisabled[g]);
        }
        for (int l = loadStart[i]; l < loadStart[i + 1]; l++) {
            restoreLoad(l);
        }
        bus.setAngle(angle[i]);
        bus.setV(voltage[i]);
        bus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(voltageControlEnabled[i]);
        if (generationTargetQFrozen[i]) {
            bus.freezeGenerationTargetQAndDisableGeneratorVoltageControl(generationTargetQ[i]);
        }
        bus.setGeneratorReactivePowerControlEnabled(reactiveControlEnabled[i]);
        if (shuntVoltageControlEnabled[i] != -1) {
            bus.getControllerShunt().orElseThrow().setVoltageControlEnabled(shuntVoltageControlEnabled[i] == 1);
        }
        if (!Double.isNaN(controllerShuntB[i])) {
            bus.getControllerShunt().orElseThrow().setB(controllerShuntB[i]);
        }
        if (!Double.isNaN(controllerShuntG[i])) {
            bus.getControllerShunt().orElseThrow().setG(controllerShuntG[i]);
        }
        if (!Double.isNaN(shuntB[i])) {
            bus.getShunt().orElseThrow().setB(shuntB[i]);
        }
        if (!Double.isNaN(shuntG[i])) {
            bus.getShunt().orElseThrow().setG(shuntG[i]);
        }
        if (!Double.isNaN(svcShuntB[i])) {
            bus.getSvcShunt().orElseThrow().setB(svcShuntB[i]);
        }
        for (int g = generatorStart[i]; g < generatorStart[i + 1]; g++) {
            generators[g].setGeneratorControlType(generatorControlType[g]);
        }
        bus.setQLimitType(qLimitType[i]);
    }

    public void restore() {
        for (int i = 0; i < buses.length; i++) {
            restoreBus(i);
        }
    }

    /**
     * Incremental restore: full restore of the given buses (only ones that have been modified), and voltage
     * magnitude and angle restore of all the buses (always modified by a load flow run).
     */
    public void restore(StateChangeTracker tracker) {
        int[] changed = new int[tracker.getChangedBusCount()];
        for (int k = 0; k < changed.length; k++) {
            changed[k] = tracker.getChangedBus(k);
        }
        Arrays.sort(changed); // same order as a full restore
        for (int i : changed) {
            restoreBus(i);
        }
        for (int i = 0; i < buses.length; i++) {
            LfBus bus = buses[i];
            bus.setAngle(angle[i]);
            bus.setV(voltage[i]);
        }
    }

    boolean isCompatible(List<LfBus> busList) {
        if (busList.size() != buses.length) {
            return false;
        }
        for (int i = 0; i < buses.length; i++) {
            if (busList.get(i) != buses[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find a difference with another state of the same buses (for incremental restore checking).
     *
     * @return the description of the first difference found, or null if both states are the same
     */
    String findDifference(BusesVectorState other) {
        for (int i = 0; i < buses.length; i++) {
            if (disabled[i] != other.disabled[i]
                    || Double.compare(angle[i], other.angle[i]) != 0
                    || Double.compare(voltage[i], other.voltage[i]) != 0
                    || Double.compare(generationTargetQ[i], other.generationTargetQ[i]) != 0
                    || generationTargetQFrozen[i] != other.generationTargetQFrozen[i]
                    || voltageControlEnabled[i] != other.voltageControlEnabled[i]
                    || reactiveControlEnabled[i] != other.reactiveControlEnabled[i]
                    || shuntVoltageControlEnabled[i] != other.shuntVoltageControlEnabled[i]
                    || Double.compare(controllerShuntB[i], other.controllerShuntB[i]) != 0
                    || Double.compare(controllerShuntG[i], other.controllerShuntG[i]) != 0
                    || Double.compare(shuntB[i], other.shuntB[i]) != 0
                    || Double.compare(shuntG[i], other.shuntG[i]) != 0
                    || Double.compare(svcShuntB[i], other.svcShuntB[i]) != 0
                    || qLimitType[i] != other.qLimitType[i]
                    || shuntsDisabled[i] != other.shuntsDisabled[i]) {
                return "bus " + buses[i].getId();
            }
        }
        for (int g = 0; g < generators.length; g++) {
            if (Double.compare(generatorTargetP[g], other.generatorTargetP[g]) != 0
                    || Double.compare(generatorInitialTargetP[g], other.generatorInitialTargetP[g]) != 0
                    || generatorParticipating[g] != other.generatorParticipating[g]
                    || generatorDisabled[g] != other.generatorDisabled[g]
                    || generatorControlType[g] != other.generatorControlType[g]) {
                return "generator " + generators[g].getId();
            }
        }
        for (int l = 0; l < loads.length; l++) {
            if (Double.compare(loadTargetP[l], other.loadTargetP[l]) != 0
                    || Double.compare(loadAbsVariableTargetP[l], other.loadAbsVariableTargetP[l]) != 0
                    || Double.compare(loadTargetQ[l], other.loadTargetQ[l]) != 0
                    || !Arrays.equals(originalLoadIds[l], other.originalLoadIds[l])
                    || !Arrays.equals(originalLoadsDisabled[l], other.originalLoadsDisabled[l])) {
                return "load " + loads[l].getId();
            }
        }
        return null;
    }
}
