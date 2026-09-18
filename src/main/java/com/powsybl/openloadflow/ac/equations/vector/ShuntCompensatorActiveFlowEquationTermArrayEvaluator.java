/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorActiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ShuntCompensatorActiveFlowEquationTermArrayEvaluator extends AbstractShuntCompensatorEquationTermArrayEvaluator {

    public ShuntCompensatorActiveFlowEquationTermArrayEvaluator(AcShuntVector shuntVector, AcBusVector busVector,
                                                                VariableSet<AcVariableType> variableSet) {
        super(shuntVector, busVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_p_shunt_array";
    }

    @Override
    public double calculateSensi(int shuntNum, DenseMatrix dx, int column) {
        Objects.requireNonNull(dx);
        double dv = dx.get(shuntVector.vRow[shuntNum], column);
        return ShuntCompensatorActiveFlowEquationTerm.dpdv(v(shuntNum), g(shuntNum)) * dv;
    }

    @Override
    public double[] eval() {
        return shuntVector.p;
    }

    @Override
    public double eval(int shuntNum) {
        return shuntVector.p[shuntNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            shuntVector.dpdv
        };
    }

    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int shuntNum) {
        return List.of(new Derivative<>(getVVar(shuntNum), 0));
    }
}
