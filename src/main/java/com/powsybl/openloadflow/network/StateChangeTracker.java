/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * Records which buses (including their generators, loads and shunts) and branches have had their saved state
 * modified since a {@link NetworkState} has been saved or restored, so that this network state can be restored
 * incrementally (only modified elements are restored). Setters of state attributes just mark the element as
 * modified (this is very cheap), the restore then only visits marked elements.
 * <p>
 * Only one network state at a time can rely on the tracker (the owner): the last one saved or fully restored.
 * Bus voltage magnitudes and angles are not tracked (they are updated for all buses by each load flow run).
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class StateChangeTracker {

    private Object owner;

    private boolean[] busChanged = new boolean[0];
    private int[] changedBuses = new int[0];
    private int changedBusCount;

    private boolean[] branchChanged = new boolean[0];
    private int[] changedBranches = new int[0];
    private int changedBranchCount;

    // set if a change cannot be recorded (unnumbered element): incremental restore is not possible
    private boolean overflow;

    /**
     * Start tracking changes for the given owner (a network state just saved or fully restored).
     */
    void reset(Object owner, int busCount, int branchCount) {
        this.owner = owner;
        if (busChanged.length != busCount) {
            busChanged = new boolean[busCount];
            changedBuses = new int[busCount];
        } else {
            for (int i = 0; i < changedBusCount; i++) {
                busChanged[changedBuses[i]] = false;
            }
        }
        changedBusCount = 0;
        if (branchChanged.length != branchCount) {
            branchChanged = new boolean[branchCount];
            changedBranches = new int[branchCount];
        } else {
            for (int i = 0; i < changedBranchCount; i++) {
                branchChanged[changedBranches[i]] = false;
            }
        }
        changedBranchCount = 0;
        overflow = false;
    }

    /**
     * Invalidate the tracker: next restore of any network state will be a full one.
     */
    void invalidate() {
        owner = null;
    }

    boolean isTracking(Object state) {
        return owner == state && !overflow;
    }

    public void onBusChange(int num) {
        if (num < 0 || num >= busChanged.length) {
            overflow = true;
        } else if (!busChanged[num]) {
            busChanged[num] = true;
            changedBuses[changedBusCount++] = num;
        }
    }

    public void onBranchChange(int num) {
        if (num < 0 || num >= branchChanged.length) {
            overflow = true;
        } else if (!branchChanged[num]) {
            branchChanged[num] = true;
            changedBranches[changedBranchCount++] = num;
        }
    }

    int getChangedBusCount() {
        return changedBusCount;
    }

    int getChangedBranchCount() {
        return changedBranchCount;
    }

    int getChangedBus(int i) {
        return changedBuses[i];
    }

    int getChangedBranch(int i) {
        return changedBranches[i];
    }
}
