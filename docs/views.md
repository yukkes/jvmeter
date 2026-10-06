# Views: the HTML prototype (`demo/prototype.html`) and the GUI

`demo/prototype.html` is the HTML prototype of the GUI: it opens in a browser with no build, and can also open a snapshot (JSON).
It loads Inter from Google Fonts (offline it falls back to the system font); the icons (Material Symbols) are inline SVG. It is laid out for 1280 px and up; on wide windows the
content stops at 1600 px.

**Start Center.** Like JProfiler, both the prototype and the GUI open the Start Center first (the GUI does not when it is given a snapshot file).
On the left: Local, SSH, Kubernetes, Open snapshot and Sample data (a fictional order service).
It opens where it left off: the kind of place used last, with its fields filled in, listing the JVMs there at once and selecting
the main class connected to last time (matched by class, as the pid has usually changed), so Connect is one click. When no JVM or several
run that class, the list says so. Only Local and places used before list on their own; elsewhere nothing runs until List JVMs.
The SSH Host field suggests the hosts used before and the Host entries of `~/.ssh/config`; a Kubernetes pod that a rollout replaced
falls back to a pod of the same Deployment. The last connection is kept in the browser's storage (the GUI: its preferences).
There is no list of recent sessions: a remembered pod name goes stale with every rollout, and a connection has nothing else worth saving.
Local / SSH / Kubernetes take where the JVM runs, list its JVMs (with an agent that is already running), and attach or connect;
the commands jvmeter runs and the startup option are shown below ([`targets.md`](targets.md)).
Closed without a choice, the window shows "No JVM connected" with the same three ways in; the views stay disabled.
Below the list, **Count calls in** takes the packages whose calls are counted (the main class's package, or what was used for
that main class last time); CPU time is sampled in every method either way.
In the GUI, Local lists the real JVMs of this user and attaches the agent; connecting starts a recording, and the views fill in
every second. SSH works the same over the user's ssh, and Kubernetes over the user's kubectl: the Context, Namespace and Pod fields offer what
kubectl reports (running pods only), and Container the pod's containers. The prototype's lists are
sample data; `prototype.html#sample` opens the sample directly.
The Swing GUI (`gui/`) has the same views and behavior and is checked against this page ([`java-gui.md`](java-gui.md)).

**Four views**

| View | What you can do | Why it is there |
|---|---|---|
| Overview | Tiles for CPU / heap / GC / threads, and "Where to look": the bottlenecks found in the data, most severe first (below). Tiles and lines open the detailed views | The first stop to find where the problem is, without an LLM |
| CPU | Hot spots (sortable columns, callers) and Call tree (expand/collapse, expand the hot path), search | Answers "what is slow?" directly |
| Memory › GC analysis (opens first) | GCeasy-style: throughput / avg · p95 · max pause / allocation and promotion rates, rule-based problems, heap before/after GC with trend, pause distribution, GC causes, generation sizes, who allocates (JFR allocation samples by application method and class), copy the GC log (unified logging) | Tells whether GC causes latency or a leak, without any log setup ([`gc-analysis.md`](gc-analysis.md)) |
| Memory › Heap & classes | Heap over time (with Full GC markers, -Xmx / -Xms lines), classes by count and size, Before / After reference points with diffs, Run GC | The minimum leak-hunting loop (mark → wait → GC → see what grew) |
| Threads | Per-thread state timeline, where threads waited (place, lock or I/O, who held the lock, total), time by state, current stack | Tells lock waits apart from I/O waits, and which lock and who holds it |

