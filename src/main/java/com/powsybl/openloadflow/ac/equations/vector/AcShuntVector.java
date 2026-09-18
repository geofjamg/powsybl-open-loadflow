/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.List;

/**
 * Vectorized view of the shunt compensators. Are included the shunt flows and their partial derivatives.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcShuntVector {

    // needed to refresh g and b, which can be changed without any notification (e.g. by a contingency)
    final LfShunt[] shunts;

    final int[] busNum;

    final boolean[] disabled;

    final boolean[] deriveB;

    public final int[] vRow;
    public final int[] bRow;

    final double[] g;
    final double[] b;

    final double[] bState;

    final double[] p;
    final double[] q;

    final double[] dpdv;

    final double[] dqdv;
    final double[] dqdb;

    public AcShuntVector(List<LfShunt> shuntList) {
        int size = shuntList.size();
        shunts = shuntList.toArray(new LfShunt[0]);
        busNum = new int[size];
        disabled = new boolean[size];
        deriveB = new boolean[size];
        vRow = new int[size];
        bRow = new int[size];
        g = new double[size];
        b = new double[size];
        bState = new double[size];
        p = new double[size];
        q = new double[size];
        dpdv = new double[size];
        dqdv = new double[size];
        dqdb = new double[size];
        for (int shuntNum = 0; shuntNum < size; shuntNum++) {
            LfShunt shunt = shunts[shuntNum];
            busNum[shuntNum] = -1;
            disabled[shuntNum] = shunt.isDisabled();
        }
    }

    public int getSize() {
        return shunts.length;
    }

    /**
     * A shunt is attached to a single bus, but {@link LfShunt} does not expose it, so it is set from the equation
     * system creator, which knows it.
     */
    void setBusNum(int shuntNum, LfBus bus) {
        busNum[shuntNum] = bus.getNum();
    }

    void setDeriveB(int shuntNum, boolean value) {
        deriveB[shuntNum] = value;
    }
}
