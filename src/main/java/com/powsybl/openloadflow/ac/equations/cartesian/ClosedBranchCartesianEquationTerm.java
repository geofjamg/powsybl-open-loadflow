/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModel;
import net.jafama.FastMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Active power, reactive power or current magnitude flowing at one side of a closed branch, with bus voltages
 * expressed in cartesian coordinates: V1 = e1 + j.f1 and V2 = e2 + j.f2.
 * <p>
 * The branch is modeled by an ideal transformer (ratio r1 and phase shift a1) at side 1, followed by a PI model.
 * With V1' = r1.exp(j.a1).V1 the voltage after the ideal transformer, y12 = g12 + j.b12 = 1 / (r + j.x) the series
 * admittance:
 * <ul>
 *     <li>I1' = (g1 + j.b1 + y12).V1' - y12.V2, S1 = V1'.conj(I1') and |I1| = r1.|I1'|</li>
 *     <li>I2 = (g2 + j.b2 + y12).V2 - y12.V1', S2 = V2.conj(I2)</li>
 * </ul>
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ClosedBranchCartesianEquationTerm extends AbstractElementEquationTerm<LfBranch, AcVariableType, AcEquationType> {

    public enum Type {
        ACTIVE_POWER,
        REACTIVE_POWER,
        CURRENT_MAGNITUDE,
    }

    private static final int VALUE = 0;
    private static final int DE1 = 1;
    private static final int DF1 = 2;
    private static final int DE2 = 3;
    private static final int DF2 = 4;
    private static final int DA1 = 5;
    private static final int DR1 = 6;

    private final TwoSides side;

    private final Type type;

    private final Variable<AcVariableType> e1Var;

    private final Variable<AcVariableType> f1Var;

    private final Variable<AcVariableType> e2Var;

    private final Variable<AcVariableType> f2Var;

    private final Variable<AcVariableType> a1Var;

    private final Variable<AcVariableType> r1Var;

    private final List<Variable<AcVariableType>> variables;

    public ClosedBranchCartesianEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                             boolean deriveA1, boolean deriveR1, TwoSides side, Type type) {
        super(branch);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        Objects.requireNonNull(variableSet);
        this.side = Objects.requireNonNull(side);
        this.type = Objects.requireNonNull(type);
        PiModel piModel = branch.getPiModel();
        if (piModel.getR() == 0 && piModel.getX() == 0) {
            throw new IllegalArgumentException("Non impedant branch not supported: " + branch.getId());
        }
        e1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_E);
        f1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_F);
        e2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_E);
        f2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_F);
        a1Var = deriveA1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_ALPHA1) : null;
        r1Var = deriveR1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_RHO1) : null;
        List<Variable<AcVariableType>> allVariables = new ArrayList<>(6);
        allVariables.add(e1Var);
        allVariables.add(f1Var);
        allVariables.add(e2Var);
        allVariables.add(f2Var);
        if (a1Var != null) {
            allVariables.add(a1Var);
        }
        if (r1Var != null) {
            allVariables.add(r1Var);
        }
        variables = Collections.unmodifiableList(allVariables);
    }

    public TwoSides getSide() {
        return side;
    }

    public Type getType() {
        return type;
    }

    private double r1() {
        return r1Var != null ? sv.get(r1Var.getRow()) : element.getPiModel().getR1();
    }

    private double a1() {
        return a1Var != null ? sv.get(a1Var.getRow()) : element.getPiModel().getA1();
    }

    /**
     * Compute the term value and its partial derivatives with respect to e1, f1, e2, f2, a1 and r1.
     */
    @SuppressWarnings("squid:S3776")
    public static double[] calculate(TwoSides side, Type type, PiModel piModel,
                                     double e1, double f1, double e2, double f2, double a1, double r1) {
        double y = piModel.getY();
        double ksi = piModel.getKsi();
        // y12 = g12 + j.b12 = 1 / (r + j.x)
        double g12 = y * FastMath.sin(ksi);
        double b12 = -y * FastMath.cos(ksi);
        double cosA1 = FastMath.cos(a1);
        double sinA1 = FastMath.sin(a1);

        // V1' = r1.exp(j.a1).V1 = r1.(u1o + j.w1o)
        double u1o = cosA1 * e1 - sinA1 * f1;
        double w1o = sinA1 * e1 + cosA1 * f1;
        double u1 = r1 * u1o;
        double w1 = r1 * w1o;

        // generic formulation: I = (ga + j.ba).Va - y12.Vb and S = Va.conj(I), a being the evaluated side
        double ua;
        double wa;
        double ub;
        double wb;
        double ga;
        double ba;
        if (side == TwoSides.ONE) {
            ua = u1;
            wa = w1;
            ub = e2;
            wb = f2;
            ga = piModel.getG1() + g12;
            ba = piModel.getB1() + b12;
        } else {
            ua = e2;
            wa = f2;
            ub = u1;
            wb = w1;
            ga = piModel.getG2() + g12;
            ba = piModel.getB2() + b12;
        }
        double ir = ga * ua - ba * wa - g12 * ub + b12 * wb;
        double ii = ga * wa + ba * ua - g12 * wb - b12 * ub;
        // derivatives of ir and ii with respect to ua, wa, ub, wb
        double[] dir = {ga, -ba, -g12, b12};
        double[] dii = {ba, ga, -b12, -g12};

        double value;
        double[] d = new double[4];
        switch (type) {
            case ACTIVE_POWER -> {
                // p = ua.ir + wa.ii
                value = ua * ir + wa * ii;
                for (int k = 0; k < 4; k++) {
                    d[k] = ua * dir[k] + wa * dii[k];
                }
                d[0] += ir;
                d[1] += ii;
            }
            case REACTIVE_POWER -> {
                // q = wa.ir - ua.ii
                value = wa * ir - ua * ii;
                for (int k = 0; k < 4; k++) {
                    d[k] = wa * dir[k] - ua * dii[k];
                }
                d[0] -= ii;
                d[1] += ir;
            }
            case CURRENT_MAGNITUDE -> {
                value = FastMath.sqrt(ir * ir + ii * ii);
                if (value > 0) {
                    for (int k = 0; k < 4; k++) {
                        d[k] = (ir * dir[k] + ii * dii[k]) / value;
                    }
                }
            }
            default -> throw new IllegalStateException("Unknown type: " + type);
        }

        // back to V1' and V2 derivatives
        double du1;
        double dw1;
        double de2;
        double df2;
        if (side == TwoSides.ONE) {
            du1 = d[0];
            dw1 = d[1];
            de2 = d[2];
            df2 = d[3];
        } else {
            de2 = d[0];
            df2 = d[1];
            du1 = d[2];
            dw1 = d[3];
        }

        // chain rule for V1' = r1.exp(j.a1).V1
        double de1 = r1 * (cosA1 * du1 + sinA1 * dw1);
        double df1 = r1 * (-sinA1 * du1 + cosA1 * dw1);
        double da1 = -w1 * du1 + u1 * dw1;
        double dr1 = u1o * du1 + w1o * dw1;

        if (type == Type.CURRENT_MAGNITUDE && side == TwoSides.ONE) {
            // |I1| = r1.|I1'|
            dr1 = value + r1 * dr1;
            de1 *= r1;
            df1 *= r1;
            de2 *= r1;
            df2 *= r1;
            da1 *= r1;
            value *= r1;
        }

        return new double[] {value, de1, df1, de2, df2, da1, dr1};
    }

    private double[] calculate() {
        return calculate(side, type, element.getPiModel(),
                sv.get(e1Var.getRow()), sv.get(f1Var.getRow()), sv.get(e2Var.getRow()), sv.get(f2Var.getRow()),
                a1(), r1());
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        return calculate()[VALUE];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        int index;
        if (variable.equals(e1Var)) {
            index = DE1;
        } else if (variable.equals(f1Var)) {
            index = DF1;
        } else if (variable.equals(e2Var)) {
            index = DE2;
        } else if (variable.equals(f2Var)) {
            index = DF2;
        } else if (variable.equals(a1Var)) {
            index = DA1;
        } else if (variable.equals(r1Var)) {
            index = DR1;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
        return calculate()[index];
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return CartesianEquationTermUtil.calculateSensi(this, dx, column);
    }

    @Override
    protected String getName() {
        String prefix = switch (type) {
            case ACTIVE_POWER -> "ac_p_closed_";
            case REACTIVE_POWER -> "ac_q_closed_";
            case CURRENT_MAGNITUDE -> "ac_i_closed_";
        };
        return prefix + (side == TwoSides.ONE ? "1" : "2") + "_cartesian";
    }
}
