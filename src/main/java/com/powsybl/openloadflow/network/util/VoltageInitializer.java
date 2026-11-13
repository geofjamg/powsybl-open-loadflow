/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfDcBus;
import com.powsybl.openloadflow.network.LfNetwork;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface VoltageInitializer {

    void prepare(LfNetwork network, ReportNode reportNode);

    double getMagnitude(LfBus bus);

    double getAngle(LfBus bus);

    /**
     * Same as {@link #getMagnitude(LfBus)} for bus number {@code busNum} of the network: an implementation can avoid
     * accessing the bus (faster when initializing all buses).
     */
    default double getMagnitude(LfNetwork network, int busNum) {
        return getMagnitude(network.getBus(busNum));
    }

    /**
     * Same as {@link #getAngle(LfBus)} for bus number {@code busNum} of the network.
     */
    default double getAngle(LfNetwork network, int busNum) {
        return getAngle(network.getBus(busNum));
    }

    double getDcVoltage(LfDcBus dcBus);

}
