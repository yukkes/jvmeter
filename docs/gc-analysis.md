# GC analysis

GCeasy-style GC metrics, computed from JFR events instead of GC logs.

![Memory › GC analysis](figures/gc-analysis.png)

## 1. Why from JFR, not from GC logs

[GCeasy](https://gceasy.io/) analyzes uploaded GC logs into KPIs (throughput, pauses, footprint) and problems;
[GCViewer](https://github.com/chewiebug/GCViewer) does it locally. jvmeter computes the same metrics from JFR events instead:
no log setup, nothing sent outside (GC logs contain host names and JVM arguments), live while recording,
and on the same time axis as the CPU and thread views. "Copy GC log" still gives unified-logging text for those tools.

## 2. What we adopt

We adopt GCeasy's **metrics and rules**, not the **service** itself.

| GCeasy feature | jvmeter | Source (JFR) |
|---|---|---|
| Throughput | Yes. Warn below 95 % | sum of `jdk.GarbageCollection.sumOfPauses` ÷ recording time |
| Average / max pause, pause distribution | Yes (plus p95) | `sumOfPauses`, `longestPause` |
| GC causes | Yes (count, total, average, max) | `jdk.GarbageCollection.name`, `cause` |
| Heap before / after GC | Yes. A regression line over the after-GC values; its slope flags leaks | `jdk.GCHeapSummary` (`when` = Before GC / After GC) |
| Generation sizes (committed / peak) | Yes | committed: the largest seen per memory pool (`MemoryPoolMXBean`: Eden + Survivor, Old, Metaspace); peak: `jdk.GCHeapSummary`, `jdk.G1HeapSummary`, `jdk.MetaspaceSummary` |
| Allocation / promotion rate | Yes | deltas of `jdk.ThreadAllocationStatistics` / growth of Old after GC |
| Problem detection | Yes, **rule-based** (table below) | combinations of the above |
| AI / ML advice, PDF, comparing multiple logs | No | — |
| Uploading logs for analysis | No. Use "Copy GC log" and paste it manually instead | `-Xlog:gc*` |

### Problem rules

| Rule | Condition | Shown as |
|---|---|---|
| Possible leak | Positive slope of the after-GC regression line, and growth over the recording ≥ 3 % of the max heap | Red, with a link to the class diff |
| Low throughput | Throughput < 95 % | Red |
| Long pauses | Max pause ≥ 200 ms, or p95 ≥ 100 ms | Yellow |
| Explicit GC | At least one GC caused by `System.gc()` | Yellow, with a link to the caller in the call tree |
| Full GC burst | 3 or more Full GCs within one minute | Red |
| Humongous allocations | At least one GC caused by `G1 Humongous Allocation` | Blue (info) |
| Metaspace pressure | Metaspace peak ≥ 90 % of `-XX:MaxMetaspaceSize` (`generations.metaspaceMaxMB`; without a limit, the default, it cannot fill up) | Yellow |
| Heap stays full | In the second half of the recording, at least 3 GCs, and every one leaves ≥ 80 % of the max heap in use (the live data is close to `-Xmx`) | Red |
| Unrequested Full GC | G1, ZGC or Shenandoah ran a Full GC that was not `System.gc()`, a diagnostic command or a heap dump / inspection, and there was no Full GC burst | Yellow |
| Premature promotion | Recording ≥ 60 s, promotion ≥ 1 MB/s and ≥ 20 % of the allocation rate | Yellow |
| Metaspace growth | Metaspace used (after GC) grew by ≥ 20 MB and ≥ 20 % over ≥ 60 s: class loaders created again and again | Yellow |

Rules marked as coming from allocation (low throughput, Full GC burst, heap stays full, unrequested Full GC, long pauses not caused by
`System.gc()`, premature promotion) add a line to "Where to look" naming the application method that allocates most ("Who allocates",
from JFR `jdk.ObjectAllocationSample`, is on the same tab). The top problem is also the GC line of the Overview's "Where to look" (`core` `Findings`), and every problem except info is a line of the
snapshot's `summary.findings`. `FindingsTest` checks the rules against the prototype on made-up GC logs that set off each of them.

## 3. Screen

A "GC analysis" tab on the Memory view, keeping the four-view layout. Like the CPU view it fills the window:
a main column that scrolls on its own (items 1 and 3–6) and a side panel with the detected problems (2).

1. **Five KPI tiles**: throughput / average pause (p95) / max pause / allocation rate / promotion rate,
   with an icon showing whether each meets its target.
2. **Detected problems** (side panel, where the CPU view shows the method details): one card per rule hit,
   each with the next place to look (class diff, caller, …).
3. **Heap (before / after GC)**: the sawtooth of usage, after-GC points, their regression line and slope,
   -Xmx / -Xms lines, and Full GC markers on the time axis.
4. **Pause distribution** (0–10 / 10–20 / 20–50 / 50–100 / 100–200 / 200+ ms).
5. **GC causes** (count, total, average, max), beside the pause distribution.
6. **Generation sizes** (peak against committed), Young / Old / Metaspace side by side.
7. **Copy GC log** (top right) for GCeasy / GCViewer.

The Overview's "Where to look" shows the most severe problem in one line.

## 4. Snapshot format

```json
"gc": {
  "collector": "G1",
  "heapMaxMB": 1024,
  "generations": { "youngMB": 410, "oldMB": 614, "metaspaceMB": 104 },   // committed; + "metaspaceMaxMB" when limited
  "events": [
    { "t": 12.48, "name": "Young", "cause": "G1 Evacuation Pause", "pauseMs": 14.2,
      "beforeMB": 702, "afterMB": 301, "oldAfterMB": 210, "metaspaceMB": 88 }
  ]
}
```

KPIs, the distribution, causes, slope and problems are all computed from `events` by `core` (`GcAnalysis`) and the prototype.
As with `cpu.tree`, only raw data is stored.

## References

- [GCeasy](https://gceasy.io/)
- [GCeasy: GC KPIs](https://blog.gceasy.io/garbage-collection-kpi/)
- [GCeasy: REST API](https://blog.gceasy.io/garbage-collection-log-analysis-api/)
- [GCViewer (GitHub)](https://github.com/chewiebug/GCViewer)
