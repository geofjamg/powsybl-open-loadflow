package bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

public final class Probe {
    private Probe() { }

    public static void main(String[] args) throws Exception {
        Network network = Network.read(Path.of(args[0]));
        LoadFlowParameters p = OlfN1Bench.lfParams("nr");
        AcLoadFlowParameters acp = OpenLoadFlowParameters.createAcParameters(network, p, OpenLoadFlowParameters.get(p),
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        LfNetwork lfn = Networks.load(network, acp.getNetworkParameters()).get(0);
        try (AcLoadFlowContext ctx = new AcLoadFlowContext(lfn, acp)) {
            AcLoadFlowResult r = new AcloadFlowEngine(ctx).run();
            System.out.println("LF " + r.getSolverStatus() + " vectorized=" + acp.isVectorized());
            EquationSystem<?, ?> es = ctx.getEquationSystem();
            System.out.println("single equations: " + es.getEquations().size() + ", jacobian dim=" + es.getIndex().getColumnCount()
                    + ", nnz=" + ctx.getJacobianMatrix().getMatrix().toSparse().getValueCount());
            for (EquationArray<?, ?> a : es.getEquationArrays()) {
                Field f = EquationArray.class.getDeclaredField("singleTermsByEquationElementNum");
                f.setAccessible(true);
                Map<Integer, ?> m = (Map<Integer, ?>) f.get(a);
                TreeMap<Integer, Integer> histVars = new TreeMap<>();
                TreeMap<Integer, Integer> histTerms = new TreeMap<>();
                for (Object add : m.values()) {
                    Field tv = add.getClass().getDeclaredField("termsByVariable"); tv.setAccessible(true);
                    Field tt = add.getClass().getDeclaredField("terms"); tt.setAccessible(true);
                    histVars.merge(((Map<?, ?>) tv.get(add)).size(), 1, Integer::sum);
                    histTerms.merge(((List<?>) tt.get(add)).size(), 1, Integer::sum);
                }
                Map<String, Integer> termTypes = new TreeMap<>();
                for (Object add : m.values()) {
                    Field tt = add.getClass().getDeclaredField("terms"); tt.setAccessible(true);
                    for (Object t : (List<?>) tt.get(add)) termTypes.merge(t.getClass().getSimpleName(), 1, Integer::sum);
                }
                System.out.printf("array %s: length=%d, elements with additional single terms=%d%n  #variables per element -> count: %s%n  #terms per element -> count: %s%n  term types: %s%n",
                        a.getType(), a.getLength(), m.size(), histVars, histTerms, termTypes);
            }
        }
    }
}
