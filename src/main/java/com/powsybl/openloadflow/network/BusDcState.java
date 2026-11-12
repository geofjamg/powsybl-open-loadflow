/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.List;
import java.util.Map;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class BusDcState extends ElementState<LfBus> {

    // generator states, in the order of the bus generator list (which does not change)
    private final double[] generatorsTargetP;
    private final double[] generatorsInitialTargetP;
    private final boolean[] participatingGenerators;
    private final boolean[] disablingStatusGenerators;
    private final List<LoadDcState> loadStates;

    protected static class LoadDcState {

        private double loadTargetP;
        private double absVariableLoadTargetP;
        private String[] originalLoadIds;
        private boolean[] originalLoadsDisabled;
        private int originalLoadsDisablingStatusVersion;

        protected LoadDcState save(LfLoad load) {
            loadTargetP = load.getTargetP();
            absVariableLoadTargetP = load.getAbsVariableTargetP();
            Map<String, Boolean> loadsDisablingStatus = load.getOriginalLoadsDisablingStatus();
            originalLoadIds = new String[loadsDisablingStatus.size()];
            originalLoadsDisabled = new boolean[loadsDisablingStatus.size()];
            int i = 0;
            for (Map.Entry<String, Boolean> e : loadsDisablingStatus.entrySet()) {
                originalLoadIds[i] = e.getKey();
                originalLoadsDisabled[i] = e.getValue();
                i++;
            }
            originalLoadsDisablingStatusVersion = load.getOriginalLoadsDisablingStatusVersion();
            return this;
        }

        protected void restore(LfLoad load) {
            load.setTargetP(loadTargetP);
            load.setAbsVariableTargetP(absVariableLoadTargetP);
            // restore values in the load own map: the saved state must not be shared with the load, otherwise it
            // would be modified by next contingencies disabling original loads
            int version = load.getOriginalLoadsDisablingStatusVersion();
            if (version == -1 || version != originalLoadsDisablingStatusVersion) {
                Map<String, Boolean> loadsDisablingStatus = load.getOriginalLoadsDisablingStatus();
                if (loadsDisablingStatus.size() != originalLoadIds.length) {
                    loadsDisablingStatus.clear();
                }
                for (int i = 0; i < originalLoadIds.length; i++) {
                    loadsDisablingStatus.put(originalLoadIds[i], originalLoadsDisabled[i]);
                }
                // status now equal to the saved one
                originalLoadsDisablingStatusVersion = load.getOriginalLoadsDisablingStatusVersion();
            }
        }
    }

    public BusDcState(LfBus bus) {
        super(bus);
        List<LfGenerator> generators = bus.getGenerators();
        int generatorCount = generators.size();
        generatorsTargetP = new double[generatorCount];
        generatorsInitialTargetP = new double[generatorCount];
        participatingGenerators = new boolean[generatorCount];
        disablingStatusGenerators = new boolean[generatorCount];
        for (int i = 0; i < generatorCount; i++) {
            LfGenerator generator = generators.get(i);
            generatorsTargetP[i] = generator.getTargetP();
            generatorsInitialTargetP[i] = generator.getInitialTargetP();
            participatingGenerators[i] = generator.isParticipating();
            disablingStatusGenerators[i] = generator.isDisabled();
        }
        loadStates = bus.getLoads().stream().map(load -> createLoadState().save(load)).toList();
    }

    protected LoadDcState createLoadState() {
        return new LoadDcState();
    }

    @Override
    public void restore() {
        super.restore();
        List<LfGenerator> generators = element.getGenerators();
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).setTargetP(generatorsTargetP[i]);
        }
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).setInitialTargetP(generatorsInitialTargetP[i]);
        }
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).setParticipating(participatingGenerators[i]);
        }
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).setDisabled(disablingStatusGenerators[i]);
        }
        for (int i = 0; i < loadStates.size(); i++) {
            LfLoad load = element.getLoads().get(i);
            loadStates.get(i).restore(load);
        }
    }

    public static BusDcState save(LfBus bus) {
        return new BusDcState(bus);
    }
}