The top bar reads as a breadcrumb, `jvmeter / target`: the target JVM's name, with its pid · JVM and where it runs (an SSH host,
or context/namespace/pod) below; clicking it (the chevron) opens the Start Center again. With no JVM it reads "Connect to a JVM".
The recording button shows the time recorded and **starts and stops recording** (a red dot to start; while recording it is tinted
red, with a red dot and a stop square). Frameless icon buttons open and **save a snapshot** (the GUI writes `.json.gz` with `about`
and `summary`; the prototype downloads the raw JSON) and switch between light and dark mode (the choice is remembered in the
browser; by default it follows the OS). On the sample, a "Sample data" label (a Material 3 tonal container: filled, no outline, not a button) says it is not a real recording.
Calls are counted exactly by instrumentation and time comes from JFR sampling ([`design.md`](design.md)); there is no mode switch.
Methods seen only in samples (outside the counted packages) have no call count: Calls and Average show "—".
On the CPU view, the right panel shows the selected method's Self / Total / calls / per-call time, its time breakdown
(Self and top callees) and its callers, and "Show in call tree" expands the path to it.
The heap charts use the time of day on the x axis.

**Where to look** (`core` `Findings`, `findings()` in the prototype) lists, most severe first (bad, warn, then the CPU line, then what looks fine):
the lock threads waited for longest, who held it and in which application method; a class that grew while the heap after GC grew
(class histograms count unreachable objects too, so growth alone is no leak; the growth since Before when it is set);
the top GC problem, and who allocates most when that problem comes from allocation; the application method that waited longest for I/O (a server waiting in `accept` or a selector waits for work);
CPUs saturated or a growing thread count; and the application method using the most CPU, with the library code it calls counted for it
(so `PriceCalculator.calculate` shows instead of `BigDecimal.multiply`). The application's code is `target.include`, the counted packages
(without it: everything outside the JDK). The snapshot's `summary.findings` holds the same lines as text.

**Left out on purpose**: heap walker (reference graphs), lock graphs, probes (JDBC and so on), triggers,
complexity / call tracer / outlier detection, MBeans. The agent (`agent/`) cannot provide the data yet,
or they are not essential for a first investigation.

**Snapshot format** (open it with the folder icon). The agent writes it gzip-compressed (`.json.gz`);
plain `.json` opens too. Reading it by hand or with an LLM: `zcat file.json.gz | head -80`.

```json
{
  "format": "jvmeter-snapshot/1",
  "about": [ "what the fields and units mean" ],
  "summary": { "cpu": { "hotSpotsBySelfTime": [], "hottestPath": [] }, "gc": {}, "threads": {}, "memory": {}, "findings": [] },
  "target": { "name": "app.Fib", "pid": 12345, "jvm": "OpenJDK 25", "include": "app" },
  "startedAt": "2026-10-04T14:30:00+09:00",
  "durationSec": 120,
  "cpu": { "tree": { "name": "All threads", "self": 0, "calls": 0, "children": [
    { "name": "app.Fib.fibRecursive", "self": 53100, "calls": 1197513491, "children": [] } ] },
          "methods": [ { "name": "app.Fib.fibRecursive", "calls": 1197513491 } ] },
  "memory": { "heapMaxMB": 512, "xmsMB": 256, "classes": [ { "name": "byte[]", "count": 1200, "bytes": 380000 } ],
              "history": { "t": [30, 60], "classes": ["byte[]"], "count": [[1100], [1200]], "bytes": [[350000], [380000]] } },
  "telemetry": { "t": [1, 2], "n": [1, 1], "cpu": [41.5, 38.2], "heap": [212.4, 230.9], "threads": [24, 24] },
  "threads": [ { "name": "main", "segs": [[0, 120, "run"]], "stack": ["app.Fib.fibRecursive"],
                 "sec": { "run": 118.4, "wait": 0, "block": 1.6, "io": 0 } } ],
  "waits": [ { "state": "block", "site": "app.Cache.get", "lock": "app.Cache@3c4d", "owner": "worker-2", "top": "app.Cache.get",
               "sec": 1.6, "threads": ["main"] } ],
  "gc": { "collector": "G1", "heapMaxMB": 512,
          "generations": { "youngMB": 200, "oldMB": 312, "metaspaceMB": 64, "metaspaceMaxMB": 256 },
          "events": [ { "t": 1.25, "name": "Young", "cause": "G1 Evacuation Pause", "pauseMs": 6.4,
                        "beforeMB": 240, "afterMB": 52, "oldAfterMB": 30, "metaspaceMB": 21 } ] }
}
```

