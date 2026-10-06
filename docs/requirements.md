# Features: what the GUI shows and the data behind it

Every feature of the GUI (and `demo/prototype.html`), the data it needs from the agent and `core`, and its status.
Done means it works with a real JVM end to end (agent → snapshot → core → GUI): a JVM here, over SSH or in a Kubernetes pod
is attached and recorded live (the agent's protocol, `Session.apply`), and a JVM started with `-javaagent` writes a snapshot at exit
with every field of the format. The sample is a fixed snapshot (the prototype simulates the same data).

## 1. Connecting (Start Center, top bar)

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| C1 | Start Center at startup, resumes where it left off (kind, fields, main class) | GUI prefs | Done | `StartCenter.Last` |
| C2 | Command preview for each kind (list, copy jar, attach, port forward) | `Targets.commands` | Done | `TargetsTest` checks it against the prototype |
| C3 | Per-JVM attach notes (JEP 451, `DebugNonSafepoints`) and "Prepare a JVM" | JDK version and options per JVM | Done | `list` reports them; `JDK_JAVA_OPTIONS` in a Deployment (in `JAVA_TOOL_OPTIONS`, JDK 21+ still warns) |
| C4 | Local: list JVMs (pid, main class, JDK, options, running agent's port) | agent `list` (jvmstat, like `jps -v`) | Done | `Launcher.list`, `Connector.list` |
| C5 | Attach and connect | agent `attach PID` with the target's own java; prints `{port, token}` | Done | Local; the target's java from `ProcessHandle.info()` |
| C6 | Connect to an agent that is already running | agent server on loopback with a token; token file for `list` | Done | `Server`, `AgentFile`, `AgentClient` |
| C7 | Agent jar copy, only when changed, into a directory only the JVM's user can write | `sh -c 'umask 077 … cat > …'` over ssh / kubectl | Done | Skipped when `sha256sum` finds the same jar (`Connector.copyJar`) |
| C8 | SSH: run commands, `ssh -L` forward, Run as (`sudo -n -u`) | the user's ssh | Done | Forwards to 127.0.0.1 and ::1; `LiveTest.overSsh` with `JVMETER_SSH` |
| C9 | SSH host suggestions (history + `~/.ssh/config`) | | Done | `Targets.sshHosts`; the GUI no longer adds the sample hosts |
| C10 | Kubernetes: contexts / namespaces / pods / containers | `kubectl config get-contexts`, `kubectl get … -o name` | Done | `Connector.contexts` … `containers`; running pods only |
| C11 | Kubernetes: `exec`, `port-forward` | | Done | `LiveTest.inKubernetes` (kind) |
| C12 | Stale pod falls back to a pod of the same Deployment | | Done | `Targets.samePod` |
| C13 | Top bar: target name, pid · JVM, where it runs (`via`) | `target.*` | Done | `via` written by the GUI |
| C14 | "Inlined → callers" badge | `target.debugNonSafepoints` | Done | Written by the agent |
| C15 | "Sample data" label | `sample` | Done | The Start Center's "Sample JVMs" badge remains in the prototype only |
| C16 | Disconnect: stop recording and remove the counters; the agent stays loaded | protocol | Done | When the connection closes (another JVM, a snapshot, the window closed) |
| C17 | Failures shown in the Start Center (no jdk.attach, no `sh`, read-only `/tmp`, auth) | | Done | Errors of list and attach (`{"error"}`, or what ssh / sudo / kubectl printed: host key, auth, unknown user, RBAC) shown above the list |

## 2. Recording and snapshots

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| R1 | Start / Stop recording | protocol: start / stop | Done | Connecting starts one; Start again begins a new recording |
| R2 | Per-second tick (CPU, heap, GC, threads) while recording | protocol: one JSON line per second | Done | `Session.apply` |
| R3 | Snapshot sent to the GUI and saved where the GUI runs | protocol | Done | The GUI builds the session from the messages; saving it is R4 |
| R4 | Save the current session as a snapshot from the GUI | `SnapshotWriter.write` | Done | Save in the top bar (prototype: downloads the raw JSON) |
| R5 | Open a snapshot (`.json.gz` / `.json`), errors shown | `Snapshot.read` | Done | |
| R6 | Snapshot starts with `about` and `summary` | `SnapshotWriter` | Done | |
| R7 | Sample data | `Snapshot.sample` | Done | |
| R8 | `startedAt` for times of day on the charts | | Done | |
| R9 | Tiered retention for long recordings (older data coarser) | core `Retention` | Done | 1 s / 10 s / 1 min / 10 min for telemetry, class history and thread segments; GC events kept; prototype + `RetentionTest` |
| R10 | Instrumentation scope chosen for a real app | Start Center, agent option | Done | Count calls in (remembered per main class); `include=` / `jvmeter.include` for `-javaagent` |
| R11 | After an attach, instrument classes that are already loaded | `retransformClasses` | Done | `CountTransformer.include` retransforms the loaded classes of the old and new packages (methods already running keep their old code) |

## 3. Overview

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| O1 | CPU (process) tile: current % and sparkline | `telemetry.cpu` | Done | `OperatingSystemMXBean` every second |
| O2 | Heap tile: current MB and sparkline | per-second heap used | Done | Heap used every second, aligned with GC events (`Session.heapAt`) |
| O3 | GC throughput tile | `gc.events` | Done | |
| O4 | Threads tile: count, blocked now, sparkline | `telemetry.threads`, thread states | Done | |
| O5 | Where to look: the application method using the most CPU, with the library code it calls | `cpu.tree`, `target.include` | Done | `Findings`: library time counts for the nearest application method above it, so `PriceCalculator.calculate` shows instead of `BigDecimal.multiply` |
| O6 | Where to look: the lock waited for longest, who held it and where | `waits` | Done | From the 100 ms thread samples (monitors, and `ReentrantLock` and the like that a thread owns); older snapshots: the longest-blocked thread |
| O7 | Where to look: a class that grows while the heap after GC grows (or since Before) | `memory.history`, `gc.events` | Done | Class histograms count unreachable objects too, so growth alone is no leak; the application's classes first |
| O8 | Where to look: top GC problem | `GcAnalysis` | Done | |
| O9 | Where to look: the application method that waited longest for I/O | `waits` | Done | A server waiting in `accept` or a selector waits for work and is left out |
| O10 | Where to look: CPUs saturated, thread count growing | `telemetry` | Done | ≥ 90 % over the last minute; +20 threads and +50 % |
| O11 | Where to look: most severe first, the same lines in `summary.findings` | | Done | bad, warn, info (CPU), then what looks fine |
| O12 | Where to look: who allocates most, when a GC problem comes from allocation | `memory.allocations` | Done | GC problems marked `alloc` (throughput, Full GCs, heap full, long pauses not from `System.gc()`, premature promotion) |

## 4. CPU

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| P1 | Hot spots: self, share, calls, average; sort; search | `cpu.tree`, `cpu.methods` | Done | `CallTree` |
| P2 | Call tree: total / self / calls; expand, collapse, hot path; sort | `cpu.tree` | Done | Calls per tree path are not counted (per method only, see [`design.md`](design.md)) |
| P3 | Method details: self, total, calls, per call, time breakdown, callers, Show in call tree | `CallTree.methodInfo` | Done | |
| P4 | Exact time in inlined methods | `-XX:+DebugNonSafepoints` at JVM startup | Done | Flagged by C14 when off |
| P5 | Profiling overhead of the counters kept out of the results | | Done | Agent frames removed from the tree; printed as overhead |
| P6 | CPU data while recording (not only at exit) | protocol: `cpu` every 5 s | Done | Expanded nodes stay expanded (`CallTree.paths` / `ids`) |

## 5. Memory › GC analysis

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| G1 | KPIs: throughput, avg / p95 / max pause, allocation and promotion rates | `gc.events` | Done | `GcAnalysis` |
| G2 | Rule-based problems (heap after GC growing, throughput, Full GC burst, System.gc(), long pause, humongous) | `gc.events` | Done | Rules in [`gc-analysis.md`](gc-analysis.md) |
| G3 | Rule: Metaspace nearly full | `gc.generations.metaspaceMaxMB` | Done | Against MaxMetaspaceSize; without a limit (the default) it cannot fill up |
| G4 | Heap before / after GC chart with trend | `gc.events` | Done | |
| G5 | Pause distribution, GC causes | `gc.events` | Done | |
| G6 | Generation sizes (peak / committed) | `gc.generations` (young, old, metaspace committed) | Done | The largest committed size per memory pool, every second |
| G7 | Copy GC log (unified logging) | `GcAnalysis.gcLog` | Done | |
| G8 | Live GC events while recording | protocol: GC events in the tick | Done | |
| G9 | Rules: heap stays full after GC, unrequested Full GC (G1 / ZGC / Shenandoah), premature promotion, Metaspace growth | `gc.events` | Done | Rules in [`gc-analysis.md`](gc-analysis.md) |
| G10 | Who allocates: MB per application method and class, share | JFR `jdk.ObjectAllocationSample` (150 samples/s) | Done | On the GC analysis tab; each sample weighs what its thread allocated since the last one; the agent's own allocations are left out |

## 6. Memory › Heap & classes

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| H1 | Heap usage over time, Full GC markers, -Xmx / -Xms lines | `gc.events`, `memory.heapMaxMB` / `xmsMB` | Done | The whole recording |
| H2 | Classes by instance count and size | `memory.classes` (class histogram) | Done | `GC.class_histogram -all` (DiagnosticCommand MBean), the 300 largest classes |
| H3 | Class history for Before / After picked on the chart | class histogram every N s | Done | Every 30 s (`jvmeter.histogram`), and after Run GC and Stop |
| H4 | Mark Before / After, diffs in instances and size | H3 | Done | |
| H6 | Run GC | protocol: `System.gc()` in the target, then a fresh histogram | Done | With a snapshot or the sample, it opens the Start Center |

## 7. Threads

| # | Feature | Needs | Status | Notes |
|---|---|---|---|---|
| T1 | Timeline of states per thread (run, wait, block, io) | `threads[].segs` | Done | `ThreadMXBean` every 100 ms; each second takes the state seen most |
| T2 | Time by state per thread | `threads[].sec` | Done | Summed per 100 ms sample, so blocks shorter than a second count |
| T3 | Current stack of the selected thread | `threads[].stack` | Done | The latest sample, 24 frames |
| T4 | Network I/O told apart from waiting | top frame | Done | RUNNABLE in a native `sun.nio.ch` / `java.net` / file method counts as io; `accept` and selectors count as wait. Parked on a lock another thread owns counts as block |
| T5 | Where threads waited: place, lock or I/O, holder, threads, total; a row selects the thread | `waits` | Done | Below the timeline; the lines of "Where to look" lead here |

## 8. General

| # | Feature | Status | Notes |
|---|---|---|---|
| X1 | Light / dark mode, remembered | Done | |
| X2 | Layout at 1280 px, content stops at 1600 px | Done | `gui/compare/compare.sh` |
| X3 | Empty state ("No JVM connected") with the views disabled | Done | |
| X4 | JDK 17 minimum for the agent | Done | The stream ends at its own `jvmeter.ChunkEnd` event, the same way on 17 and later, so no sample is lost at the end |

## Overhead

`demo/overhead.sh` is the gate for every change to the agent (fib(35) at most 2.0×, `app.Load` 1.3×), next to
`gui/compare/compare.sh` for the GUI. The measurements, and how jvmeter compares with JProfiler, are in [`benchmarks.md`](benchmarks.md).

## Limits

- At JVM exit, JFR's own shutdown hook and the agent's run at the same time. When JFR's comes first (for example with
  `-XX:StartFlightRecording`), it ends the agent's stream, and samples not processed yet (at most the last second) are lost; the JVM still exits.
- Each class histogram pauses the JVM, about 50 ms per GB of heap (every 30 s while recording; `-Djvmeter.histogram`).
- Calls are counted per method, not per call-tree path; methods outside the counted packages show "—".
- Attaching appends to the boot class path, so the JVM logs once that class data sharing now covers boot classes only.
- Run as needs sudo without a password for that user (`sudo -n`); an unknown SSH host key must be accepted once with `ssh HOST`.
- The rest is in [`targets.md`](targets.md) (Limits): distroless images, jlink runtimes without `jdk.attach`, Windows over SSH,
  one JVM per window.
