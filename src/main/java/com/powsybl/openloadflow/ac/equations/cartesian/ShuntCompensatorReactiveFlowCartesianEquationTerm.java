/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.Collections;
import java.util.List;

/**
 * Shunt reactive power q = -b.(e^2 + f^2), where b can optionally be a variable.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ShuntCompensatorReactiveFlowCartesianEquationTerm extends AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<LfShunt> {

    private final Variable<AcVariableType> bVar;

    public ShuntCompensatorReactiveFlowCartesianEquationTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, boolean deriveB) {
        this(shunt, bus, variableSet, deriveB ? variableSet.getVariable(shunt.getNum(), AcVariableType.SHUNT_B) : null);
    }

    private ShuntCompensatorReactiveFlowCartesianEquationTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, Variable<AcVariableType> bVar) {
        super(shunt, bus, variableSet, bVar != null ? List.of(bVar) : Collections.emptyList());
        this.bVar = bVar;
    }

    private double b() {
        return bVar != null ? sv.get(bVar.getRow()) : element.getB();
    }

    @Override
    protected double f(double v) {
        return -b() * v * v;
    }

    @Override
    protected double dfdv(double v) {
        return -2 * b() * v;
    }

    @Override
    protected double derOther(Variable<AcVariableType> variable) {
        if (variable.equals(bVar)) {
            double e = e();
            double f = f();
            return -(e * e + f * f);
        }
        return super.derOther(variable);
    }

    @Override
    protected String getName() {
        return "ac_q_shunt_cartesian";
    }
}