- `format`, `about` and `summary` come first for readers. `summary` is derived from the data below it and is ignored when the file is loaded.
- `cpu.tree` times are in ms. Each node holds only `self` and `calls`; totals and hot spots are computed by `core` (and the prototype).
- `cpu.methods` is optional: the exact call count per method from instrumentation. Sampled tree nodes cannot count calls, so these override them.
- `gc.events[].manual` is `true` for a GC the GUI's Run GC started: it shows as "System.gc() (Run GC)" and is left out of the System.gc() rule.
- `target.agent` (`startup` | `attach`), `target.debugNonSafepoints` and `target.via` (where the JVM runs) are optional ([`targets.md`](targets.md)).
  `target.debugNonSafepoints` is `false` when time in inlined methods is attributed to their callers; the top bar then shows "Inlined → callers".
- `startedAt` is when the recording started (ISO 8601). The heap and GC charts use it to show the time of day.
  Without it, the start is derived from the time the file was opened.
- `memory.xmsMB` is optional; it adds the -Xms line to the heap charts.
- `telemetry`: process CPU %, heap used (MB) and live threads, one point per second. `t` is the second a point ends at, `n` the seconds it averages.
- **Older data is coarser**, so hours of recording stay small: telemetry, class histories and thread segments keep 1 s for the last 10 minutes,
  10 s up to an hour, 1 min up to 6 hours, then 10 min (`core` `Retention`; the prototype does the same while recording the sample).
  GC events are all kept. The charts show the whole recording; the Overview's sparklines show the last 10 minutes.
- `memory.history`: class histograms over time, for Before / After in the past (the agent takes one every 30 s while recording, `-Djvmeter.histogram`).
- `memory.allocations`: `{site, cls, mb}`, MB of a class allocated at a site (the first frame in the application's code, else the top frame),
  estimated from JFR `jdk.ObjectAllocationSample` (throttled to 150 a second; each sample weighs what its thread allocated since the last one).
  The 100 largest. Optional.
- `memory.classes`: the 300 largest classes by size, from `GC.class_histogram -all` (as `jcmd` runs it) at the end of the recording.
- `threads[].segs` is `[start s, end s, "run" | "wait" | "block" | "io"]`. Optional. The agent samples thread states every 100 ms
  (`ThreadMXBean`; RUNNABLE in a native socket or file call counts as `io`, but `accept` and selectors as `wait`; parked on a lock another thread owns as `block`); each second takes the state seen most,
  and `sec` holds the seconds per state summed per sample, so blocks shorter than a second still count. `stack` is the latest one, top first.
- `target.include`: the packages whose calls are counted, the application's own code for "Where to look". Optional.
- `waits`: where threads waited, summed from the same 100 ms thread samples: `state` is `block` (a monitor, or a lock such as `ReentrantLock`
  that another thread owns, whose name is `owner`) or `io`; `site` is the first frame in the application's code (else the top frame), `top` the top frame.
  At most 50, longest first. Optional (older snapshots: "Where to look" falls back to the longest-blocked thread).
- `gc.generations`: the largest committed size of Young (Eden + Survivor), Old and Metaspace; `metaspaceMaxMB` only when MaxMetaspaceSize is set.
- `gc` is optional. `events` are built from JFR `jdk.GarbageCollection` + `jdk.GCHeapSummary`
  (+ `jdk.G1HeapSummary`, `jdk.MetaspaceSummary`). `name` is `Young` / `Mixed` / `Full`.
  KPIs, distribution, causes, slope and problems are computed by `core` (and the prototype).
- The sample's telemetry, class history and GC events are fixed in `sample-snapshot.json`: what the prototype simulates.
