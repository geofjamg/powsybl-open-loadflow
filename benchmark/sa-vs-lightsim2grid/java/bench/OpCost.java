package bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.*;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.*;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Unit cost of each Newton-Raphson building block on the real Jacobian (median of N runs, in microseconds). */
public final class OpCost {
    private OpCost() { }

    static double time(int n, Runnable r) {
        for (int i = 0; i < n / 5 + 5; i++) r.run(); // warmup
        double[] t = new double[n];
        for (int i = 0; i < n; i++) { long s = System.nanoTime(); r.run(); t[i] = (System.nanoTime() - s) / 1e3; }
        java.util.Arrays.sort(t);
        return t[n / 2];
    }

    public static void main(String[] args) throws Exception {
        int n = 300;
        Network network = Network.read(Path.of(args[0]));
        LoadFlowParameters p = OlfN1Bench.lfParams("nr");
        AcLoadFlowParameters acp = OpenLoadFlowParameters.createAcParameters(network, p, OpenLoadFlowParameters.get(p),
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        LfNetwork lfn = Networks.load(network, acp.getNetworkParameters()).get(0);
        try (AcLoadFlowContext ctx = new AcLoadFlowContext(lfn, acp)) {
            new AcloadFlowEngine(ctx).run();
            EquationSystem<AcVariableType, AcEquationType> es = ctx.getEquationSystem();
            JacobianMatrix<AcVariableType, AcEquationType> jm = ctx.getJacobianMatrix();
            SparseMatrix m = (SparseMatrix) jm.getMatrix();
            double[] x = es.getStateVector().get().clone();
            int dim = x.length;
            System.out.printf("Jacobian dim=%d nnz=%d%n", dim, m.getValueCount());

            Method updateDer = JacobianMatrix.class.getDeclaredMethod("updateDer"); updateDer.setAccessible(true);
            Method initDer = JacobianMatrix.class.getDeclaredMethod("initDer"); initDer.setAccessible(true);

            System.out.printf("stateVector.set (=> AcNetworkVector update incl. all branch currents) : %8.1f us%n",
                    time(n, () -> es.getStateVector().set(x.clone())));
            EquationVector<AcVariableType, AcEquationType> ev = ctx.getEquationVector();
            System.out.printf("equation vector eval (mismatch)                     : %8.1f us%n",
                    time(n, () -> { es.getStateVector().set(x.clone()); ev.getArray(); }) - time(n, () -> es.getStateVector().set(x.clone())));
            System.out.printf("Jacobian updateDer (value refill, Java)              : %8.1f us%n", time(n, () -> {
                try { updateDer.invoke(jm); } catch (Exception e) { throw new RuntimeException(e); } }));
            System.out.printf("Jacobian initDer (structure rebuild, Java)           : %8.1f us%n", time(n / 3, () -> {
                try { initDer.invoke(jm); } catch (Exception e) { throw new RuntimeException(e); } }));

            SparseMatrix mm = (SparseMatrix) jm.getMatrix();
            LUDecomposition[] lu = {mm.decomposeLU()};
            System.out.printf("KLU init = klu_analyze + klu_factor (new LU)          : %8.1f us%n", time(n / 3, () -> { lu[0].close(); lu[0] = mm.decomposeLU(); }));
            System.out.printf("KLU update(true)  = klu_refactor + klu_rgrowth       : %8.1f us%n", time(n, () -> lu[0].update(true)));
            System.out.printf("KLU update(false) = klu_factor (full numeric)         : %8.1f us%n", time(n, () -> lu[0].update(false)));
            double[] b = new double[dim];
            System.out.printf("KLU solve (1 rhs)                                    : %8.1f us%n", time(n, () -> { java.util.Arrays.fill(b, 1.0); lu[0].solveTransposed(b); }));
            lu[0].close();

            // network state save/restore
            NetworkState st = NetworkState.save(lfn);
            System.out.printf("NetworkState.save                                    : %8.1f us%n", time(n, () -> NetworkState.save(lfn)));
            System.out.printf("NetworkState.restore                                 : %8.1f us%n", time(n, st::restore));
        }
    }
}
