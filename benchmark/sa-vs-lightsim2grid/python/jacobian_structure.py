"""Structure analysis of exported Jacobians (raw CSC files written by ExportJ / ls_export_jacobian).

For each file: pattern symmetry, number of structurally zero diagonal entries.
With --match: writes <file>.matched.bin, the same matrix with rows permuted by a maximum-weight
matching on |J| (each equation re-aligned with "its" variable), to be timed with native/klubench.
"""
import sys
import numpy as np
import scipy.sparse as sp
from scipy.sparse.csgraph import min_weight_full_bipartite_matching


def load(path):
    b = open(path, 'rb').read()
    n, nnz = np.frombuffer(b[:8], '<i4')
    o = 8
    ap = np.frombuffer(b[o:o + 4 * (n + 1)], '<i4'); o += 4 * (n + 1)
    ai = np.frombuffer(b[o:o + 4 * nnz], '<i4'); o += 4 * nnz
    ax = np.frombuffer(b[o:o + 8 * nnz], '<f8')
    return sp.csc_matrix((ax, ai, ap), shape=(n, n))


def save(m, path):
    m = m.tocsc(); m.sort_indices(); n = m.shape[0]
    open(path, 'wb').write(np.array([n, m.nnz], '<i4').tobytes() + m.indptr.astype('<i4').tobytes()
                           + m.indices.astype('<i4').tobytes() + m.data.astype('<f8').tobytes())


def stats(m):
    p = (m != 0).astype(int)
    return p.multiply(p.T).nnz / p.nnz, int((m.diagonal() == 0).sum())


if __name__ == '__main__':
    match = '--match' in sys.argv
    for f in [a for a in sys.argv[1:] if not a.startswith('--')]:
        m = load(f)
        sym, zd = stats(m)
        print(f"{f}: n={m.shape[0]} nnz={m.nnz} pattern symmetry={sym:.4f} zero diagonal={zd}")
        if match:
            r = m.tocsr()
            w = r.copy()
            la = np.log(np.abs(w.data) + 1e-300)
            w.data = la.max() + 1.0 - la
            rows, cols = min_weight_full_bipartite_matching(w)
            perm = np.empty(m.shape[0], int); perm[cols] = rows
            a = r[perm]
            sym, zd = stats(a)
            print(f"  after row matching: pattern symmetry={sym:.4f} zero diagonal={zd}")
            save(a, f + '.matched.bin')
