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
import com.powsybl.openloadflow.network.LfElement;
import net.jafama.FastMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base class of terms that only depend on a bus voltage magnitude v = sqrt(e^2 + f^2), where e and f are the
 * real and imaginary parts of the bus voltage.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<T extends LfElement>
        extends AbstractElementEquationTerm<T, AcVariableType, AcEquationType> {

    protected final Variable<AcVariableType> eVar;

    protected final Variable<AcVariableType> fVar;

    protected final List<Variable<AcVariableType>> variables;

    protected AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm(T element, LfBus bus, VariableSet<AcVariableType> variableSet,
                                                                       List<Variable<AcVariableType>> otherVariables) {
        super(element);
        Objects.requireNonNull(bus);
        Objects.requireNonNull(variableSet);
        eVar = variableSet.getVariable(bus.getNum(), AcVariableType.BUS_E);
        fVar = variableSet.getVariable(bus.getNum(), AcVariableType.BUS_F);
        List<Variable<AcVariableType>> allVariables = new ArrayList<>(2 + otherVariables.size());
        allVariables.add(eVar);
        allVariables.add(fVar);
        allVariables.addAll(otherVariables);
        variables = Collections.unmodifiableList(allVariables);
    }

    protected AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm(T element, LfBus bus, VariableSet<AcVariableType> variableSet) {
        this(element, bus, variableSet, Collections.emptyList());
    }

    protected double e() {
        return sv.get(eVar.getRow());
    }

    protected double f() {
        return sv.get(fVar.getRow());
    }

    protected double v() {
        return FastMath.sqrt(e() * e() + f() * f());
    }

    /**
     * Value of the term as a function of the voltage magnitude.
     */
    protected abstract double f(double v);

    /**
     * Derivative of the term with respect to the voltage magnitude.
     */
    protected abstract double dfdv(double v);

    /**
     * Derivative with respect to a variable which is not the bus voltage one.
     */
    protected double derOther(Variable<AcVariableType> variable) {
        throw new IllegalStateException("Unknown variable: " + variable);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        return f(v());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(eVar) || variable.equals(fVar)) {
            double v = v();
            if (v == 0) {
                return 0;
            }
            // dv/de = e / v and dv/df = f / v
            return dfdv(v) * (variable.equals(eVar) ? e() : f()) / v;
        }
        return derOther(variable);
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return CartesianEquationTermUtil.calculateSensi(this, dx, column);
    }
}
