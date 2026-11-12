/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.Arrays;
import java.util.List;

/**
 * State of all branches of a network stored in flat arrays indexed by branch position, instead of one state object
 * per branch. It restores exactly the same values, with the same setter calls in the same order, as
 * {@link BranchState}.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class BranchesVectorState {

    private static final byte NOT_SAVED = -1;

    private final LfBranch[] branches;
    private final boolean[] disabled;
    private final double[] a1;
    private final double[] r1;
    private final boolean[] phaseControlEnabled;
    private final boolean[] voltageControlEnabled;
    private final boolean[] hasTapPosition;
    private final int[] tapPosition;
    private final byte[] connectedSide1; // -1: not saved (disconnection not allowed), 0: false, 1: true
    private final byte[] connectedSide2;
    private final int[] tapBranches; // branches with a tap position, always restored by incremental restore

    private BranchesVectorState(List<LfBranch> branchList) {
        branches = branchList.toArray(new LfBranch[0]);
        int size = branches.length;
        disabled = new boolean[size];
        a1 = new double[size];
        r1 = new double[size];
        phaseControlEnabled = new boolean[size];
        voltageControlEnabled = new boolean[size];
        hasTapPosition = new boolean[size];
        tapPosition = new int[size];
        connectedSide1 = new byte[size];
        connectedSide2 = new byte[size];
        int tapBranchCount = 0;
        for (int i = 0; i < size; i++) {
            LfBranch branch = branches[i];
            disabled[i] = branch.isDisabled();
            PiModel piModel = branch.getPiModel();
            if (piModel instanceof PiModelArray piModelArray) {
                hasTapPosition[i] = true;
                tapBranchCount++;
                tapPosition[i] = piModel.getTapPosition();
                // also save modified a1 and r1 and not directly a1 and r1 to avoid restoring
                // with same values as current tap position
                a1[i] = piModelArray.getModifiedA1();
                r1[i] = piModelArray.getModifiedR1();
            } else {
                a1[i] = piModel.getA1();
                r1[i] = piModel.getR1();
            }
            phaseControlEnabled[i] = branch.isPhaseControlEnabled();
            voltageControlEnabled[i] = branch.isVoltageControlEnabled();
            connectedSide1[i] = branch.isDisconnectionAllowedSide1() ? (byte) (branch.isConnectedSide1() ? 1 : 0) : NOT_SAVED;
            connectedSide2[i] = branch.isDisconnectionAllowedSide2() ? (byte) (branch.isConnectedSide2() ? 1 : 0) : NOT_SAVED;
        }
        tapBranches = new int[tapBranchCount];
        int k = 0;
        for (int i = 0; i < size; i++) {
            if (hasTapPosition[i]) {
                tapBranches[k++] = i;
            }
        }
    }

    public static BranchesVectorState save(List<LfBranch> branches) {
        return new BranchesVectorState(branches);
    }

    /**
     * Same setter calls in the same order as {@link BranchState#restore()}.
     */
    public void restore() {
        for (int i = 0; i < branches.length; i++) {
            restoreBranch(i);
        }
    }

    /**
     * Incremental restore: only branches modified since last save or restore, and branches with a tap position
     * (their pi model have too many ways to be modified to be tracked).
     */
    public void restore(StateChangeTracker tracker) {
        int[] changed = new int[tracker.getChangedBranchCount() + tapBranches.length];
        int count = 0;
        for (int k = 0; k < tracker.getChangedBranchCount(); k++) {
            int i = tracker.getChangedBranch(k);
            if (!hasTapPosition[i]) {
                changed[count++] = i;
            }
        }
        System.arraycopy(tapBranches, 0, changed, count, tapBranches.length);
        count += tapBranches.length;
        Arrays.sort(changed, 0, count); // same order as a full restore
        for (int k = 0; k < count; k++) {
            restoreBranch(changed[k]);
        }
    }

    boolean isCompatible(List<LfBranch> branchList) {
        if (branchList.size() != branches.length) {
            return false;
        }
        for (int i = 0; i < branches.length; i++) {
            if (branchList.get(i) != branches[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find a difference with another state of the same branches (for incremental restore checking).
     *
     * @return the description of the first difference found, or null if both states are the same
     */
    String findDifference(BranchesVectorState other) {
        for (int i = 0; i < branches.length; i++) {
            if (disabled[i] != other.disabled[i]
                    || Double.compare(a1[i], other.a1[i]) != 0
                    || Double.compare(r1[i], other.r1[i]) != 0
                    || phaseControlEnabled[i] != other.phaseControlEnabled[i]
                    || voltageControlEnabled[i] != other.voltageControlEnabled[i]
                    || tapPosition[i] != other.tapPosition[i]
                    || connectedSide1[i] != other.connectedSide1[i]
                    || connectedSide2[i] != other.connectedSide2[i]) {
                return "branch " + branches[i].getId();
            }
        }
        return null;
    }

    private void restoreBranch(int i) {
        LfBranch branch = branches[i];
        branch.setDisabled(disabled[i]);
        PiModel piModel = branch.getPiModel();
        if (hasTapPosition[i]) {
            piModel.setTapPosition(tapPosition[i]);
        }
        piModel.setA1(a1[i]);
        piModel.setR1(r1[i]);
        branch.setPhaseControlEnabled(phaseControlEnabled[i]);
        branch.setVoltageControlEnabled(voltageControlEnabled[i]);
        if (connectedSide1[i] != NOT_SAVED) {
            branch.setConnectedSide1(connectedSide1[i] == 1);
        }
        if (connectedSide2[i] != NOT_SAVED) {
            branch.setConnectedSide2(connectedSide2[i] == 1);
        }
    }
}
