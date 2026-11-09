/* Time KLU operations on a CSC matrix with a KLU library chosen at runtime (dlopen). */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "klu.h"

typedef int (*defaults_t)(klu_common *);
typedef klu_symbolic *(*analyze_t)(int, int *, int *, klu_common *);
typedef klu_numeric *(*factor_t)(int *, int *, double *, klu_symbolic *, klu_common *);
typedef int (*refactor_t)(int *, int *, double *, klu_symbolic *, klu_numeric *, klu_common *);
typedef int (*rgrowth_t)(int *, int *, double *, klu_symbolic *, klu_numeric *, klu_common *);
typedef int (*solve_t)(klu_symbolic *, klu_numeric *, int, int, double *, klu_common *);
typedef int (*free_num_t)(klu_numeric **, klu_common *);
typedef int (*free_sym_t)(klu_symbolic **, klu_common *);

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e6 + t.tv_nsec / 1e3; }
static int cmp(const void *a, const void *b) { double x = *(double *)a, y = *(double *)b; return x < y ? -1 : x > y; }
#define REP 200
#define MED(expr) ({ double ts[REP]; for (int r = 0; r < REP; r++) { double t0 = now(); expr; ts[r] = now() - t0; } qsort(ts, REP, sizeof(double), cmp); ts[REP / 2]; })

int main(int argc, char **argv) {
    void *h = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!h) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    defaults_t klu_defaults_ = dlsym(h, "klu_defaults"); analyze_t klu_analyze_ = dlsym(h, "klu_analyze");
    factor_t klu_factor_ = dlsym(h, "klu_factor"); refactor_t klu_refactor_ = dlsym(h, "klu_refactor");
    rgrowth_t klu_rgrowth_ = dlsym(h, "klu_rgrowth"); solve_t klu_solve_ = dlsym(h, "klu_solve"); solve_t klu_tsolve_ = dlsym(h, "klu_tsolve");
    free_num_t klu_free_numeric_ = dlsym(h, "klu_free_numeric"); free_sym_t klu_free_symbolic_ = dlsym(h, "klu_free_symbolic");

    FILE *f = fopen(argv[2], "rb"); int n, nnz;
    fread(&n, 4, 1, f); fread(&nnz, 4, 1, f);
    int *ap = malloc(4 * (n + 1)), *ai = malloc(4 * nnz); double *ax = malloc(8 * nnz);
    fread(ap, 4, n + 1, f); fread(ai, 4, nnz, f); fread(ax, 8, nnz, f); fclose(f);

    static char buf[8192]; klu_common *c = (klu_common *)buf;
    klu_defaults_(c);
    klu_symbolic *sym = klu_analyze_(n, ap, ai, c);
    klu_numeric *num = klu_factor_(ap, ai, ax, sym, c);
    if (!num) { fprintf(stderr, "factor failed status=%d\n", c->status); return 1; }
    double *b = malloc(8 * n);
    double t_an = MED({ klu_symbolic *s2 = klu_analyze_(n, ap, ai, c); klu_free_symbolic_(&s2, c); });
    double t_fa = MED({ klu_numeric *n2 = klu_factor_(ap, ai, ax, sym, c); klu_free_numeric_(&n2, c); });
    double t_re = MED(klu_refactor_(ap, ai, ax, sym, num, c));
    double t_rg = MED(klu_rgrowth_(ap, ai, ax, sym, num, c));
    double t_so = MED({ for (int i = 0; i < n; i++) b[i] = 1.0; klu_solve_(sym, num, n, 1, b, c); });
    double t_ts = MED({ for (int i = 0; i < n; i++) b[i] = 1.0; klu_tsolve_(sym, num, n, 1, b, c); });
    printf("%-14s n=%6d nnz=%6d blocks=%5d lnz+unz=%8d offdiag=%6d | analyze %7.0f us | factor %7.0f us | refactor %7.0f us | rgrowth %5.0f us | solve %5.0f us | tsolve %5.0f us\n",
           argv[3], n, nnz, sym->nblocks, num->lnz + num->unz, num->nzoff, t_an, t_fa, t_re, t_rg, t_so, t_ts);
    return 0;
}
