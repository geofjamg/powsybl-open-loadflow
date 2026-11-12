/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

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
}
