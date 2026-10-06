package jvmeter.core;

import io.avaje.json.JsonIoException;
import io.avaje.jsonb.Json;
import io.avaje.jsonb.Jsonb;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * A snapshot in the JSON format of docs/views.md. Fields mirror the JSON, so generated adapters
 * (avaje-jsonb) read and write it directly, without reflection. Only raw data is stored; totals,
 * hot spots and the GC analysis are computed by {@link Session}.
 */
@Json
public final class Snapshot {

  /** {@link SnapshotWriter#FORMAT} in written files */
  public String format;

  /** what the fields mean, for a reader; written, not read */
  @Json.Ignore(serialize = true)
  public List<String> about;

  /** derived from the raw data that follows (SnapshotWriter); written, not read */
  @Json.Ignore(serialize = true)
  public Map<String, Object> summary;

  public Target target;
  public Boolean sample;

  /** ISO 8601, e.g. 2026-10-04T14:30:00+09:00 */
  public String startedAt;

  public double durationSec;
  public Cpu cpu;
  public Memory memory;
  public List<RawThread> threads = new ArrayList<>();
  public Gc gc;

  /** CPU %, heap MB and threads over time */
  public Telemetry telemetry;

  /**
   * where threads waited for a lock or for I/O, from the thread samples; absent in older snapshots
   */
  public List<Wait> waits;

  @Json
  public static final class Target {
    public String name;
    public long pid;
    public String jvm;

    /**
     * "startup" (-javaagent, also when absent) or "attach" (loaded into a running JVM: inlined time
     * goes to callers)
     */
    public String agent;

    /** where the JVM runs; absent for this computer (docs/targets.md) */
    public Via via;

    /**
     * false: -XX:+DebugNonSafepoints was off, so time in inlined methods is shown in their callers
     * (absent: unknown)
     */
    public Boolean debugNonSafepoints;

    /** the packages whose calls are counted ("com.example,app"): the application's own code */
    public String include;
  }

  /**
   * where a JVM runs: kind "local", "ssh" (host, runAs) or "kubectl" (context, namespace, pod,
   * container)
   */
  @Json
  public static final class Via {
    public String kind, host, runAs, context, namespace, pod, container;

    public Via(String kind) {
      this.kind = kind;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Via v
          && java.util.Objects.equals(kind, v.kind)
          && java.util.Objects.equals(host, v.host)
          && java.util.Objects.equals(runAs, v.runAs)
          && java.util.Objects.equals(context, v.context)
          && java.util.Objects.equals(namespace, v.namespace)
          && java.util.Objects.equals(pod, v.pod)
          && java.util.Objects.equals(container, v.container);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(kind, host, runAs, context, namespace, pod, container);
    }

    public Via copy() {
      Via v = new Via(kind);
      v.host = host;
      v.runAs = runAs;
      v.context = context;
      v.namespace = namespace;
      v.pod = pod;
      v.container = container;
      return v;
    }
  }

  @Json
  public static final class Cpu {
    public Node tree;

    /** exact call counts per method (from the agent); tree nodes then carry no counts */
    public List<MethodCalls> methods;
  }

  @Json
  public static final class Node {
    public String name;

    /** ms */
    public double self;

    public long calls;
    public List<Node> children = new ArrayList<>();
  }

  @Json
  public static final class MethodCalls {
    public String name;
    public long calls;
  }

  @Json
  public static final class Memory {
    public double heapMaxMB;
    public Double xmsMB;
    public List<ClassStat> classes = new ArrayList<>();

    /** class histograms over time, for Before / After in the past */
    public History history;

    /** what was allocated where, from JFR allocation samples; absent in older snapshots */
    public List<Allocation> allocations;
  }

  /**
   * MB of cls allocated at site (the first frame in the application's code, else the top frame),
   * estimated from allocation samples
   */
  @Json
  public static final class Allocation {
    public String site, cls;
    public double mb;
  }

  /**
   * classes[i] had count[k][i] instances of bytes[k][i] bytes at t[k] (0: not among the largest
   * classes then)
   */
  @Json
  public static final class History {
    public List<Double> t = new ArrayList<>();
    public List<String> classes = new ArrayList<>();
    public List<List<Long>> count = new ArrayList<>();
    public List<List<Long>> bytes = new ArrayList<>();
  }

  @Json
  public static final class RawThread {
    public String name;

    /** [startSec, endSec, "run" | "wait" | "block" | "io"] */
    public List<List<Object>> segs;

    public List<String> stack = new ArrayList<>();

    /** seconds per state, sampled every 100 ms (written by the agent); absent: summed from segs */
    public java.util.Map<String, Double> sec;
  }

  /**
   * Thread samples (every 100 ms) in one state at one place: state "block" (a monitor, or a lock
   * that a thread owns) or "io"; site is the first frame in the application's code (else the top
   * frame), lock and owner what the thread waited for
   */
  @Json
  public static final class Wait {
    public String state, site, lock, owner, top;
    public double sec;

    /** the threads that waited here, most first */
    public List<String> threads = new ArrayList<>();
  }

  @Json
  public static final class Gc {
    public String collector;
    public Double heapMaxMB;
    public Generations generations;

    /** absent in the sample, which generates its events */
    public List<GcEvent> events;
  }

  @Json
  public static final class Generations {
    public double youngMB;
    public double oldMB;
    public double metaspaceMB;

    /**
     * MaxMetaspaceSize; absent when Metaspace has no limit (the default), so it cannot be "nearly
     * full"
     */
    public Double metaspaceMaxMB;
  }

  public static Snapshot loadSample() {
    try (InputStream in =
        Objects.requireNonNull(Snapshot.class.getResourceAsStream("sample-snapshot.json"))) {
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Reads a snapshot file: gzip-compressed JSON (.json.gz, as written by jvmeter) or plain JSON.
   */
  public static Snapshot read(Path file) throws IOException {
    // streamed into the parser: no copy of the whole JSON as bytes and again as a String
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
      in.mark(2);
      boolean gz = in.read() == 0x1f && in.read() == 0x8b;
      in.reset();
      InputStream json = gz ? new GZIPInputStream(in, 1 << 16) : in;
      try {
        return checked(() -> JSONB.type(Snapshot.class).fromJson(json));
      } catch (IllegalArgumentException e) {
        if (e.getCause() instanceof JsonIoException io && io.getCause() instanceof IOException c) {
          throw c; // a file that cannot be read (or a broken .gz) stays an IOException
        }
        throw e;
      }
    }
  }

  private static final Jsonb JSONB = Jsonb.builder().build();

  /**
   * @throws IllegalArgumentException when the JSON is invalid or required fields are missing
   */
  public static Snapshot parse(String json) {
    return checked(() -> JSONB.type(Snapshot.class).fromJson(json));
  }

  private static Snapshot checked(Supplier<Snapshot> from) {
    Snapshot s;
    try {
      s = from.get();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    if (s == null
        || s.target == null
        || s.cpu == null
        || s.cpu.tree == null
        || s.memory == null
        || s.memory.classes == null) {
      throw new IllegalArgumentException("target / cpu.tree / memory.classes are required");
    }
    if (s.threads == null) {
      s.threads = new ArrayList<>();
    }
    return s;
  }
}
