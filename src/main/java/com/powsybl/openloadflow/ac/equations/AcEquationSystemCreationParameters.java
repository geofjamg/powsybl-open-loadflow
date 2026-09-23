/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcEquationSystemCreationParameters {

    public static final AcVoltageRepresentation VOLTAGE_REPRESENTATION_DEFAULT_VALUE = AcVoltageRepresentation.POLAR;

    private final boolean forceA1Var;

    private final AcVoltageRepresentation voltageRepresentation;

    public AcEquationSystemCreationParameters() {
        this(false);
    }

    public AcEquationSystemCreationParameters(boolean forceA1Var) {
        this(forceA1Var, VOLTAGE_REPRESENTATION_DEFAULT_VALUE);
    }

    public AcEquationSystemCreationParameters(boolean forceA1Var, AcVoltageRepresentation voltageRepresentation) {
        this.forceA1Var = forceA1Var;
        this.voltageRepresentation = Objects.requireNonNull(voltageRepresentation);
    }

    public boolean isForceA1Var() {
        return forceA1Var;
    }

    public AcVoltageRepresentation getVoltageRepresentation() {
        return voltageRepresentation;
    }

    public AcEquationTermFactory getTermFactory() {
        return AcEquationTermFactory.of(voltageRepresentation);
    }

    @Override
    public String toString() {
        return "AcEquationSystemCreationParameters(" +
                "forceA1Var=" + forceA1Var +
                ", voltageRepresentation=" + voltageRepresentation +
                ')';
    }
}
