/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import net.jafama.FastMath;

import java.util.List;
import java.util.Objects;

/**
 * Bus voltage angle phi = atan2(f, e).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class BusVoltageAngleCartesianEquationTerm extends AbstractElementEquationTerm<LfBus, AcVariableType, AcEquationType> {

    private final Variable<AcVariableType> eVar;

    private final Variable<AcVariableType> fVar;

    private final List<Variable<AcVariableType>> variables;

    public BusVoltageAngleCartesianEquationTerm(LfBus bus, VariableSet<AcVariableType> variableSet) {
        super(bus);
        Objects.requireNonNull(variableSet);
        eVar = variableSet.getVariable(bus.getNum(), AcVariableType.BUS_E);
        fVar = variableSet.getVariable(bus.getNum(), AcVariableType.BUS_F);
        variables = List.of(eVar, fVar);
    }

    public static double phi(double e, double f) {
        return FastMath.atan2(f, e);
    }

    public static double dphide(double e, double f) {
        double v2 = e * e + f * f;
        return v2 == 0 ? 0 : -f / v2;
    }

    public static double dphidf(double e, double f) {
        double v2 = e * e + f * f;
        return v2 == 0 ? 0 : e / v2;
    }

    private double e() {
        return sv.get(eVar.getRow());
    }

    private double f() {
        return sv.get(fVar.getRow());
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        return phi(e(), f());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(eVar)) {
            return dphide(e(), f());
        } else if (variable.equals(fVar)) {
            return dphidf(e(), f());
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return CartesianEquationTermUtil.calculateSensi(this, dx, column);
    }

    @Override
    protected String getName() {
        return "ac_phi_cartesian";
    }
}
