/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class AcSolverUtil {

    private AcSolverUtil() {
    }

    public static void initStateVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, VoltageInitializer initializer) {
        // variables are visited in element order (network elements accessed sequentially in memory)
        double[] x = new double[equationSystem.getIndex().getSortedVariablesToFind().size()];
        var table = equationSystem.getIndex().getVariablesToFindTable();
        for (int i = 0; i < table.size(); i++) {
            int row = table.rows()[i];
            int num = table.elementNums()[i];
            switch (table.types()[i]) {
                case BUS_V:
                    x[row] = initializer.getMagnitude(network, num);
                    break;

                case BUS_PHI:
                    x[row] = initializer.getAngle(network, num);
                    break;

                case SHUNT_B:
                    x[row] = network.getShunt(num).getB();
                    break;

                case BRANCH_ALPHA1:
                    x[row] = network.getBranch(num).getPiModel().getA1();
                    break;

                case BRANCH_RHO1:
                    x[row] = network.getBranch(num).getPiModel().getR1();
                    break;

                case DUMMY_P,
                     DUMMY_Q,
                     BUS_PHI_ZERO,
                     BUS_PHI_NEGATIVE:
                    x[row] = 0;
                    break;

                case BUS_V_ZERO,
                     BUS_V_NEGATIVE:
                    // when balanced, zero and negative sequence should be zero
                    // v_zero and v_negative initially set to zero will bring a singularity to the Jacobian
                    // We chose to set the initial value to a small one, but different from zero
                    // By construction if the system does not carry any asymmetry in its structure,
                    // the resolution of the system on the three sequences will bring a singularity
                    // Therefore, if the system is balanced by construction, we should run a balanced load flow only
                    x[row] = 0.1;
                    break;

                case DC_BUS_V:
                    x[row] = initializer.getDcVoltage(network.getDcBus(num));
                    break;

                case CONV_P_AC:
                    double p = network.getVoltageSourceConverter(num).getPac();
                    x[row] = Double.isNaN(p) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : p;
                    break;

                case CONV_Q_AC:
                    double q = network.getVoltageSourceConverter(num).getQac();
                    x[row] = Double.isNaN(q) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : q;
                    break;
                default:
                    throw new IllegalStateException("Unknown variable type " + table.types()[i]);
            }
        }
        equationSystem.getStateVector().set(x);
    }

    public static void updateNetwork(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        // update state variable
        StateVector stateVector = equationSystem.getStateVector();
        var table = equationSystem.getIndex().getVariablesToFindTable();
        for (int i = 0; i < table.size(); i++) {
            int row = table.rows()[i];
            int num = table.elementNums()[i];
            switch (table.types()[i]) {
                case BUS_V:
                    network.setBusV(num, stateVector.get(row));
                    break;

                case BUS_PHI:
                    network.setBusAngle(num, stateVector.get(row));
                    break;

                case BUS_V_ZERO:
                    network.getBus(num).getAsym().setVz(stateVector.get(row));
                    break;

                case BUS_PHI_ZERO:
                    network.getBus(num).getAsym().setAngleZ(stateVector.get(row));
                    break;

                case BUS_V_NEGATIVE:
                    network.getBus(num).getAsym().setVn(stateVector.get(row));
                    break;

                case BUS_PHI_NEGATIVE:
                    network.getBus(num).getAsym().setAngleN(stateVector.get(row));
                    break;

                case SHUNT_B:
                    network.getShunt(num).setB(stateVector.get(row));
                    break;

                case BRANCH_ALPHA1:
                    network.getBranch(num).getPiModel().setA1(stateVector.get(row));
                    break;

                case BRANCH_RHO1:
                    network.getBranch(num).getPiModel().setR1(stateVector.get(row));
                    break;

                case DUMMY_P,
                     DUMMY_Q:
                    // nothing to do
                    break;

                case DC_BUS_V:
                    network.getDcBus(num).setV(stateVector.get(row));
                    break;

                case CONV_P_AC:
                    network.getVoltageSourceConverter(num).setPac(stateVector.get(row));
                    break;

                case CONV_Q_AC:
                    network.getVoltageSourceConverter(num).setQac(stateVector.get(row));
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type " + table.types()[i]);
            }
        }
    }
}
