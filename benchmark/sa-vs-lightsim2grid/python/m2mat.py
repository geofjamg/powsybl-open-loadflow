"""Convert a MATPOWER .m case file into a MATPOWER .mat file (struct 'mpc') readable by powsybl."""
import re, sys
import numpy as np, scipy.io

def parse(path):
    txt = open(path).read()
    txt = re.sub(r'%.*', '', txt)
    mpc = {}
    mpc['version'] = re.search(r"mpc\.version\s*=\s*'(\d+)'", txt).group(1)
    mpc['baseMVA'] = float(re.search(r"mpc\.baseMVA\s*=\s*([\d.eE+-]+)", txt).group(1))
    for name in ['bus', 'gen', 'branch', 'gencost', 'areas']:
        m = re.search(r"mpc\.%s\s*=\s*\[(.*?)\];" % name, txt, re.S)
        if m is None:
            continue
        rows = [r.strip() for r in m.group(1).replace('\n', ';').split(';')]
        rows = [np.array(r.split(), dtype=float) for r in rows if r]
        mpc[name] = np.vstack(rows)
    return mpc

src, dst = sys.argv[1], sys.argv[2]
mpc = parse(src)
print({k: (v.shape if hasattr(v, 'shape') else v) for k, v in mpc.items()})
scipy.io.savemat(dst, {'mpc': mpc})
