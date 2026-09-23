/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.cartesian;

import com.powsybl.openloadflow.ac.equations.AbstractLoadModelEquationTerm;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfLoadModel;

import java.util.Collection;
import java.util.Objects;

/**
 * Voltage dependent load active or reactive power.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LoadModelCartesianEquationTerm extends AbstractBusVoltageMagnitudeFunctionCartesianEquationTerm<LfBus> {

    private final LfLoad load;

    private final LfLoadModel loadModel;

    private final boolean reactive;

    public LoadModelCartesianEquationTerm(LfBus bus, LfLoad load, LfLoadModel loadModel, VariableSet<AcVariableType> variableSet, boolean reactive) {
        super(bus, bus, variableSet);
        this.load = Objects.requireNonNull(load);
        this.loadModel = Objects.requireNonNull(loadModel);
        this.reactive = reactive;
    }

    private Collection<LfLoadModel.ExpTerm> getExpTerms() {
        return reactive ? loadModel.getExpTermsQ() : loadModel.getExpTermsP();
    }

    private double getTarget() {
        return reactive ? load.getTargetQ() : load.getTargetP();
    }

    @Override
    protected double f(double v) {
        return AbstractLoadModelEquationTerm.f(v, getTarget(), getExpTerms());
    }

    @Override
    protected double dfdv(double v) {
        return AbstractLoadModelEquationTerm.dfdv(v, getTarget(), getExpTerms());
    }

    @Override
    protected String getName() {
        return reactive ? "ac_load_q_cartesian" : "ac_load_p_cartesian";
    }
}
