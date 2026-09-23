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
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
final class CartesianEquationTermUtil {

    private CartesianEquationTermUtil() {
    }

    /**
     * Generic first order sensitivity: sum of the term partial derivatives multiplied by the state variation.
     */
    static double calculateSensi(SingleEquationTerm<AcVariableType, AcEquationType> term, DenseMatrix dx, int column) {
        double sensi = 0;
        for (Variable<AcVariableType> variable : term.getVariables()) {
            int row = variable.getRow();
            if (row >= 0) {
                sensi += term.der(variable) * dx.get(row, column);
            }
        }
        return sensi;
    }
}
