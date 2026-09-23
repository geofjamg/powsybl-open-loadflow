/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;

/**
 * Bus voltage magnitude v = sqrt(e^2 + f^2).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class BusVoltageMagnitudeCartesianEquationTerm extends AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<LfBus> {

    public BusVoltageMagnitudeCartesianEquationTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        super(bus, bus, variableSet);
    }

    @Override
    protected double f(double v) {
        return v;
    }

    @Override
    protected double dfdv(double v) {
        return 1;
    }

    @Override
    protected String getName() {
        return "ac_v_cartesian";
    }
}
