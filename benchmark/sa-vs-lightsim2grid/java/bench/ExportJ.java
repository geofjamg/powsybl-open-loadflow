package bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.*;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.*;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;

import java.io.*;
import java.nio.file.Path;

/** Dump the converged OLF Jacobian as raw CSC: int n, int nnz, int[n+1] ap, int[nnz] ai, double[nnz] ax (little endian). */
public final class ExportJ {
    private ExportJ() { }

    public static void main(String[] args) throws Exception {
        OlfN1Bench.setVectorized(!Boolean.getBoolean("novec"));
        Network network = Network.read(Path.of(args[0]));
        LoadFlowParameters p = OlfN1Bench.lfParams(Boolean.getBoolean("novec") ? "nr_novec" : "nr");
        AcLoadFlowParameters acp = OpenLoadFlowParameters.createAcParameters(network, p, OpenLoadFlowParameters.get(p),
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        LfNetwork lfn = Networks.load(network, acp.getNetworkParameters()).get(0);
        try (AcLoadFlowContext ctx = new AcLoadFlowContext(lfn, acp)) {
            new AcloadFlowEngine(ctx).run();
            SparseMatrix m = (SparseMatrix) ctx.getJacobianMatrix().getMatrix();
            int n = m.getColumnStart().length - 1;
            int nnz = m.getColumnStart()[n];
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8 + 4 * (n + 1) + 4 * nnz + 8 * nnz).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                bb.putInt(n).putInt(nnz);
                for (int i = 0; i <= n; i++) bb.putInt(m.getColumnStart()[i]);
                for (int i = 0; i < nnz; i++) bb.putInt(m.getRowIndices()[i]);
                for (int i = 0; i < nnz; i++) bb.putDouble(m.getValues()[i]);
                out.write(bb.array());
            }
            System.out.println("exported n=" + n + " nnz=" + nnz);
        }
    }
}
