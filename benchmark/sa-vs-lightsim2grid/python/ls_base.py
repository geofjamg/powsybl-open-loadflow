import sys, time, warnings
warnings.filterwarnings("ignore")
import numpy as np, pypowsybl as pp
from lightsim2grid.gridmodel import init_from_pypowsybl
from lightsim2grid.solver import SolverType

path = sys.argv[1]
n = pp.network.load(path)
p = pp.loadflow.Parameters(distributed_slack=False, voltage_init_mode=pp.loadflow.VoltageInitMode.DC_VALUES,
                           use_reactive_limits=False, transformer_voltage_control_on=False, shunt_compensator_voltage_control_on=False)
t = time.perf_counter(); r = pp.loadflow.run_ac(n, p); print("OLF(pypowsybl) base", r[0].status, r[0].iteration_count, "it", time.perf_counter() - t, "s", r[0].reference_bus_id)
slack_bus = r[0].reference_bus_id
gens = n.get_generators()
slack_gen = gens[(gens.bus_id == slack_bus) & gens.connected].index[0]
print("slack gen", slack_gen)
t = time.perf_counter()
grid = init_from_pypowsybl(n, gen_slack_id=(slack_gen, 1.0))
print("init_from_pypowsybl", time.perf_counter() - t, "s")
import lightsim2grid.lightsim2grid_cpp as c
grid.change_algorithm(c.AlgorithmType.NR_KLU)
print("outer loops", grid.get_outer_loops(), "algo", grid.get_algo_name(), grid.get_ac_algo_config())
nb = grid.total_bus()
V0 = np.full(nb, 1.0, dtype=complex)
grid.clear_outer_loops()
Vdc = grid.dc_pf(V0, 10, 1e-8)
print("dc ok", Vdc.shape)
V0 = Vdc
t = time.perf_counter(); V = grid.ac_pf(V0, 20, 1e-6); dt = time.perf_counter() - t
print("LS2G base nb_bus", nb, "converged", V.shape[0] > 0, "it", grid.get_algo().get_nb_iter(), "time", dt)
# compare voltages to OLF
b = n.get_buses()
vm_olf = (b.v_mag / b.index.map(lambda x: 0).astype(float)) if False else None
vl = n.get_voltage_levels()
b = b.join(vl[["nominal_v"]], on="voltage_level_id")
vm_olf = (b.v_mag / b.nominal_v).values
va_olf = b.v_angle.values
ls_vm = np.abs(V)
print("LS2G n_conv buses", (ls_vm>0).sum(), "OLF buses", len(b))
print("vm OLF range", np.nanmin(vm_olf), np.nanmax(vm_olf), "LS2G range", ls_vm.min(), ls_vm.max())
print("sorted vm max abs diff (rough check)", np.max(np.abs(np.sort(vm_olf[~np.isnan(vm_olf)]) - np.sort(ls_vm[ls_vm > 0]))) if len(ls_vm[ls_vm > 0]) == np.sum(~np.isnan(vm_olf)) else "bus count differs")
import pandas as pd
res = grid.get_line_res1()
print("line_res1 tuple len", len(res), [np.asarray(x).shape for x in res])
names = list(grid.get_line_names())
p_ls = pd.Series(np.asarray(res[0]), index=names)
v_ls = pd.Series(np.asarray(res[2]), index=names)
lines = n.get_lines().join(vl[["nominal_v"]], on="voltage_level1_id")
common = lines.index.intersection(p_ls.index)
dp = (lines.loc[common, "p1"] - p_ls[common]).abs()
print("lines compared", len(common), "max |dP1| MW", dp.max(), "median", dp.median())
print(dp.sort_values().tail(5))
dv = (lines.loc[common, "v1"] if "v1" in lines else None)
tr = n.get_2_windings_transformers(); rt = grid.get_trafo_res1(); tnames = list(grid.get_trafo_names())
pt = pd.Series(np.asarray(rt[0]), index=tnames); ct = tr.index.intersection(pt.index)
print("trafos compared", len(ct), "max |dP1| MW", (tr.loc[ct, "p1"] - pt[ct]).abs().max())

# export the converged lightsim2grid Jacobian (raw CSC, same format as java/bench/ExportJ)
if len(sys.argv) > 2:
    J = grid.get_algo().get_J().tocsc(); J.sort_indices()
    open(sys.argv[2], 'wb').write(np.array([J.shape[0], J.nnz], '<i4').tobytes() + J.indptr.astype('<i4').tobytes()
                                  + J.indices.astype('<i4').tobytes() + J.data.astype('<f8').tobytes())
    print("lightsim2grid Jacobian exported to", sys.argv[2])
