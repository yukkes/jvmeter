#!/bin/bash
# Why JProfiler's sampling looks free, and why -javaagent costs more than attaching: docs/benchmarks.md
# ("Why sampling looks free") shows the results. About 45 s.
#  1. Sampling: wall and CPU time per round, for JFR's execution samples alone and JProfiler's sampling,
#     on fib with all CPUs and with one, and on app.Load.
#  2. Loading: the map workload (app.Load's HashMap code, one thread) with an agent that does nothing, JFR started with
#     the JVM or later, and the JVM's startup changed the way an agent changes it; then the inlining that explains it.
#   JPROFILER_HOME=path/to/jprofiler15.0.4   JProfiler rows; left out when unset
#   RUNS=1 (demo/bench.sh)
# Needs ./mvnw package. Every JVM is stopped after 5 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
. demo/bench.sh
JFR="-XX:StartFlightRecording:filename=$OUT/r.jfr,settings"
SAMPLES="$JFR=none,+jdk.ExecutionSample#enabled=true,+jdk.ExecutionSample#period"
jp() {
  cat > "$OUT/jp.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<config version="10.0"><sessions><session id="1" name="bench" methodCallRecordingType="$1">
<filters><filter type="inclusive" name="app." /></filters></session></sessions></config>
EOF
  echo "-agentpath:$JPROFILER_HOME/bin/linux-x64/libjprofilerti.so=port=18849,nowait,id=1,config=$OUT/jp.xml"
}
# an agent that does nothing
printf 'Premain-Class: app.EmptyAgent\n' > "$OUT/manifest"
(cd demo/target/classes && jar cfm "$OUT/empty-agent.jar" "$OUT/manifest" app/EmptyAgent.class)
java -version 2>&1 | head -1

echo "# 1. Sampling"
for PIN in "" "taskset -c 0"; do
  echo "## fib, ${PIN:-all CPUs}"
  run "No profiler" -cp $CP app.Rounds fib 7 none
  run "JFR ExecutionSample every 1 ms" "$SAMPLES=1ms" -cp $CP app.Rounds fib 7 none
  [ -n "${JPROFILER_HOME:-}" ] && run "JProfiler, Full sampling" "$(jp sampling)" -cp $CP app.Rounds fib 7 jprofiler
done
PIN=""
echo "## load"
run "No profiler" -cp $CP app.Rounds load 7 none
run "JFR ExecutionSample every 1 ms" "$SAMPLES=1ms" -cp $CP app.Rounds load 7 none
run "JFR ExecutionSample every 5 ms" "$SAMPLES=5ms" -cp $CP app.Rounds load 7 none
[ -n "${JPROFILER_HOME:-}" ] && run "JProfiler, Async sampling" "$(jp async)" -cp $CP app.Rounds load 7 jprofiler

echo "# 2. Loading (map, nothing recorded unless noted)"
run "No profiler" -cp $CP app.Rounds map 7 none
run "No CDS module graph (--add-modules)" --add-modules=java.management -cp $CP app.Rounds map 7 none
run "An agent that does nothing (-javaagent)" -javaagent:"$OUT/empty-agent.jar" -cp $CP app.Rounds map 7 none
run "JFR started with the JVM, no events" "$JFR=none" -cp $CP app.Rounds map 7 none
run "JFR started after warming up, no events" -cp $CP app.Rounds map 7 jfr
run "jvmeter -javaagent, recording" -javaagent:$AGENT -Djvmeter.out="$OUT/s.json.gz" -cp $CP app.Rounds map 7 none
run "jvmeter, attached" -cp $CP app.Rounds map 7 jvmeter $AGENT
echo "## HashMap.hash: places where C2 inlined Integer.hashCode (-XX:+PrintInlining, app.Parts)"
for c in "No profiler:" "An agent that does nothing:-javaagent:$OUT/empty-agent.jar" "JFR started with the JVM:$JFR=none"; do
  printf "  %-44s %s\n" "${c%%:*}" "$(timeout 300 java ${c#*:} -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -cp $CP app.Parts 2>&1 |
    grep -c 'Integer::hashCode.*inline (hot)' || true)"
done
echo "## CDS with -javaagent (-Xlog:cds)"
java -Xlog:cds -javaagent:"$OUT/empty-agent.jar" -cp $CP app.Rounds fib 1 none 2>&1 | grep -E "full module graph" | sed 's/^/  /'
