"""Exclusive categorisation of JFR Java execution samples (leaf -> root, first matching category wins)."""
import json, sys, subprocess, collections
raw = subprocess.run(["jfr", "print", "--json", "--stack-depth", "128", "--events", "jdk.ExecutionSample", sys.argv[1]], capture_output=True, text=True).stdout
ev = json.loads(raw)["recording"]["events"]
CATS = [  # (category, substring) checked from leaf to root
    ("KLU (JNI Java side)", "SparseLUDecomposition."),
    ("Jacobian: structure rebuild (initDer)", "JacobianMatrix.initDer"),
    ("Jacobian: value refill (updateDer)", "JacobianMatrix.updateDer"),
    ("Mismatch vector eval", "EquationVector.updateArray"),
    ("Mismatch vector eval", "AbstractVector.getArray"),
    ("State -> branch currents/flows (AcNetworkVector)", "AcNetworkVector."),
    ("NR misc (init state, norms, updateNetwork)", "NewtonRaphson."),
    ("NR misc (init state, norms, updateNetwork)", "AcSolverUtil."),
    ("Unrealistic state check", "isStateUnrealistic"),
    ("Outer loops", "OuterLoop"),
    ("Network state restore", "NetworkState.restore"),
    ("Limit violations detection", "LimitViolationManager"),
    ("Post-contingency results / monitors", "PostContingencyNetworkResult"),
    ("Post-contingency results / monitors", "runPostContingencySimulation"),
    ("Contingency apply / connectivity", "LfContingency."),
    ("Contingency apply / connectivity", "PropagatedContingency."),
    ("Contingency apply / connectivity", "GraphConnectivity"),
    ("Equation system update on disable", "EquationSystemUpdater"),
    ("Other in contingency loop", "processContingency"),
]
cnt = collections.Counter(); tot = 0
for e in ev:
    st = e["values"].get("stackTrace")
    if not st: continue
    fr = ["%s.%s" % (f["method"]["type"]["name"].replace("/", "."), f["method"]["name"]) for f in st["frames"]]
    if not any("openloadflow" in f for f in fr): continue
    tot += 1
    cat = "Base case + setup / other"
    for f in fr:
        hit = next((c for c, k in CATS if k in f), None)
        if hit: cat = hit; break
    cnt[cat] += 1
print("java samples", tot)
for c, v in cnt.most_common(): print("%6.1f%%  %s" % (100 * v / tot, c))
