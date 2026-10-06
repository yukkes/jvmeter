#!/bin/bash
# jvmeter next to JProfiler, jvmeter also sampling every 3 and 5 ms: docs/benchmarks.md shows the results.
# About 85 s per JDK (CYCLES=2).
# app.Rounds runs fib(35) or one round of app.Load, with the profiler in each of its states (jvmeter: before attach,
# attached, recording, disconnected; JProfiler: recording off, on, off again); after each change of state the rounds
# of the first 0.5 s are left out while the JIT compiles again (demo/bench.sh).
# Against noise: the machine's speed drifts by several percent over minutes, so every ratio compares runs made close in
# time. Each cycle runs every line once, between two JVMs without a profiler, and a line's ratio is against those two
# (attached: against the same JVM before attaching). The cycles run in turns; a line shows the median ratio over the
# cycles, their range, and the median round in ms.
#   JPROFILER_HOME=path/to/jprofiler15.0.4   JProfiler's Full and Async sampling; left out when unset
#   JP_INSTRUMENTATION=1                      also JProfiler's Instrumentation (290x on fib: about a minute a cycle)
#   CYCLES=2 ROUNDS=7
# Needs ./mvnw package. Every JVM is stopped after 5 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
. demo/bench.sh
CYCLES=${CYCLES:-2} ROUNDS=${ROUNDS:-7}
# the names in JProfiler's manual, and their values in its config file
types=("Full sampling:sampling" "Async sampling:async")
[ -n "${JP_INSTRUMENTATION:-}" ] && types=("Instrumentation:instrumentation" "${types[@]}")

# one JVM: "STATE<tab>MS" per state
states() { timeout 300 java $JVM "$@" 2>&1 | sed -n 's/^> \(.\{24\}\) *\([0-9.]*\) ms.*/\1\t\2/p' | sed 's/ *\t/\t/'; }
# one JVM without a profiler: its median round
base() { states -cp $CP app.Rounds "$1" "$ROUNDS" none | cut -f2; }
# one line of one cycle: "WORKLOAD<tab>LINE<tab>STATE<tab>MS<tab>RATIO"
line() {
  local w=$1 label=$2 b=$3 first=""
  shift 3
  while IFS=$'\t' read -r state ms; do
    if [ "$label" = "jvmeter, attached" ]; then
      first=${first:-$ms}
      ratio=$(awk "BEGIN {print $ms / $first}")
    else
      ratio=$(awk "BEGIN {print $ms / $b}")
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$w" "$label" "$state" "$ms" "$ratio"
  done < <(states "$@")
}

java -version 2>&1 | head -1
for c in $(seq "$CYCLES"); do
  for w in fib load; do
    b1=$(base $w)
    {
      line $w "jvmeter, attached" 0 -cp $CP app.Rounds $w "$ROUNDS" jvmeter $AGENT
      line $w "jvmeter -javaagent" "$b1" -javaagent:$AGENT -Djvmeter.out="$OUT/s.json.gz" -cp $CP app.Rounds $w "$ROUNDS" none
      for ms in 3 5; do
        line $w "jvmeter -javaagent, every $ms ms" "$b1" -javaagent:$AGENT -Djvmeter.period=$ms -Djvmeter.out="$OUT/s.json.gz" \
          -cp $CP app.Rounds $w "$ROUNDS" none
      done
      if [ -n "${JPROFILER_HOME:-}" ]; then
        for t in "${types[@]}"; do
          cat > "$OUT/jp.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<config version="10.0"><sessions><session id="1" name="bench" methodCallRecordingType="${t#*:}" autoTuneInstrumentation="false">
<filters><filter type="inclusive" name="app." /></filters></session></sessions></config>
EOF
          line $w "JProfiler, ${t%%:*}" "$b1" \
            -agentpath:"$JPROFILER_HOME/bin/linux-x64/libjprofilerti.so=port=18849,nowait,id=1,config=$OUT/jp.xml" \
            -cp $CP app.Rounds $w "$ROUNDS" jprofiler
        done
      fi
    } > "$OUT/cycle"
    b2=$(base $w)
    # the ratios against the mean of the runs before and after, so a drift during the cycle cancels out
    printf '%s\tNo profiler\tno profiler\t%s\t1\n' $w "$(awk "BEGIN {print ($b1 + $b2) / 2}")"
    awk -F'\t' -v OFS='\t' -v f="$(awk "BEGIN {print 2 * $b1 / ($b1 + $b2)}")" \
      '{ if ($2 != "jvmeter, attached") $5 = $5 * f; print }' "$OUT/cycle"
  done
done > "$OUT/all"

# per line and state: the median ratio [range over the cycles], the median ms
awk -F'\t' -v cycles="$CYCLES" '
  function median(a, n) { asort(a); return n % 2 ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2 }
  { k = $1 SUBSEP $2 SUBSEP $3; if (!(k in n)) order[++m] = k; i = ++n[k]; r[k, i] = $5; t[k, i] = $4 }
  END { for (j = 1; j <= m; j++) { k = order[j]; split(k, p, SUBSEP); lo = 1e9; hi = 0
          for (i = 1; i <= n[k]; i++) { x[i] = r[k, i]; y[i] = t[k, i]; lo = x[i] < lo ? x[i] : lo; hi = x[i] > hi ? x[i] : hi }
          if (p[1] != w) { print "## " p[1]; w = p[1]; last = "" }
          printf "  %-36s %-24s %6.2fx  [%.2f-%.2f]  %7.1f ms\n", (p[2] == last ? "" : p[2]), p[3], median(x, n[k]), lo, hi, median(y, n[k])
          last = p[2]; delete x; delete y } }' <(sort -s -t$'\t' -k1,1 "$OUT/all")
