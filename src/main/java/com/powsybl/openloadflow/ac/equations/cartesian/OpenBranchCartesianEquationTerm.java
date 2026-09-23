/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModel;
import net.jafama.FastMath;

import java.util.Objects;

/**
 * Active power, reactive power or current magnitude at the connected side of a branch open at the other side.
 * Seen from the connected bus, the branch is an equivalent shunt admittance, so that p = kp.v^2, q = kq.v^2
 * and i = sqrt(kp^2 + kq^2).v with v = sqrt(e^2 + f^2).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenBranchCartesianEquationTerm extends AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<LfBranch> {

    public enum Type {
        ACTIVE_POWER,
        REACTIVE_POWER,
        CURRENT_MAGNITUDE,
    }

    private final TwoSides openSide;

    private final Type type;

    /**
     * @param openSide the open side of the branch, the term is evaluated at the other side (connected to the bus)
     */
    public OpenBranchCartesianEquationTerm(LfBranch branch, LfBus bus, VariableSet<AcVariableType> variableSet, TwoSides openSide, Type type) {
        super(branch, bus, variableSet);
        this.openSide = Objects.requireNonNull(openSide);
        this.type = Objects.requireNonNull(type);
    }

    private double kp() {
        PiModel piModel = element.getPiModel();
        double y = piModel.getY();
        double ksi = piModel.getKsi();
        double cosKsi = FastMath.cos(ksi);
        double sinKsi = FastMath.sin(ksi);
        return openSide == TwoSides.TWO
                ? OpenBranchSide2ActiveFlowEquationTerm.p1(y, cosKsi, sinKsi, piModel.getG1(), piModel.getG2(), piModel.getB2(), 1, piModel.getR1())
                : OpenBranchSide1ActiveFlowEquationTerm.p2(y, cosKsi, sinKsi, piModel.getG1(), piModel.getB1(), piModel.getG2(), 1);
    }

    private double kq() {
        PiModel piModel = element.getPiModel();
        double y = piModel.getY();
        double ksi = piModel.getKsi();
        double cosKsi = FastMath.cos(ksi);
        double sinKsi = FastMath.sin(ksi);
        return openSide == TwoSides.TWO
                ? OpenBranchSide2ReactiveFlowEquationTerm.q1(y, cosKsi, sinKsi, piModel.getB1(), piModel.getG2(), piModel.getB2(), 1, piModel.getR1())
                : OpenBranchSide1ReactiveFlowEquationTerm.q2(y, cosKsi, sinKsi, piModel.getG1(), piModel.getB1(), piModel.getB2(), 1);
    }

    @Override
    protected double f(double v) {
        return switch (type) {
            case ACTIVE_POWER -> kp() * v * v;
            case REACTIVE_POWER -> kq() * v * v;
            case CURRENT_MAGNITUDE -> FastMath.hypot(kp(), kq()) * v;
        };
    }

    @Override
    protected double dfdv(double v) {
        return switch (type) {
            case ACTIVE_POWER -> 2 * kp() * v;
            case REACTIVE_POWER -> 2 * kq() * v;
            case CURRENT_MAGNITUDE -> FastMath.hypot(kp(), kq());
        };
    }

    @Override
    protected String getName() {
        String prefix = switch (type) {
            case ACTIVE_POWER -> "ac_p_open_";
            case REACTIVE_POWER -> "ac_q_open_";
            case CURRENT_MAGNITUDE -> "ac_i_open_";
        };
        return prefix + (openSide == TwoSides.ONE ? "1" : "2") + "_cartesian";
    }
}
