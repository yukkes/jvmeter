# Sourced by demo/profilers.sh and demo/sampling.sh: one measurement, repeated in RUNS fresh JVMs.
# A program prints "> NAME(24 columns) VALUE ms [... CPU VALUE ms]" per state; run prints, per state, the median
# over the JVMs, with RUNS > 1 the range [lowest-highest], and the median CPU time. One JVM is the default: from one JVM
# to the next, fib and map vary by up to 5 % and app.Load by up to 10 % (RUNS=3), so smaller differences are noise.
# The heap is fixed (-Xms = -Xmx), so heap sizing does not change from one JVM to the next.
RUNS=${RUNS:-1}
CP=demo/target/jvmeter-demo.jar AGENT=agent/target/jvmeter-agent.jar OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
JVM="-Xms1g -Xmx1g -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+EnableDynamicAgentLoading"
run() {
  local label=$1
  shift
  for _ in $(seq "$RUNS"); do timeout 300 ${PIN:-} java $JVM "$@" 2>&1 | sed -n 's/^> //p'; done |
    awk -v label="$label" -v runs="$RUNS" '
      function median(a, n) { asort(a); return n % 2 ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2 }
      { name = substr($0, 1, 24); sub(/ +$/, "", name); split(substr($0, 25), f, " ")
        if (!(name in n)) order[++k] = name
        v[name, ++n[name]] = f[1]; c[name, n[name]] = match($0, /CPU +[0-9.]+/) ? substr($0, RSTART + 4) + 0 : -1 }
      END { for (j = 1; j <= k; j++) { s = order[j]; m = n[s]; lo = 1e18; hi = 0
              for (i = 1; i <= m; i++) { x[i] = v[s, i]; y[i] = c[s, i]; lo = x[i] < lo ? x[i] : lo; hi = x[i] > hi ? x[i] : hi }
              cpu = median(y, m); med = median(x, m)
              printf "  %-44s %-24s %8.1f ms%s%s\n", (j == 1 ? label : ""), s, med,
                (runs > 1 ? sprintf("  [%.1f-%.1f]", lo, hi) : ""), (cpu >= 0 ? sprintf("  CPU %.1f ms", cpu) : "")
              delete x; delete y } }'
}
