/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreator;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.util.Evaluable;
import net.jafama.FastMath;

import java.util.List;

/**
 * Vectorized view of the branches and variables related to branches.
 * Are included all power flows and theirs partial derivatives.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcBranchVector {

    final int[] bus1Num;
    final int[] bus2Num;

    final boolean[] connected1;
    final boolean[] connected2;

    final double[] y;
    final double[] ksi;
    final double[] cosKsi;
    final double[] sinKsi;
    final double[] g1;
    final double[] g2;
    final double[] b1;
    final double[] b2;
    final double[] r1;
    final double[] a1;

    final boolean[] disabled;

    final boolean[] deriveA1;
    final boolean[] deriveR1;

    public final int[] v1Row;
    public final int[] v2Row;
    public final int[] ph1Row;
    public final int[] ph2Row;

    public final int[] a1Row;
    public final int[] r1Row;

    final double[] r1State;
    final double[] a1State;

    final double[] p1;
    final double[] p2;
    final double[] q1;
    final double[] q2;
    final double[] i1;
    final double[] i2;
    // currents are computed lazily (only needed for results, not by the Newton-Raphson): voltages at last update
    // and validity of the currents
    final double[] v1State;
    final double[] v2State;
    final boolean[] i1Valid;
    final boolean[] i2Valid;

    final double[] dp1dv1;
    final double[] dp1dv2;
    final double[] dp1dph1;
    final double[] dp1dph2;
    final double[] dp1da1;
    final double[] dp1dr1;

    final double[] dq1dv1;
    final double[] dq1dv2;
    final double[] dq1dph1;
    final double[] dq1dph2;
    final double[] dq1da1;
    final double[] dq1dr1;

    final double[] dp2dv1;
    final double[] dp2dv2;
    final double[] dp2dph1;
    final double[] dp2dph2;
    final double[] dp2da1;
    final double[] dp2dr1;

    final double[] dq2dv1;
    final double[] dq2dv2;
    final double[] dq2dph1;
    final double[] dq2dph2;
    final double[] dq2da1;
    final double[] dq2dr1;

    public AcBranchVector(List<LfBranch> branches, AcEquationSystemCreationParameters creationParameters) {
        int size = branches.size();
        bus1Num = new int[size];
        bus2Num = new int[size];
        connected1 = new boolean[size];
        connected2 = new boolean[size];
        y = new double[size];
        ksi = new double[size];
        cosKsi = new double[size];
        sinKsi = new double[size];
        g1 = new double[size];
        g2 = new double[size];
        b1 = new double[size];
        b2 = new double[size];
        r1 = new double[size];
        a1 = new double[size];
        disabled = new boolean[size];

        deriveA1 = new boolean[size];
        deriveR1 = new boolean[size];

        v1Row = new int[size];
        v2Row = new int[size];
        ph1Row = new int[size];
        ph2Row = new int[size];
        a1Row = new int[size];
        r1Row = new int[size];

        r1State = new double[size];
        a1State = new double[size];

        p1 = new double[size];
        p2 = new double[size];
        q1 = new double[size];
        q2 = new double[size];
        i1 = new double[size];
        i2 = new double[size];
        v1State = new double[size];
        v2State = new double[size];
        i1Valid = new boolean[size];
        i2Valid = new boolean[size];
        // as long as not computed, currents are zero
        java.util.Arrays.fill(i1Valid, true);
        java.util.Arrays.fill(i2Valid, true);

        dp1dv1 = new double[size];
        dp1dv2 = new double[size];
        dp1dph1 = new double[size];
        dp1dph2 = new double[size];
        dp1da1 = new double[size];
        dp1dr1 = new double[size];

        dq1dv1 = new double[size];
        dq1dv2 = new double[size];
        dq1dph1 = new double[size];
        dq1dph2 = new double[size];
        dq1da1 = new double[size];
        dq1dr1 = new double[size];

        dp2dv1 = new double[size];
        dp2dv2 = new double[size];
        dp2dph1 = new double[size];
        dp2dph2 = new double[size];
        dp2da1 = new double[size];
        dp2dr1 = new double[size];

        dq2dv1 = new double[size];
        dq2dv2 = new double[size];
        dq2dph1 = new double[size];
        dq2dph2 = new double[size];
        dq2da1 = new double[size];
        dq2dr1 = new double[size];

        for (int i = 0; i < branches.size(); i++) {
            LfBranch branch = branches.get(i);
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            bus1Num[i] = bus1 != null ? bus1.getNum() : -1;
            bus2Num[i] = bus2 != null ? bus2.getNum() : -1;
            connected1[i] = branch.isConnectedSide1();
            connected2[i] = branch.isConnectedSide2();
            PiModel piModel = branch.getPiModel();
            if (piModel.getZ() != 0) {
                y[i] = piModel.getY();
                ksi[i] = piModel.getKsi();
                cosKsi[i] = FastMath.cos(ksi[i]);
                sinKsi[i] = FastMath.sin(ksi[i]);
            }
            b1[i] = piModel.getB1();
            b2[i] = piModel.getB2();
            g1[i] = piModel.getG1();
            g2[i] = piModel.getG2();
            r1[i] = piModel.getR1();
            a1[i] = piModel.getA1();
            disabled[i] = branch.isDisabled();
            deriveA1[i] = AcEquationSystemCreator.isDeriveA1(branch, creationParameters);
            deriveR1[i] = AcEquationSystemCreator.isDeriveR1(branch);
        }

        packedParams = new double[size * P_STRIDE];
        packedRows = new int[size * R_STRIDE];
        status = new byte[size];
        for (int i = 0; i < size; i++) {
            updatePackedParams(i);
            updatePackedRows(i);
            updateStatus(i);
        }
    }

    public int getSize() {
        return disabled.length;
    }

    // Interleaved copy of the inputs of the flows computation (constant parameters and variable rows), so that the
    // hot loop of AcNetworkVector reads one contiguous block per branch instead of about twenty separate arrays.
    // Kept in sync with the separate arrays (which remain the reference for other users) by the update methods below.
    static final int P_Y = 0;
    static final int P_SIN_KSI = 1;
    static final int P_COS_KSI = 2;
    static final int P_G1 = 3;
    static final int P_B1 = 4;
    static final int P_G2 = 5;
    static final int P_B2 = 6;
    static final int P_R1 = 7;
    static final int P_A1 = 8;
    static final int P_STRIDE = 9;

    static final int R_PH1 = 0;
    static final int R_PH2 = 1;
    static final int R_V1 = 2;
    static final int R_V2 = 3;
    static final int R_A1 = 4;
    static final int R_R1 = 5;
    static final int R_STRIDE = 6;

    final double[] packedParams;
    final int[] packedRows;
    // 0: disabled, 1: enabled but not connected on both sides, 2: enabled and connected on both sides (closed)
    final byte[] status;

    void updatePackedParams(int i) {
        int b = i * P_STRIDE;
        packedParams[b + P_Y] = y[i];
        packedParams[b + P_SIN_KSI] = sinKsi[i];
        packedParams[b + P_COS_KSI] = cosKsi[i];
        packedParams[b + P_G1] = g1[i];
        packedParams[b + P_B1] = b1[i];
        packedParams[b + P_G2] = g2[i];
        packedParams[b + P_B2] = b2[i];
        packedParams[b + P_R1] = r1[i];
        packedParams[b + P_A1] = a1[i];
    }

    void updatePackedRows(int i) {
        int b = i * R_STRIDE;
        packedRows[b + R_PH1] = ph1Row[i];
        packedRows[b + R_PH2] = ph2Row[i];
        packedRows[b + R_V1] = v1Row[i];
        packedRows[b + R_V2] = v2Row[i];
        packedRows[b + R_A1] = a1Row[i];
        packedRows[b + R_R1] = r1Row[i];
    }

    void updateStatus(int i) {
        if (disabled[i]) {
            status[i] = 0;
        } else {
            boolean closed = bus1Num[i] != -1 && connected1[i] && bus2Num[i] != -1 && connected2[i];
            status[i] = (byte) (closed ? 2 : 1);
        }
    }

    public Evaluable getP1(int branchNum) {
        return () -> p1[branchNum];
    }

    public Evaluable getP2(int branchNum) {
        return () -> p2[branchNum];
    }

    public Evaluable getQ1(int branchNum) {
        return () -> q1[branchNum];
    }

    public Evaluable getQ2(int branchNum) {
        return () -> q2[branchNum];
    }

    public Evaluable getI1(int branchNum) {
        return () -> {
            if (!i1Valid[branchNum]) {
                i1[branchNum] = FastMath.hypot(p1[branchNum], q1[branchNum]) / v1State[branchNum];
                i1Valid[branchNum] = true;
            }
            return i1[branchNum];
        };
    }

    public Evaluable getI2(int branchNum) {
        return () -> {
            if (!i2Valid[branchNum]) {
                i2[branchNum] = FastMath.hypot(p2[branchNum], q2[branchNum]) / v2State[branchNum];
                i2Valid[branchNum] = true;
            }
            return i2[branchNum];
        };
    }
}
