/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.AbstractClosedBranchAcFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide1ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide1ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide2ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.SimplePiModel;
import net.jafama.FastMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class ClosedBranchCartesianEquationTermTest {

    private static final double V1 = 1.03;
    private static final double PH1 = 0.12;
    private static final double V2 = 0.97;
    private static final double PH2 = -0.08;
    private static final double R1 = 1.05;
    private static final double A1 = 0.07;

    private final PiModel piModel = new SimplePiModel()
            .setR(0.01)
            .setX(0.1)
            .setG1(0.002)
            .setB1(0.03)
            .setG2(0.001)
            .setB2(0.025)
            .setR1(R1)
            .setA1(A1);

    private double[] calculate(TwoSides side, ClosedBranchCartesianEquationTerm.Type type, double[] x) {
        return ClosedBranchCartesianEquationTerm.calculate(side, type, piModel, x[0], x[1], x[2], x[3], x[4], x[5]);
    }

    private static double[] state() {
        return new double[] {V1 * FastMath.cos(PH1), V1 * FastMath.sin(PH1), V2 * FastMath.cos(PH2), V2 * FastMath.sin(PH2), A1, R1};
    }

    @Test
    void testConsistencyWithPolarFormulation() {
        double[] x = state();
        double y = piModel.getY();
        double ksi = piModel.getKsi();
        double theta1 = AbstractClosedBranchAcFlowEquationTerm.theta1(ksi, PH1, A1, PH2);
        double theta2 = AbstractClosedBranchAcFlowEquationTerm.theta2(ksi, PH1, A1, PH2);
        double p1 = ClosedBranchSide1ActiveFlowEquationTerm.p1(y, FastMath.sin(ksi), piModel.getG1(), V1, R1, V2, FastMath.sin(theta1));
        double q1 = ClosedBranchSide1ReactiveFlowEquationTerm.q1(y, FastMath.cos(ksi), piModel.getB1(), V1, R1, V2, FastMath.cos(theta1));
        double p2 = ClosedBranchSide2ActiveFlowEquationTerm.p2(y, FastMath.sin(ksi), piModel.getG2(), V1, R1, V2, FastMath.sin(theta2));
        double q2 = ClosedBranchSide2ReactiveFlowEquationTerm.q2(y, FastMath.cos(ksi), piModel.getB2(), V1, R1, V2, FastMath.cos(theta2));

        assertEquals(p1, calculate(TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.ACTIVE_POWER, x)[0], 1e-12);
        assertEquals(q1, calculate(TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.REACTIVE_POWER, x)[0], 1e-12);
        assertEquals(p2, calculate(TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.ACTIVE_POWER, x)[0], 1e-12);
        assertEquals(q2, calculate(TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.REACTIVE_POWER, x)[0], 1e-12);

        // |I| = |S| / |V|
        assertEquals(FastMath.hypot(p1, q1) / V1, calculate(TwoSides.ONE, ClosedBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE, x)[0], 1e-12);
        assertEquals(FastMath.hypot(p2, q2) / V2, calculate(TwoSides.TWO, ClosedBranchCartesianEquationTerm.Type.CURRENT_MAGNITUDE, x)[0], 1e-12);
    }

    @Test
    void testDerivatives() {
        double h = 1e-7;
        for (TwoSides side : TwoSides.values()) {
            for (ClosedBranchCartesianEquationTerm.Type type : ClosedBranchCartesianEquationTerm.Type.values()) {
                double[] x = state();
                double[] result = calculate(side, type, x);
                for (int i = 0; i < x.length; i++) {
                    double[] xPlus = x.clone();
                    xPlus[i] += h;
                    double[] xMinus = x.clone();
                    xMinus[i] -= h;
                    double numericalDer = (calculate(side, type, xPlus)[0] - calculate(side, type, xMinus)[0]) / (2 * h);
                    assertEquals(numericalDer, result[i + 1], 1e-6, "Derivative " + i + " of " + type + " at side " + side);
                }
            }
        }
    }
}
