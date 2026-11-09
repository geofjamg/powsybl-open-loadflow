"""List the lines whose N-1 outage splits the main connected component (graph bridges)."""
import sys, warnings
warnings.filterwarnings("ignore")
import networkx as nx, pypowsybl as pp

n = pp.network.load(sys.argv[1])
g = nx.MultiGraph()
lines = n.get_lines(attributes=['bus1_id', 'bus2_id', 'connected1', 'connected2'])
for df in (lines, n.get_2_windings_transformers(attributes=['bus1_id', 'bus2_id', 'connected1', 'connected2'])):
    for i, r in df.iterrows():
        if r.connected1 and r.connected2 and r.bus1_id and r.bus2_id:
            g.add_edge(r.bus1_id, r.bus2_id, key=i)
h = g.subgraph(max(nx.connected_components(g), key=len))
simple, mult = nx.Graph(), {}
for u, v, k in h.edges(keys=True):
    simple.add_edge(u, v); mult.setdefault(frozenset((u, v)), []).append(k)
isl = sorted(mult[frozenset(e)][0] for e in nx.bridges(simple) if len(mult[frozenset(e)]) == 1 and mult[frozenset(e)][0] in lines.index)
open(sys.argv[2], 'w').write("\n".join(isl))
print("islanding lines:", len(isl))
