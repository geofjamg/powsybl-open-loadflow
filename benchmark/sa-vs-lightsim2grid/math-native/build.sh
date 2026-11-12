#!/usr/bin/env bash
# Build an improved powsybl-math-native (v1.5.0 + perf patch) shared library.
# Usage: ./build.sh <work dir>   -> <work dir>/natives/linux_64/libmath.so
# Put <work dir> in front of the classpath to use it instead of the one of the powsybl-math-native jar.
# GCC 14 is used as for the official build (GCC 13 gives a noticeably slower KLU).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); W=${1:-/tmp/math-native}; mkdir -p "$W"; cd "$W"
[ -d pmn ] || git clone -q --depth 1 --branch v1.5.0 https://github.com/powsybl/powsybl-math-native.git pmn
[ -d suitesparse ] || git clone -q --depth 1 --branch v7.3.1 https://github.com/DrTimothyAldenDavis/SuiteSparse.git suitesparse
[ -d sundials ] || git clone -q --depth 1 --branch v6.6.2 https://github.com/LLNL/sundials.git sundials
(cd suitesparse && git apply --check ../pmn/patches/suitesparse.patch 2>/dev/null && git apply ../pmn/patches/suitesparse.patch || true)
(cd pmn && git apply --check "$HERE/powsybl-math-native-v1.5.0-perf.patch" 2>/dev/null && git apply "$HERE/powsybl-math-native-v1.5.0-perf.patch" || true)
mkdir -p pmn/build && cd pmn/build
CC=${CC:-gcc-14} CXX=${CXX:-g++-14} cmake .. -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DFETCHCONTENT_SOURCE_DIR_SUITESPARSE="$W/suitesparse" -DFETCHCONTENT_SOURCE_DIR_SUNDIALS="$W/sundials" \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON > /dev/null
ninja math > /dev/null
mkdir -p "$W/natives/linux_64" && cp ../target/classes/natives/linux_64/libmath.so "$W/natives/linux_64/"
echo "built $W/natives/linux_64/libmath.so"
