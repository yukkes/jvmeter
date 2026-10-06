#!/bin/bash
# Overhead gate: each benchmark with the agent against no profiling, run in turns; the median of the runs' medians.
# The run without profiling starts the JVM the way -javaagent does: CDS's archived module graph off (--add-modules), so
# the ratio is the agent's own cost. Any -javaagent, even one that does nothing, turns it off, and from JDK 25 on that
# alone makes app.Load about 1.1x slower (JDK 17: 1.03x); attaching later keeps it (docs/benchmarks.md).
#   app.Bench fib(35): huge numbers of tiny calls, the worst case for counting       fails above FIB_LIMIT
#   app.Load: 4 threads that allocate and share a lock, a busy service (JFR, threads)  fails above LOAD_LIMIT
# BASE=path/to/an/older/jvmeter-agent.jar also runs that agent, to compare two builds.
# Needs ./mvnw package. Every JVM is stopped after 2 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
# Limits: one pair for this machine and GitHub Actions, on any JDK from 17. Measured 2026-10-06, RUNS=3:
#                                                  fib    load
#   GitHub ubuntu-latest, EPYC 7763, 2 vCPUs, 17   1.97x  1.07x  (single runs 1.91-1.98x, 1.04-1.10x; measured before
#                                                                 the baseline started like -javaagent, which can only
#                                                                 lower load and leaves fib alone)
#   this machine, Ryzen AI 7 350, 8 CPUs, JDK 17   1.81x  1.22x  (act -W .github/act/gates.yml)
#   the same, JDK 25                               1.82x  1.19x
# The two machines differ by CPU model, so each limit is set by the machine where that ratio is higher:
#   fib 2.0x: the call counters' cost on huge numbers of tiny calls. Highest on GitHub's slower CPU, 1.97x; 2.0x is the
#     round number just above it. A regression of a few percent fails there, about 10 % here. As GitHub's own runs spread
#     1.91-1.98x, a fib failure on GitHub alone may be the runner: rerun it once before looking for a cause.
#   load 1.3x: JFR's sampling and the agent's threads on a busy service. Highest here, 1.22x; 1.3x leaves about 7 %, just
#     above the run-to-run drift of this machine (5-7 %). On GitHub (4 threads on 2 vCPUs hide the agent's cost) only a
#     regression of about 20 % fails, so this machine, not GitHub, is the one that guards load.
RUNS=${RUNS:-3} FIB_LIMIT=${FIB_LIMIT:-2.0} LOAD_LIMIT=${LOAD_LIMIT:-1.3}
CP=demo/target/jvmeter-demo.jar OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
OPTS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
run() { timeout 120 java "${@:2}" -cp $CP $1 2>&1 | sed -n 's/.*median=\([0-9.]*\) ms.*/\1/p'; }
median() { sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }
ratio() { awk "BEGIN {printf \"%.2f\", $1 / $2}"; }
fail=0
for b in "fib app.Bench 35 30" "load app.Load 10"; do
  set -- $b
  name=$1 main="${*:2}"
  for i in $(seq "$RUNS"); do
    run "$main" --add-modules=java.management >> "$OUT/$name-none"
    run "$main" $OPTS -javaagent:agent/target/jvmeter-agent.jar -Djvmeter.out="$OUT/s.json.gz" >> "$OUT/$name-agent"
    [ -n "${BASE:-}" ] && run "$main" $OPTS -javaagent:"$BASE" -Djvmeter.out="$OUT/s.json.gz" >> "$OUT/$name-base"
  done
  none=$(median < "$OUT/$name-none") agent=$(median < "$OUT/$name-agent")
  limit=$([ "$name" = fib ] && echo "$FIB_LIMIT" || echo "$LOAD_LIMIT")
  echo "$name: median of $RUNS runs: no profiling $none ms, agent $agent ms ($(ratio "$agent" "$none")x, limit ${limit}x)"
  [ -n "${BASE:-}" ] && echo "  base agent $(median < "$OUT/$name-base") ms ($(ratio "$(median < "$OUT/$name-base")" "$none")x)"
  awk "BEGIN {exit !($agent / $none <= $limit)}" || { echo "FAIL: $name overhead above ${limit}x"; fail=1; }
done
exit $fail
