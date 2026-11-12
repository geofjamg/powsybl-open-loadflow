/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class NetworkState {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkState.class);

    /**
     * If true, each incremental restore is checked against a full save of the restored state (debug).
     */
    private static final boolean CHECK_INCREMENTAL_RESTORE = Boolean.getBoolean("olf.networkState.checkIncrementalRestore");

    /**
     * If false, restore is always a full one.
     */
    private static final boolean INCREMENTAL_RESTORE = !Boolean.getBoolean("olf.networkState.noIncrementalRestore");

    private final LfNetwork network;

    // states of buses and branches, stored in flat arrays (much better memory locality than one object per element)
    private final BusesVectorState busStates;

    private final BranchesVectorState branchStates;

    private final List<HvdcState> hvdcStates;

    private final Set<LfBus> excludedSlackBuses;

    private final List<AreaState> areaStates;

    protected NetworkState(LfNetwork network, BusesVectorState busStates, BranchesVectorState branchStates, List<HvdcState> hvdcStates,
                           Set<LfBus> excludedSlackBuses, List<AreaState> areaStates) {
        this.network = Objects.requireNonNull(network);
        this.busStates = Objects.requireNonNull(busStates);
        this.branchStates = Objects.requireNonNull(branchStates);
        this.hvdcStates = Objects.requireNonNull(hvdcStates);
        this.excludedSlackBuses = Objects.requireNonNull(excludedSlackBuses);
        this.areaStates = Objects.requireNonNull(areaStates);
    }

    public static NetworkState save(LfNetwork network) {
        Objects.requireNonNull(network);
        LOGGER.trace("Saving network state");
        network.setGeneratorsInitialTargetPToTargetP();
        BusesVectorState busStates = BusesVectorState.save(network.getBuses());
        BranchesVectorState branchStates = BranchesVectorState.save(network.getBranches());
        List<HvdcState> hvdcStates = ElementState.save(network.getHvdcs(), HvdcState::save);
        List<AreaState> areaStates = ElementState.save(network.getAreas(), AreaState::save);
        Set<LfBus> excludedSlackBuses = network.getSynchronousNetworks().stream()
            .flatMap(lfScNetwork -> lfScNetwork.getExcludedSlackBuses().stream())
            .collect(Collectors.toSet());
        NetworkState networkState = new NetworkState(network, busStates, branchStates, hvdcStates, excludedSlackBuses, areaStates);
        networkState.startTracking();
        return networkState;
    }

    private void startTracking() {
        network.getStateChangeTracker().reset(this, network.getBuses().size(), network.getBranches().size());
    }

    public void restore() {
        LOGGER.trace("Restoring network state");
        StateChangeTracker tracker = network.getStateChangeTracker();
        if (INCREMENTAL_RESTORE && tracker.isTracking(this)) {
            // only buses and branches modified since last save or restore of this state
            busStates.restore(tracker);
            branchStates.restore(tracker);
            if (CHECK_INCREMENTAL_RESTORE) {
                checkIncrementalRestore();
            }
        } else if (busStates.isCompatible(network.getBuses()) && branchStates.isCompatible(network.getBranches())) {
            busStates.restore();
            branchStates.restore();
        } else {
            // buses or branches have been added or removed, cannot track changes anymore
            busStates.restore();
            branchStates.restore();
            network.getStateChangeTracker().invalidate();
            restoreOthers();
            return;
        }
        restoreOthers();
        startTracking();
    }

    private void checkIncrementalRestore() {
        String busDifference = busStates.findDifference(BusesVectorState.save(network.getBuses()));
        String branchDifference = branchStates.findDifference(BranchesVectorState.save(network.getBranches()));
        if (busDifference != null || branchDifference != null) {
            throw new IllegalStateException("Incremental network state restore failed: "
                    + (busDifference != null ? busDifference : branchDifference) + " has not been restored");
        }
    }

    private void restoreOthers() {
        ElementState.restore(hvdcStates);
        ElementState.restore(areaStates);
        // Set excluded slack buses of each synchronous network
        network.getSynchronousNetworks().forEach(scLfNetwork -> scLfNetwork.setExcludedSlackBuses(excludedSlackBuses));
    }
}
