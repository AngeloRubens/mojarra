#!/usr/bin/env bash
# Runs test/perf with one Mojarra and two builds of an EL implementation, in the order A B C C B A:
# A = EL built from $A_REF, B and C = EL built from $B_REF (C is a replicate of B, as noise reference).
# Env: EL (tomcat | expressly), A_REF, B_REF, WARMUP, RUNS, SCENARIOS, OUT.
set -euo pipefail
mkdir -p "$OUT"
benchmarks/ci/build_el.sh "$EL" "$A_REF" "$RUNNER_TEMP/el-A"
benchmarks/ci/build_el.sh "$EL" "$B_REF" "$RUNNER_TEMP/el-B"
mvn -B -q -P '!glassfish' -pl impl -am install -DskipTests -Dmaven.javadoc.skip=true

run_arm() { # $1 = arm name, $2 = round
    local name=$1 round=$2 lib="$RUNNER_TEMP/el-B" dump=""
    [ "$name" = A ] && lib="$RUNNER_TEMP/el-A"
    [ "$round" = 1 ] && dump="-Dperf.dumpDir=$OUT/dump-$name"
    echo "::group::arm $name round $round ($lib)"
    local status=0
    (cd test/perf && mvn -B clean verify -Ptomcat -Dperf=true -Dperf.warmup="$WARMUP" -Dperf.runs="$RUNS" \
        -Dperf.scenarios="$SCENARIOS" -Dperf.tomcatLib="$lib" $dump) || status=$?
    if [ $status -ne 0 ]; then
        for f in test/perf/target/apache-tomcat-*/logs/*; do echo "== $f"; grep -B2 -A40 -E "SEVERE|Exception" "$f" | head -120 || true; done
    fi
    echo "::endgroup::"
    [ $status -eq 0 ] || exit $status
    cp test/perf/target/perf-stats-*.txt "$OUT/$name-$round.txt"
}

run_arm A 1; run_arm B 1; run_arm C 1
run_arm C 2; run_arm B 2; run_arm A 2

for arm in A B C; do
    python3 benchmarks/ci/merge_stats.py "$OUT/$arm.txt" "$OUT/$arm-1.txt" "$OUT/$arm-2.txt"
done
