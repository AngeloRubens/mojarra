#!/usr/bin/env bash
# Runs test/perf for three arms in the symmetric order A B C C B A on one machine, so that linear drift of the
# machine's speed cancels out:  A = baseline impl, B = this branch with defaults, C = this branch with $OPTIONS.
# Env: BASELINE_DIR (checkout of the baseline), WARMUP, RUNS, SCENARIOS, OPTIONS, OUT.
set -euo pipefail

REPO=$(pwd)
WEB_XML=test/perf/src/main/webapp/WEB-INF/web.xml
cp "$WEB_XML" "$RUNNER_TEMP/web.xml.orig"
mkdir -p "$OUT"

install_impl() { # $1 = repo dir
    mvn -B -q -f "$1/pom.xml" -P '!glassfish' -pl impl -am install -DskipTests -Dmaven.javadoc.skip=true
}

run_arm() { # $1 = arm name, $2 = round
    local name=$1 round=$2 jvm="-Xmx1g" params=""
    cp "$RUNNER_TEMP/web.xml.orig" "$WEB_XML"
    if [ "$name" = C ]; then
        case " $OPTIONS " in *" prerender "*) params="$params<context-param><param-name>com.sun.faces.preRenderLiteralMarkup</param-name><param-value>true</param-value></context-param>";; esac
        case " $OPTIONS " in *" utf8 "*) params="$params<context-param><param-name>com.sun.faces.utf8ResponseBuffer</param-name><param-value>true</param-value></context-param>";; esac
        case " $OPTIONS " in *" vector "*) jvm="$jvm --add-modules jdk.incubator.vector";; esac
        case " $OPTIONS " in *" nopath "*) jvm="$jvm -Dcom.sun.faces.disablePathExpressions=true -Dcom.sun.faces.disableELResolverShortcuts=true";; esac
        sed -i "s#</web-app>#    $params\n</web-app>#" "$WEB_XML"
    fi
    local dump=""
    if [ "$round" = 1 ]; then dump="-Dperf.dumpDir=$OUT/dump-$name"; fi
    echo "::group::arm $name round $round (jvm: $jvm)"
    local status=0
    (cd test/perf && mvn -B clean verify -Ptomcat -Dperf=true -Dperf.warmup="$WARMUP" -Dperf.runs="$RUNS" \
        -Dperf.scenarios="$SCENARIOS" -Dperf.jvmArguments="$jvm" $dump) || status=$?
    if [ $status -ne 0 ]; then
        for f in test/perf/target/apache-tomcat-*/logs/*; do echo "== $f"; grep -B2 -A40 -E "SEVERE|Exception" "$f" | head -120 || true; done
    fi
    echo "::endgroup::"
    cp "$RUNNER_TEMP/web.xml.orig" "$WEB_XML"
    [ $status -eq 0 ] || exit $status
    cp test/perf/target/perf-stats-*.txt "$OUT/$name-$round.txt"
}

install_impl "$BASELINE_DIR"; run_arm A 1
install_impl "$REPO";         run_arm B 1; run_arm C 1
                              run_arm C 2; run_arm B 2
install_impl "$BASELINE_DIR"; run_arm A 2

for arm in A B C; do
    python3 benchmarks/ci/merge_stats.py "$OUT/$arm.txt" "$OUT/$arm-1.txt" "$OUT/$arm-2.txt"
done
