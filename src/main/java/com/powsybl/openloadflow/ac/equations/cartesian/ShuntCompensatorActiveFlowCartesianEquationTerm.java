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
import com.powsybl.openloadflow.network.LfShunt;

/**
 * Shunt active power p = g.(e^2 + f^2).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ShuntCompensatorActiveFlowCartesianEquationTerm extends AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<LfShunt> {

    public ShuntCompensatorActiveFlowCartesianEquationTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet) {
        super(shunt, bus, variableSet);
    }

    @Override
    protected double f(double v) {
        return element.getG() * v * v;
    }

    @Override
    protected double dfdv(double v) {
        return 2 * element.getG() * v;
    }

    @Override
    protected String getName() {
        return "ac_p_shunt_cartesian";
    }
}
