/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.google.common.base.Stopwatch;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import net.jafama.DoubleWrapper;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * Vectorized view of the network and variables of the equation system.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcNetworkVector extends AbstractLfNetworkListener
        implements EquationSystemIndexListener<AcVariableType, AcEquationType>, StateVectorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcNetworkVector.class);

    private final LfNetwork network;
    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;
    private final AcBusVector busVector;
    private final AcBranchVector branchVector;
    private boolean variablesInvalid = true;

    public AcNetworkVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                           AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        busVector = new AcBusVector(network.getBuses());
        branchVector = new AcBranchVector(network.getBranches(), creationParameters);
    }

    public AcBusVector getBusVector() {
        return busVector;
    }

    public AcBranchVector getBranchVector() {
        return branchVector;
    }

    public void startListening() {
        // listen for branch disabling status update
        network.addListener(this);

        // update vectorized variables and then listen for variables change in equation system
        updateVariables();
        equationSystem.getIndex().addListener(this);

        // listen for state vector change to update vectorized network
        equationSystem.getStateVector().addListener(this);
    }

    /**
     * Update vectorized view of the variables from the equation system.
     */
    public void updateVariables() {
        if (!variablesInvalid) {
            return;
        }

        Stopwatch stopwatch = Stopwatch.createStarted();

        Arrays.fill(busVector.vRow, -1);
        Arrays.fill(busVector.phRow, -1);
        Arrays.fill(branchVector.a1Row, -1);
        Arrays.fill(branchVector.r1Row, -1);
        Arrays.fill(branchVector.v1Row, -1);
        Arrays.fill(branchVector.ph1Row, -1);
        Arrays.fill(branchVector.v2Row, -1);
        Arrays.fill(branchVector.ph2Row, -1);

        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            int num = v.getElementNum();
            int row = v.getRow();
            switch (v.getType()) {
                case BUS_V, BUS_E:
                    busVector.vRow[num] = row;
                    break;

                case BUS_PHI, BUS_F:
                    busVector.phRow[num] = row;
                    break;

                case BRANCH_ALPHA1:
                    branchVector.a1Row[num] = branchVector.deriveA1[num] ? row : -1;
                    break;

                case BRANCH_RHO1:
                    branchVector.r1Row[num] = branchVector.deriveR1[num] ? row : -1;
                    break;

                default:
                    break;
            }
        }

        copyVariablesToBranches();

        stopwatch.stop();
        LOGGER.debug("AC variable vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));

        variablesInvalid = false;
    }

    public void copyVariablesToBranches() {
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (branchVector.bus1Num[branchNum] != -1) {
                branchVector.v1Row[branchNum] = busVector.vRow[branchVector.bus1Num[branchNum]];
                branchVector.ph1Row[branchNum] = busVector.phRow[branchVector.bus1Num[branchNum]];
            }
            if (branchVector.bus2Num[branchNum] != -1) {
                branchVector.v2Row[branchNum] = busVector.vRow[branchVector.bus2Num[branchNum]];
                branchVector.ph2Row[branchNum] = busVector.phRow[branchVector.bus2Num[branchNum]];
            }
        }
    }

    private boolean isBranchConnectedSide1(int branchNum) {
        return branchVector.bus1Num[branchNum] != -1 && branchVector.connected1[branchNum];
    }

    private boolean isBranchConnectedSide2(int branchNum) {
        return branchVector.bus2Num[branchNum] != -1 && branchVector.connected2[branchNum];
    }

    public static double theta1(double ksi, double ph1, double a1, double ph2) {
        return ksi - a1 + A2 - ph1 + ph2;
    }

    public static double theta2(double ksi, double ph1, double a1, double ph2) {
        return ksi + a1 - A2 + ph1 - ph2;
    }

    public void updateBuses(double[] state) {
        for (int busNum = 0; busNum < busVector.v.length; busNum++) {
            if (!busVector.disabled[busNum]) {
                if (busVector.vRow[busNum] != -1) {
                    busVector.v[busNum] = state[busVector.vRow[busNum]];
                }
                if (busVector.phRow[busNum] != -1) {
                    busVector.ph[busNum] = state[busVector.phRow[busNum]];
                }
            }
        }
    }

    /**
     * Update all power flows and their derivatives.
     */
    public void updateClosedBranches(double[] state) {
        var w = new DoubleWrapper();

        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {

            if (!branchVector.disabled[branchNum]) {

                branchVector.r1State[branchNum] = branchVector.r1Row[branchNum] != -1 ? state[branchVector.r1Row[branchNum]]
                        : branchVector.r1[branchNum];
                branchVector.a1State[branchNum] = branchVector.a1Row[branchNum] != -1 ? state[branchVector.a1Row[branchNum]]
                        : branchVector.a1[branchNum];

                if (isBranchConnectedSide1(branchNum) && isBranchConnectedSide2(branchNum)) {
                    if (branchVector.cartesian) {
                        updateCartesianClosedBranch(branchNum, state, w);
                        continue;
                    }
                    double ph1 = state[branchVector.ph1Row[branchNum]];
                    double ph2 = state[branchVector.ph2Row[branchNum]];
                    double a1 = branchVector.a1State[branchNum];

                    double theta1 = theta1(
                            branchVector.ksi[branchNum],
                            ph1,
                            a1,
                            ph2);
                    double theta2 = theta2(
                            branchVector.ksi[branchNum],
                            ph1,
                            a1,
                            ph2);
                    double sinTheta1 = FastMath.sinAndCos(theta1, w);
                    double cosTheta1 = w.value;
                    double sinTheta2 = FastMath.sinAndCos(theta2, w);
                    double cosTheta2 = w.value;

                    double v1 = state[branchVector.v1Row[branchNum]];
                    double v2 = state[branchVector.v2Row[branchNum]];
                    double r1 = branchVector.r1State[branchNum];

                    // p1
                    updateP1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                    // q1
                    updateQ1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                    // i1
                    branchVector.i1[branchNum] = FastMath.hypot(branchVector.p1[branchNum], branchVector.q1[branchNum]) / v1;

                    // p2
                    updateP2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                    // q2
                    updateQ2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                    // i2
                    branchVector.i2[branchNum] = FastMath.hypot(branchVector.p2[branchNum], branchVector.q2[branchNum]) / v2;
                }
            }
        }
    }

    /**
     * Update power flows and their derivatives of a closed branch, bus voltages being in cartesian coordinates.
     * With V1' = r1.exp(j.a1).V1 = u1 + j.w1 the voltage after the ideal transformer and y12 = g12 + j.b12 the series
     * admittance:
     * I1' = (g1 + j.b1 + y12).V1' - y12.V2, S1 = V1'.conj(I1')
     * I2 = (g2 + j.b2 + y12).V2 - y12.V1', S2 = V2.conj(I2)
     */
    private void updateCartesianClosedBranch(int branchNum, double[] state, DoubleWrapper w) {
        double e1 = state[branchVector.v1Row[branchNum]];
        double f1 = state[branchVector.ph1Row[branchNum]];
        double e2 = state[branchVector.v2Row[branchNum]];
        double f2 = state[branchVector.ph2Row[branchNum]];
        double r1 = branchVector.r1State[branchNum];
        double a1 = branchVector.a1State[branchNum];

        double y = branchVector.y[branchNum];
        double g12 = y * branchVector.sinKsi[branchNum];
        double b12 = -y * branchVector.cosKsi[branchNum];

        double cosA1;
        double sinA1;
        if (a1 == 0) {
            cosA1 = 1;
            sinA1 = 0;
        } else {
            sinA1 = FastMath.sinAndCos(a1, w);
            cosA1 = w.value;
        }

        double u1o = cosA1 * e1 - sinA1 * f1;
        double w1o = sinA1 * e1 + cosA1 * f1;
        double u1 = r1 * u1o;
        double w1 = r1 * w1o;

        // side 1
        double ga1 = branchVector.g1[branchNum] + g12;
        double ba1 = branchVector.b1[branchNum] + b12;
        double ir1 = ga1 * u1 - ba1 * w1 - g12 * e2 + b12 * f2;
        double ii1 = ga1 * w1 + ba1 * u1 - g12 * f2 - b12 * e2;
        double p1 = u1 * ir1 + w1 * ii1;
        double q1 = w1 * ir1 - u1 * ii1;
        double dp1du1 = ir1 + u1 * ga1 + w1 * ba1;
        double dp1dw1 = ii1 - u1 * ba1 + w1 * ga1;
        double dq1du1 = -ii1 + w1 * ga1 - u1 * ba1;
        double dq1dw1 = ir1 - w1 * ba1 - u1 * ga1;

        // side 2
        double ga2 = branchVector.g2[branchNum] + g12;
        double ba2 = branchVector.b2[branchNum] + b12;
        double ir2 = ga2 * e2 - ba2 * f2 - g12 * u1 + b12 * w1;
        double ii2 = ga2 * f2 + ba2 * e2 - g12 * w1 - b12 * u1;
        double p2 = e2 * ir2 + f2 * ii2;
        double q2 = f2 * ir2 - e2 * ii2;
        double dp2du1 = -e2 * g12 - f2 * b12;
        double dp2dw1 = e2 * b12 - f2 * g12;
        double dq2du1 = -f2 * g12 + e2 * b12;
        double dq2dw1 = f2 * b12 + e2 * g12;

        branchVector.p1[branchNum] = p1;
        branchVector.q1[branchNum] = q1;
        branchVector.p2[branchNum] = p2;
        branchVector.q2[branchNum] = q2;
        branchVector.i1[branchNum] = FastMath.hypot(p1, q1) / FastMath.sqrt(e1 * e1 + f1 * f1);
        branchVector.i2[branchNum] = FastMath.hypot(p2, q2) / FastMath.sqrt(e2 * e2 + f2 * f2);

        // derivatives with respect to e1, f1, a1 and r1 through V1' (chain rule)
        double rc = r1 * cosA1;
        double rs = r1 * sinA1;

        branchVector.dp1dv1[branchNum] = rc * dp1du1 + rs * dp1dw1;
        branchVector.dp1dph1[branchNum] = -rs * dp1du1 + rc * dp1dw1;
        branchVector.dp1dv2[branchNum] = -u1 * g12 - w1 * b12;
        branchVector.dp1dph2[branchNum] = u1 * b12 - w1 * g12;
        branchVector.dp1da1[branchNum] = -w1 * dp1du1 + u1 * dp1dw1;
        branchVector.dp1dr1[branchNum] = u1o * dp1du1 + w1o * dp1dw1;

        branchVector.dq1dv1[branchNum] = rc * dq1du1 + rs * dq1dw1;
        branchVector.dq1dph1[branchNum] = -rs * dq1du1 + rc * dq1dw1;
        branchVector.dq1dv2[branchNum] = -w1 * g12 + u1 * b12;
        branchVector.dq1dph2[branchNum] = w1 * b12 + u1 * g12;
        branchVector.dq1da1[branchNum] = -w1 * dq1du1 + u1 * dq1dw1;
        branchVector.dq1dr1[branchNum] = u1o * dq1du1 + w1o * dq1dw1;

        branchVector.dp2dv1[branchNum] = rc * dp2du1 + rs * dp2dw1;
        branchVector.dp2dph1[branchNum] = -rs * dp2du1 + rc * dp2dw1;
        branchVector.dp2dv2[branchNum] = ir2 + e2 * ga2 + f2 * ba2;
        branchVector.dp2dph2[branchNum] = ii2 - e2 * ba2 + f2 * ga2;
        branchVector.dp2da1[branchNum] = -w1 * dp2du1 + u1 * dp2dw1;
        branchVector.dp2dr1[branchNum] = u1o * dp2du1 + w1o * dp2dw1;

        branchVector.dq2dv1[branchNum] = rc * dq2du1 + rs * dq2dw1;
        branchVector.dq2dph1[branchNum] = -rs * dq2du1 + rc * dq2dw1;
        branchVector.dq2dv2[branchNum] = -ii2 + f2 * ga2 - e2 * ba2;
        branchVector.dq2dph2[branchNum] = ir2 - f2 * ba2 - e2 * ga2;
        branchVector.dq2da1[branchNum] = -w1 * dq2du1 + u1 * dq2dw1;
        branchVector.dq2dr1[branchNum] = u1o * dq2du1 + w1o * dq2dw1;
    }

    public void updateNetworkState() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] state = equationSystem.getStateVector().get();
        updateBuses(state);
        updateClosedBranches(state);
        stopwatch.stop();
        LOGGER.debug("AC network vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        if (element.getType() == ElementType.BUS) {
            busVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.BRANCH) {
            branchVector.disabled[element.getNum()] = disabled;
        }
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        if (side == TwoSides.ONE) {
            branchVector.connected1[branch.getNum()] = connected;
        } else {
            branchVector.connected2[branch.getNum()] = connected;
        }
    }

    @Override
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
        PiModel piModel = branch.getPiModel();

        double y = piModel.getY();
        branchVector.y[branch.getNum()] = y;
        double ksi = piModel.getKsi();
        var w = new DoubleWrapper();
        branchVector.ksi[branch.getNum()] = ksi;
        branchVector.sinKsi[branch.getNum()] = FastMath.sinAndCos(ksi, w);
        branchVector.cosKsi[branch.getNum()] = w.value;
        branchVector.b1[branch.getNum()] = piModel.getB1();
        branchVector.b2[branch.getNum()] = piModel.getB2();
        branchVector.g1[branch.getNum()] = piModel.getG1();
        branchVector.g2[branch.getNum()] = piModel.getG2();
        branchVector.a1[branch.getNum()] = piModel.getA1();
        branchVector.r1[branch.getNum()] = piModel.getR1();
    }

    @Override
    public void onShuntSusceptanceChange(LfShunt shunt, double b) {
        // do nothing
    }

    @Override
    public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
        // do nothing
    }

    @Override
    public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
        // do nothing
    }

    @Override
    public void onVariableChange(Variable<AcVariableType> variable, ChangeType changeType) {
        variablesInvalid = true;
    }

    @Override
    public void onEquationChange(SingleEquation<AcVariableType, AcEquationType> equation, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<AcVariableType, AcEquationType> term) {
        // nothing to do
    }

    @Override
    public void onEquationArrayChange(EquationArray<AcVariableType, AcEquationType> equationArray, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<AcVariableType, AcEquationType> equationTermArray, int termNum, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationIndexOrderChanged() {
        // nothing to do
    }

    @Override
    public void onStateUpdate() {
        updateVariables();
        updateNetworkState();
    }

    private void updateP1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1) {
        branchVector.p1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.p1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dp1dv1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dp1dv2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv2(
            branchVector.y[branchNum],
            v1,
            r1,
            sinTheta1);

        branchVector.dp1dph1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1dph2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1da1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1dr1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dr1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);
    }

    private void updateQ1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1) {
        branchVector.q1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.q1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);
        branchVector.dq1dv1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dq1dv2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv2(
            branchVector.y[branchNum],
            v1,
            r1,
            cosTheta1);

        branchVector.dq1dph1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1dph2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1da1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1dr1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dr1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);
    }

    private void updateP2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2) {
        branchVector.p2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.p2(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g2[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dp2dv1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv1(
            branchVector.y[branchNum],
            r1,
            v2,
            sinTheta2);

        branchVector.dp2dv2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv2(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g2[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dp2dph1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2dph2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2da1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2dr1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dr1(
            branchVector.y[branchNum],
            v1,
            v2,
            sinTheta2);
    }

    private void updateQ2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2) {
        branchVector.q2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.q2(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b2[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dq2dv1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv1(
            branchVector.y[branchNum],
            r1,
            v2,
            cosTheta2);

        branchVector.dq2dv2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv2(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b2[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dq2dph1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2dph2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2da1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2dr1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dr1(
            branchVector.y[branchNum],
            v1,
            v2,
            cosTheta2);
    }
}
