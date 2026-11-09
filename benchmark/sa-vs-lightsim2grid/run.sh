#!/usr/bin/env bash
# Reproduce the OpenLoadFlow vs lightsim2grid N-1 benchmark. Usage: ./run.sh <work dir>
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$HERE/../.." && pwd); W=${1:-/tmp/sa-bench}; mkdir -p "$W/data" "$W/classes"
CASE=case6515rte

# 1. Python env (lightsim2grid 1.3.0, pypowsybl 1.16.1 were used)
[ -d "$W/venv" ] || { python3 -m venv "$W/venv"; "$W/venv/bin/pip" install -q lightsim2grid pypowsybl networkx scipy; }
PY="$W/venv/bin/python -I"

# 2. MATPOWER .m -> .mat -> IIDM (powsybl MATPOWER importer)
[ -f "$W/data/$CASE.m" ] || curl -sSfL -o "$W/data/$CASE.m" https://raw.githubusercontent.com/MATPOWER/matpower/master/data/$CASE.m
$PY "$HERE/python/m2mat.py" "$W/data/$CASE.m" "$W/data/$CASE.mat"
$PY -c "import pypowsybl as pp; pp.network.load('$W/data/$CASE.mat').save('$W/data/$CASE.xiidm', format='XIIDM')"
$PY "$HERE/python/islanding_lines.py" "$W/data/$CASE.xiidm" "$W/islanding_lines.txt"

# 3. lightsim2grid: base case check vs OLF + N-1
$PY "$HERE/python/ls_base.py" "$W/data/$CASE.xiidm" "$W/ls_J.bin"
$PY "$HERE/python/ls_n1.py" "$W/data/$CASE.xiidm" nr,nr_tight,nr_island,nr_island_t4,outer_island "$W/ls_results.json"

# 4. OpenLoadFlow (this repo) N-1
(cd "$REPO" && mvn -q -B install -DskipTests && mvn -q -B dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$W/cp.txt")
CP="$REPO/target/classes:$(cat "$W/cp.txt")"
javac -proc:none -d "$W/classes" -cp "$CP" $(find "$HERE/java" -name '*.java')
JAVA="java -Xmx8g -Dlogback.configurationFile=$HERE/java/logback.xml -cp $CP:$W/classes"
$JAVA bench.OlfN1Bench "$W/data/$CASE.xiidm" nr_count,nr_novec_count,default_count,default_novec_count,nr_t4 1 | grep -E 'RESULT|KLU'
$JAVA -Dexclude="$W/islanding_lines.txt" bench.OlfN1Bench "$W/data/$CASE.xiidm" nr_count,nr_novec_count 1 | grep -E 'RESULT|KLU'
$JAVA bench.OpCost "$W/data/$CASE.xiidm"

# 5. Same KLU, same matrices: powsybl-math-native vs lightsim2grid KLU builds
$JAVA bench.ExportJ "$W/data/$CASE.xiidm" "$W/olf_J.bin"
$JAVA -Dnovec=true bench.ExportJ "$W/data/$CASE.xiidm" "$W/olf_novec_J.bin"
$PY "$HERE/python/jacobian_structure.py" --match "$W/olf_J.bin" "$W/olf_novec_J.bin" "$W/ls_J.bin"
SS=$("$W/venv/bin/python" -c "import lightsim2grid,os;print(os.path.dirname(lightsim2grid.__file__))")
gcc -O2 -I"$SS/include/suitesparse" -o "$W/klubench" "$HERE/native/klubench.c" -ldl
unzip -o -q "$(tr ':' '\n' < "$W/cp.txt" | grep powsybl-math-native)" 'natives/linux_64/*' -d "$W/pmn"
for lib in "$W/pmn/natives/linux_64/libmath.so" "$SS/liblightsim2grid_core.so"; do
  for m in olf_J.bin olf_J.bin.matched.bin olf_novec_J.bin ls_J.bin; do f="$W/$m"; "$W/klubench" "$lib" "$f" "$(basename "$lib"):$m"; done
done
