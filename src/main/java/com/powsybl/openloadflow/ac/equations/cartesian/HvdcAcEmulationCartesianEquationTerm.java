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
import com.powsybl.openloadflow.ac.equations.HvdcAcEmulationSide1ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.HvdcAcEmulationSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfHvdc;

import java.util.List;
import java.util.Objects;

/**
 * HVDC AC emulation active power, the voltage angles being computed from cartesian voltages: phi = atan2(f, e).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class HvdcAcEmulationCartesianEquationTerm extends AbstractElementEquationTerm<LfHvdc, AcVariableType, AcEquationType> {

    private final TwoSides side;

    private final Variable<AcVariableType> e1Var;

    private final Variable<AcVariableType> f1Var;

    private final Variable<AcVariableType> e2Var;

    private final Variable<AcVariableType> f2Var;

    private final List<Variable<AcVariableType>> variables;

    private final double k;

    private final double p0;

    private final double r;

    private final double lossFactor1;

    private final double lossFactor2;

    public HvdcAcEmulationCartesianEquationTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet, TwoSides side) {
        super(hvdc);
        this.side = Objects.requireNonNull(side);
        e1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_E);
        f1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_F);
        e2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_E);
        f2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_F);
        variables = List.of(e1Var, f1Var, e2Var, f2Var);
        k = hvdc.getAcEmulationControl().getDroop() * 180 / Math.PI;
        p0 = hvdc.getAcEmulationControl().getP0();
        r = hvdc.getR();
        lossFactor1 = hvdc.getConverterStation1().getLossFactor() / 100;
        lossFactor2 = hvdc.getConverterStation2().getLossFactor() / 100;
    }

    private double e1() {
        return sv.get(e1Var.getRow());
    }

    private double f1() {
        return sv.get(f1Var.getRow());
    }

    private double e2() {
        return sv.get(e2Var.getRow());
    }

    private double f2() {
        return sv.get(f2Var.getRow());
    }

    private double ph1() {
        return BusVoltageAngleCartesianEquationTerm.phi(e1(), f1());
    }

    private double ph2() {
        return BusVoltageAngleCartesianEquationTerm.phi(e2(), f2());
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        return side == TwoSides.ONE
                ? HvdcAcEmulationSide1ActiveFlowEquationTerm.p1(p0, k, lossFactor1, lossFactor2, r, ph1(), ph2())
                : HvdcAcEmulationSide2ActiveFlowEquationTerm.p2(p0, k, lossFactor1, lossFactor2, r, ph1(), ph2());
    }

    private double dpdph1() {
        return side == TwoSides.ONE
                ? HvdcAcEmulationSide1ActiveFlowEquationTerm.dp1dph1(p0, k, lossFactor1, lossFactor2, ph1(), ph2())
                : HvdcAcEmulationSide2ActiveFlowEquationTerm.dp2dph1(p0, k, lossFactor1, lossFactor2, ph1(), ph2());
    }

    private double dpdph2() {
        return side == TwoSides.ONE
                ? HvdcAcEmulationSide1ActiveFlowEquationTerm.dp1dph2(p0, k, lossFactor1, lossFactor2, ph1(), ph2())
                : HvdcAcEmulationSide2ActiveFlowEquationTerm.dp2dph2(p0, k, lossFactor1, lossFactor2, ph1(), ph2());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(e1Var)) {
            return dpdph1() * BusVoltageAngleCartesianEquationTerm.dphide(e1(), f1());
        } else if (variable.equals(f1Var)) {
            return dpdph1() * BusVoltageAngleCartesianEquationTerm.dphidf(e1(), f1());
        } else if (variable.equals(e2Var)) {
            return dpdph2() * BusVoltageAngleCartesianEquationTerm.dphide(e2(), f2());
        } else if (variable.equals(f2Var)) {
            return dpdph2() * BusVoltageAngleCartesianEquationTerm.dphidf(e2(), f2());
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
        return side == TwoSides.ONE ? "ac_emulation_p_1_cartesian" : "ac_emulation_p_2_cartesian";
    }
}
