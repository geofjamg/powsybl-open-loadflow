"""lightsim2grid N-1 (lines only) benchmark on an IIDM network loaded through pypowsybl."""
import sys, time, warnings, json
warnings.filterwarnings("ignore")
import numpy as np, pypowsybl as pp
import lightsim2grid.lightsim2grid_cpp as c
from lightsim2grid.gridmodel import init_from_pypowsybl
from lightsim2grid.securityAnalysis import ContingencyAnalysisCPP

path = sys.argv[1]
configs = sys.argv[2].split(",") if len(sys.argv) > 2 else ["nr"]
n = pp.network.load(path)
p = pp.loadflow.Parameters(distributed_slack=False, voltage_init_mode=pp.loadflow.VoltageInitMode.DC_VALUES,
                           use_reactive_limits=False, transformer_voltage_control_on=False, shunt_compensator_voltage_control_on=False)
r = pp.loadflow.run_ac(n, p)
gens = n.get_generators()
slack_gen = gens[(gens.bus_id == r[0].reference_bus_id) & gens.connected].index[0]

def build(outer):
    grid = init_from_pypowsybl(n, gen_slack_id=None if outer else (slack_gen, 1.0))
    if not outer:
        grid.clear_outer_loops()
    grid.change_algorithm(c.AlgorithmType.NR_KLU)
    V0 = grid.dc_pf(np.full(grid.total_bus(), 1.0, dtype=complex), 10, 1e-8)
    V = grid.ac_pf(V0, 30, 1e-6)
    assert V.shape[0] > 0, "base case diverged"
    return grid, V

nl = len(n.get_lines())
out = {}
for cfg in configs:
    outer = "outer" in cfg
    grid, V = build(outer)
    sa = ContingencyAnalysisCPP(grid, "viol" in cfg)
    if outer:
        print("available algos", sa.available_algorithm_names())
        sa.change_algorithm("NROuter_KLU")
        sa.set_algo_config(grid.get_ac_algo_config())
    sa.add_multiple_n1(list(range(nl)))
    import re
    m = re.search(r"_t(\d+)", cfg)
    sa.nb_thread = int(m.group(1)) if m else 1
    sa.handle_disconnected_grid = "island" in cfg
    sa.reuse_base_case = False
    tol = 1e-6 if "tight" in cfg else 0.01  # MVA, inf-norm (OLF default ~1e-4 p.u. = 0.01 MW/MVar)
    t = time.perf_counter()
    sa.compute(V, 10, tol)
    t_compute = time.perf_counter() - t
    t = time.perf_counter(); sa.compute_flows(); A = sa.get_flows(); t_flows = time.perf_counter() - t
    t = time.perf_counter(); sa.compute_power_flows(); t_pflows = time.perf_counter() - t
    it = np.asarray(sa.get_row_nb_iter())
    conv = np.asarray(sa.converged_mask()) if hasattr(sa, "converged_mask") else None
    res = dict(cfg=cfg, n_cont=nl, nb_thread=sa.nb_thread, wall_compute_s=t_compute, flows_amps_s=t_flows, flows_pq_s=t_pflows,
               total_time=sa.total_time(), solver_time=sa.solver_time(), preprocessing_time=sa.preprocessing_time(),
               modif_Ybus_time=sa.modif_Ybus_time(), amps_time=sa.amps_computation_time(),
               nb_solved=int(sa.nb_solved()), nb_converged=int(sa.nb_converged()),
               mean_iter_solved=float(it[it > 0].mean()), max_iter=int(it.max()))
    try:
        res["linear_solver_stats"] = str(sa.get_linear_solver_stats())
    except Exception as e:
        res["linear_solver_stats"] = repr(e)
    res["per_cont_ms"] = 1000 * t_compute / nl
    if "viol" in cfg:
        t = time.perf_counter(); viol = sa.get_violations(); res["get_violations_s"] = time.perf_counter() - t
        res["violations"] = int(sum(len(v) for v in viol))
    print(json.dumps(res, indent=1))
    out[cfg] = res
json.dump(out, open(sys.argv[3] if len(sys.argv) > 3 else "/dev/null", "w"), indent=1)
