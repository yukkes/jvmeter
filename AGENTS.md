# AGENTS.md — jvmeter

Lightweight JVM profiler: exact call counts by bytecode instrumentation,
CPU time from JFR sampling. Snapshots are JSON for people, the GUI or an LLM.

## Layout

| Module | What |
|---|---|
| `core/` | Snapshot model (avaje-jsonb, compile-time adapters), analysis, snapshot writer |
| `agent/` | `-javaagent`: ASM for counts, JFR for time and GC; writes a snapshot at exit |
| `gui/` | Swing + FlatLaf; must match `demo/prototype.html` (`gui/compare/compare.sh` ≥ 99%) |
| `demo/` | Demo apps, `prototype.html`, `overhead.sh`, `profilers.sh`, `sampling.sh` |
| `docs/` | design, views, gc-analysis, java-gui, targets, requirements, benchmarks |

## Build and run

```bash
./mvnw package                    # JDK 17+
java -javaagent:agent/target/jvmeter-agent.jar -cp demo/target/jvmeter-demo.jar app.Mix
java -jar gui/target/jvmeter-gui.jar [snapshot.json.gz]
```

## Rules

- JDK 17 minimum. Small distribution: Inter (subset), FlatLaf and avaje-jsonb only.
- Analysis in `core`; the GUI only draws. Keep `prototype.html`, the Java port and
  `GoldenTest` in sync; for look changes run `gui/compare/compare.sh`. The sample's data is
  fixed in `sample-snapshot.json` (the prototype simulates it; regenerate it when that changes).
- Overhead gate: `demo/overhead.sh` must pass after an agent change
  (fib(35) ≤ 2.0×, `app.Load` ≤ 1.3×).
- Snapshot JSON starts with `about` and `summary`.
- Google Java Style: `./mvnw verify` fails on unformatted code; `./mvnw spotless:apply` fixes.
- Qodana must report nothing (`qodana.yaml`) when a release is tagged; run it as yourself.
- CI locally with act (`.github/act/`); GitHub runs only `release.yml` on a `v*` tag.
- Anything started to check a change must stop itself within 10 minutes.
- Commits and pull requests in English, with no `Co-Authored-By` or "Generated with" lines.

## Coding style

Write only what the task needs ([ponytail](https://github.com/DietrichGebert/ponytail)):
skip it, reuse, JDK, existing dependency, one line, or the minimum that works.
Never cut input validation at trust boundaries, data-loss handling, security, or
accessibility.
