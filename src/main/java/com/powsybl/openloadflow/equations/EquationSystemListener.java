/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface EquationSystemListener<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType);

    void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType);

    void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType);

    /**
     * Called when the column of an equation array element changed value without changing structure, which happens when
     * the element and its complementary equation switch.
     */
    default void onEquationArrayValuesChange(EquationArray<V, E> equationArray, int elementNum) {
        // nothing to do by default
    }

    /**
     * Called when an equation array element gained or lost its column because the pair it forms with its complementary
     * equation became occupied or empty. The variables have already been accounted for by the other events.
     */
    default void onEquationArrayColumnChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        // nothing to do by default
    }

    /**
     * Called when the complementary equation of an equation array element has been activated or deactivated.
     */
    default void onComplementaryEquationChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        // nothing to do by default
    }

    void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationTermEventType eventType);
}
