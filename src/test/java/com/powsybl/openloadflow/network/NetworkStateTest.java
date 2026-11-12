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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
