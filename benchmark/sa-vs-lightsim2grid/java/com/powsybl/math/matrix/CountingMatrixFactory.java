package com.powsybl.math.matrix;

import java.util.concurrent.atomic.AtomicLong;

/** SparseMatrixFactory decorator counting/timing every KLU call made through LUDecomposition. */
public class CountingMatrixFactory implements MatrixFactory {

    public static final AtomicLong CREATE = new AtomicLong();
    public static final AtomicLong INIT = new AtomicLong();
    public static final AtomicLong INIT_NS = new AtomicLong();
    public static final AtomicLong UPD_TRUE = new AtomicLong();
    public static final AtomicLong UPD_TRUE_NS = new AtomicLong();
    public static final AtomicLong UPD_FALSE = new AtomicLong();
    public static final AtomicLong UPD_FALSE_NS = new AtomicLong();
    public static final AtomicLong SOLVE = new AtomicLong();
    public static final AtomicLong SOLVE_NS = new AtomicLong();

    public static void reset() {
        for (AtomicLong a : new AtomicLong[] {CREATE, INIT, INIT_NS, UPD_TRUE, UPD_TRUE_NS, UPD_FALSE, UPD_FALSE_NS, SOLVE, SOLVE_NS}) {
            a.set(0);
        }
    }

    public static String report(int nCont) {
        return String.format("jacobian structure builds=%d | KLU init(analyze+factor)=%d (%.1f s) | update(false)=klu_factor %d (%.1f s) | update(true)=klu_refactor+rgrowth %d (%.1f s) | solves=%d (%.1f s) | per contingency: builds=%.2f factor=%.2f refactor=%.2f solves=%.2f",
                CREATE.get(), INIT.get(), INIT_NS.get() / 1e9, UPD_FALSE.get(), UPD_FALSE_NS.get() / 1e9, UPD_TRUE.get(), UPD_TRUE_NS.get() / 1e9,
                SOLVE.get(), SOLVE_NS.get() / 1e9, (double) CREATE.get() / nCont, (double) UPD_FALSE.get() / nCont, (double) UPD_TRUE.get() / nCont, (double) SOLVE.get() / nCont);
    }

    static final class CountingSparseMatrix extends SparseMatrix {
        CountingSparseMatrix(int r, int c, int nnz) {
            super(r, c, nnz);
        }

        @Override
        public LUDecomposition decomposeLU() {
            long t = System.nanoTime();
            LUDecomposition lu = super.decomposeLU();
            INIT_NS.addAndGet(System.nanoTime() - t);
            INIT.incrementAndGet();
            return new LUDecomposition() {
                @Override
                public void update(boolean allowIncrementalUpdate) {
                    long t0 = System.nanoTime();
                    lu.update(allowIncrementalUpdate);
                    long dt = System.nanoTime() - t0;
                    if (allowIncrementalUpdate) {
                        UPD_TRUE.incrementAndGet();
                        UPD_TRUE_NS.addAndGet(dt);
                    } else {
                        UPD_FALSE.incrementAndGet();
                        UPD_FALSE_NS.addAndGet(dt);
                    }
                }

                private void timedSolve(Runnable r) {
                    long t0 = System.nanoTime();
                    r.run();
                    SOLVE_NS.addAndGet(System.nanoTime() - t0);
                    SOLVE.incrementAndGet();
                }

                @Override
                public void solve(double[] b) {
                    timedSolve(() -> lu.solve(b));
                }

                @Override
                public void solveTransposed(double[] b) {
                    timedSolve(() -> lu.solveTransposed(b));
                }

                @Override
                public void solve(DenseMatrix b) {
                    timedSolve(() -> lu.solve(b));
                }

                @Override
                public void solveTransposed(DenseMatrix b) {
                    timedSolve(() -> lu.solveTransposed(b));
                }

                @Override
                public void close() {
                    lu.close();
                }
            };
        }
    }

    @Override
    public Matrix create(int rowCount, int columnCount, int estimatedValueCount) {
        CREATE.incrementAndGet();
        return new CountingSparseMatrix(rowCount, columnCount, estimatedValueCount);
    }
}
