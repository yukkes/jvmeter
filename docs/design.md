# Design

**Calls are counted by instrumentation. Time comes from JFR sampling.**

- **Calls**: `CountTransformer` (ASM) inserts `CallCounter.hit(id)` at the entry of each target method
  (`-Djvmeter.include`, default: the main class's package). Each thread increments its own slot; no atomics, no clock reads.
  A method's first integer argument picks one of 8 counters in the slot, so a recursion or a loop does not wait
  for its previous increment to be stored (fib(35): 1.8× instead of 2.6×).
- **Time**: `JfrRecorder` subscribes to JFR `jdk.ExecutionSample` every 1 ms in the same JVM.
  Self = top of the stack, total = anywhere on the stack. Time ≈ samples × interval.
- **Snapshot**: with `-javaagent`, `Agent` writes the snapshot at exit (`jvmeter-PID.json.gz`); samples
  inside the counter are profiling overhead and left out of the call tree.

`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` is required. Without it, time in inlined methods
is attributed to their callers (a known 60/40 split showed up as 99/1). It must be set when the JVM starts, also for an agent
attached later; with `-XX:+EnableDynamicAgentLoading` too, a JVM can be attached to later silently and exactly ([`targets.md`](targets.md)).

## Why this design

| Approach | fib(35) against no profiling | Accuracy of time |
|---|---:|---|
| Read the clock on every call | ~39× | exact |
| Read the clock only some of the time | ~14× | poor for small methods |
| **Count calls + JFR sampling** | **1.8×** (18 ms → 32 ms) | **58–60 % for a method whose true share is 60 %** |

fib(35) is the worst case: about 30 million calls of a method that does almost nothing. Measurements, and JProfiler on the same
benchmarks, are in [`benchmarks.md`](benchmarks.md).

## Limits

- Time is statistical: record for a few seconds or more.
- Counts are per method, not per call-tree path.
