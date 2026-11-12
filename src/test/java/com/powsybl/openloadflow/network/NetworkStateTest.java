/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class NetworkStateTest {

    @Test
    void testOriginalLoadDisablingStatusRestore() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        LfLoad load = lfNetwork.getBusById("VLLOAD_0").getLoads().get(0);
        NetworkState networkState = NetworkState.save(lfNetwork);

        // several contingencies losing the load: restoring the network state each time must re-enable the load
        for (int i = 0; i < 3; i++) {
            assertFalse(load.isOriginalLoadDisabled("LOAD"));
            load.setOriginalLoadDisabled("LOAD", true);
            assertTrue(load.isOriginalLoadDisabled("LOAD"));
            networkState.restore();
        }
        assertFalse(load.isOriginalLoadDisabled("LOAD"));
    }

    @Test
    void testIncrementalRestore() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        LfBus genBus = lfNetwork.getBusById("VLGEN_0");
        LfGenerator gen = genBus.getGenerators().get(0);
        LfLoad load = lfNetwork.getBusById("VLLOAD_0").getLoads().get(0);
        LfBranch branch = lfNetwork.getBranchById("NHV1_NHV2_1");
        double genTargetP = gen.getTargetP();
        double loadTargetQ = load.getTargetQ();
        double v = genBus.getV();
        NetworkState networkState = NetworkState.save(lfNetwork);
        StateChangeTracker tracker = lfNetwork.getStateChangeTracker();
        assertEquals(0, tracker.getChangedBusCount());
        assertEquals(0, tracker.getChangedBranchCount());

        for (int i = 0; i < 2; i++) {
            gen.setTargetP(genTargetP + 1);
            load.setTargetQ(loadTargetQ + 1);
            branch.setDisabled(true);
            genBus.setV(v + 0.1); // not tracked, always restored
            assertEquals(2, tracker.getChangedBusCount());
            assertEquals(1, tracker.getChangedBranchCount());
            assertTrue(tracker.isTracking(networkState));
            networkState.restore();
            assertEquals(genTargetP, gen.getTargetP());
            assertEquals(loadTargetQ, load.getTargetQ());
            assertFalse(branch.isDisabled());
            assertEquals(v, genBus.getV());
            assertEquals(0, tracker.getChangedBusCount());
            assertEquals(0, tracker.getChangedBranchCount());
        }

        // another state saved: the first one cannot be restored incrementally anymore, but it is after a full restore
        gen.setTargetP(genTargetP + 2);
        NetworkState otherNetworkState = NetworkState.save(lfNetwork);
        assertFalse(tracker.isTracking(networkState));
        assertTrue(tracker.isTracking(otherNetworkState));
        networkState.restore();
        assertEquals(genTargetP, gen.getTargetP());
        assertTrue(tracker.isTracking(networkState));
        otherNetworkState.restore();
        assertEquals(genTargetP + 2, gen.getTargetP());
        assertTrue(tracker.isTracking(otherNetworkState));
    }
}
