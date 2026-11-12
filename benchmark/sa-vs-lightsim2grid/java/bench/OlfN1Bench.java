package bench;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisProvider;
import com.powsybl.security.*;
import com.powsybl.contingency.violations.LimitViolationFilter;
import com.powsybl.security.results.PostContingencyResult;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * N-1 lines benchmark of the OpenLoadFlow AC security analysis.
 * args: <xiidm> <config,...> [repeat]
 * configs: nr (single slack, no Q limits, no controls) | default (OLF defaults + DC init) | suffix _t<N> for threads
 */
public final class OlfN1Bench {

    private OlfN1Bench() {
    }

    public static void setVectorized(boolean v) {
        try {
            java.lang.reflect.Field f = Class.forName("com.powsybl.openloadflow.ac.AcLoadFlowParameters").getDeclaredField("vectorizedDefaultValue");
            f.setAccessible(true);
            f.setBoolean(null, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static LoadFlowParameters lfParams(String cfg) {
        setVectorized(!cfg.contains("novec"));
        LoadFlowParameters p = new LoadFlowParameters()
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        OpenLoadFlowParameters olf = OpenLoadFlowParameters.create(p);
        if (cfg.startsWith("nr")) {
            p.setDistributedSlack(false)
             .setUseReactiveLimits(false)
             .setTransformerVoltageControlOn(false)
             .setShuntCompensatorVoltageControlOn(false)
             .setPhaseShifterRegulationOn(false);
        }
        if (cfg.contains("perEq")) {
            // per equation type criteria: 0.01 MW / 0.01 MVar, close to the inf-norm 0.01 MVA tol used for lightsim2grid
            olf.setNewtonRaphsonStoppingCriteriaType(com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA);
        }
        return p;
    }

    public static void main(String[] args) {
        Path file = Path.of(args[0]);
        String[] cfgs = args[1].split(",");
        int repeat = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        Network network = Network.read(file);
        List<Contingency> contingencies0 = network.getLineStream()
                .map(l -> new Contingency(l.getId(), new BranchContingency(l.getId())))
                .collect(Collectors.toList());
        List<Contingency> contingencies = contingencies0;
        String only = System.getProperty("only");
        if (only != null) {
            try {
                Set<String> keep = new HashSet<>(java.nio.file.Files.readAllLines(Path.of(only)));
                contingencies = contingencies.stream().filter(c -> keep.contains(c.getId())).collect(Collectors.toList());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        String excl = System.getProperty("exclude");
        if (excl != null) {
            try {
                Set<String> ex = new HashSet<>(java.nio.file.Files.readAllLines(Path.of(excl)));
                contingencies = contingencies.stream().filter(c -> !ex.contains(c.getId())).collect(Collectors.toList());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        System.out.printf("network: %d buses, %d lines, %d contingencies%n",
                network.getBusView().getBusStream().count(), network.getLineCount(), contingencies.size());

        for (String cfg : cfgs) {
            LoadFlowParameters lfp = lfParams(cfg);
            long t0 = System.nanoTime();
            LoadFlowResult lfr = LoadFlow.find("OpenLoadFlow").run(network, lfp);
            System.out.printf("[%s] base LF %s in %.3f s, %d it%n", cfg, lfr.getComponentResults().get(0).getStatus(),
                    (System.nanoTime() - t0) / 1e9, lfr.getComponentResults().get(0).getIterationCount());

            SecurityAnalysisParameters sap = new SecurityAnalysisParameters().setLoadFlowParameters(lfp);
            OpenSecurityAnalysisParameters osap = new OpenSecurityAnalysisParameters();
            int threads = cfg.matches(".*_t\\d+") ? Integer.parseInt(cfg.replaceAll(".*_t", "")) : 1;
            osap.setThreadCount(threads);
            sap.addExtension(OpenSecurityAnalysisParameters.class, osap);
            final List<Contingency> fc = contingencies;
            ContingenciesProvider provider = n -> fc;
            for (int r = 0; r < repeat; r++) {
                SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                        .setFilter(new LimitViolationFilter())
                        .setComputationManager(LocalComputationManager.getDefault())
                        .setSecurityAnalysisParameters(sap)
                        .setReportNode(ReportNode.NO_OP);
                long t = System.nanoTime();
                boolean counting = cfg.contains("count");
                if (counting) { com.powsybl.math.matrix.CountingMatrixFactory.reset(); }
                SecurityAnalysisResult res = (counting ? new OpenSecurityAnalysisProvider(new com.powsybl.math.matrix.CountingMatrixFactory(), new com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory<>()) : new OpenSecurityAnalysisProvider()).run(network, network.getVariantManager().getWorkingVariantId(),
                        provider, runParameters).join().getResult();
                double dt = (System.nanoTime() - t) / 1e9;
                Map<String, Long> status = res.getPostContingencyResults().stream()
                        .collect(Collectors.groupingBy(pcr -> pcr.getStatus().name(), TreeMap::new, Collectors.counting()));
                long islanded = res.getPostContingencyResults().stream()
                        .filter(pcr -> pcr.getConnectivityResult().getCreatedSynchronousComponentCount() > 0).count();
                long violations = res.getPostContingencyResults().stream().mapToLong(pcr -> pcr.getLimitViolationsResult().getLimitViolations().size()).sum();
                System.out.printf("RESULT cfg=%s run=%d threads=%d time_s=%.3f per_cont_ms=%.3f status=%s islanding=%d violations=%d%n",
                        cfg, r, threads, dt, 1000 * dt / contingencies.size(), status, islanded, violations);
                if (counting) { System.out.println("KLU " + com.powsybl.math.matrix.CountingMatrixFactory.report(contingencies.size())); }
            }
        }
    }
}
