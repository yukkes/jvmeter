package jvmeter.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import jvmeter.core.Fmt;
import jvmeter.core.GcEvent;
import jvmeter.core.Snapshot;

/**
 * Subscribes to JFR in the same JVM.
 *
 * <p>CPU: each jdk.ExecutionSample adds one sample to self : the method at the top of the stack
 * total : every method on the stack (once per sample, even if it recurses) and to the call tree
 * along its stack. Time ≈ samples × interval. -XX:+DebugNonSafepoints is required to attribute
 * inlined code correctly.
 *
 * <p>GC: jdk.GarbageCollection with the heap before / after it (jdk.GCHeapSummary,
 * jdk.G1HeapSummary, jdk.MetaspaceSummary).
 */
public final class JfrRecorder {

  private static final double MB = 1024 * 1024;

  private final Duration period;

  /** the application's code (Findings.own) */
  private final java.util.function.Predicate<String> app;

  /**
   * site + '\0' + class -> bytes, estimated from allocation samples (each weighs what its thread
   * allocated since the last one)
   */
  private final Map<String, double[]> allocations = new HashMap<>();

  /** RecordedStackTrace -> the first frame in the application's code (else the top frame) */
  private final Map<RecordedStackTrace, String> sites = new IdentityHashMap<>();

  private final Instant start = Instant.now();
  private final Map<String, long[]> samples = new HashMap<>(); // method -> [self, total]
  private final TreeNode root = new TreeNode("All threads");
  private final Map<Long, GcEvent> gcs = new LinkedHashMap<>(); // gcId -> event
  private final Map<Long, String> g1Types = new HashMap<>();
  private final Map<Long, Long> youngUsed =
      new HashMap<>(); // gcId -> eden + survivor bytes after GC
  private RecordingStream stream;
  private Thread thread;

  /**
   * Recorded at the end of every chunk of the recording, so also when it stops, with a number that
   * counts up: once the stream has processed the last one, it has processed every event of the
   * recording. Off unless a recording asks for it.
   */
  @Name("jvmeter.ChunkEnd")
  @Label("jvmeter chunk end")
  @Enabled(false)
  @StackTrace(false)
  @Period("endChunk")
  static final class ChunkEnd extends Event {
    @SuppressWarnings("unused") // read back as the event's "seq" field
    long seq;
  }

  /** chunk ends recorded, and processed by the stream; guarded by lock */
  private long emitted, processed;

  private boolean ended;
  private final Object lock = new Object();
  private final Runnable chunkEnd =
      () -> {
        ChunkEnd e = new ChunkEnd();
        synchronized (lock) {
          e.seq = ++emitted;
        }
        e.commit();
      };

  /** the recording behind the stream (RecordingStream does not expose it) */
  private volatile Recording own;

  /** call tree node counted in samples; converted to ms when written */
  private static final class TreeNode {
    final String name;
    long self;
    final Map<String, TreeNode> children = new LinkedHashMap<>();

    TreeNode(String name) {
      this.name = name;
    }
  }

  /** include: the counted packages, the application's code where allocations are placed */
  public JfrRecorder(Duration period, String include) {
    this.period = period;
    this.app = jvmeter.core.Findings.own(include);
  }

  public void start() {
    Set<Recording> before = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    before.addAll(FlightRecorder.getFlightRecorder().getRecordings());
    stream = new RecordingStream();
    own =
        FlightRecorder.getFlightRecorder().getRecordings().stream()
            .filter(r -> !before.contains(r))
            .findFirst()
            .orElseThrow();
    stream.enable("jdk.ExecutionSample").withPeriod(period).withStackTrace();
    for (String e :
        List.of(
            "jdk.GarbageCollection",
            "jdk.G1GarbageCollection",
            "jdk.GCHeapSummary",
            "jdk.G1HeapSummary",
            "jdk.MetaspaceSummary")) {
      stream.enable(e);
    }
    stream.onEvent("jdk.ExecutionSample", this::sample);
    // JDK 16+: throttled to 150 samples a second, the rate JFR's default settings use
    stream.enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace();
    stream.onEvent("jdk.ObjectAllocationSample", this::allocation);
    stream.onEvent("jdk.GarbageCollection", this::gc);
    stream.onEvent(
        "jdk.G1GarbageCollection", e -> put(g1Types, e.getLong("gcId"), e.getString("type")));
    stream.onEvent(
        "jdk.GCHeapSummary",
        e -> {
          GcEvent g = gc(e.getLong("gcId"));
          double used = e.getLong("heapUsed") / MB;
          if (before(e)) {
            g.beforeMB = used;
          } else {
            g.afterMB = used;
          }
        });
    stream.onEvent(
        "jdk.G1HeapSummary",
        e -> {
          if (!before(e)) {
            put(
                youngUsed,
                e.getLong("gcId"),
                e.getLong("edenUsedSize") + e.getLong("survivorUsedSize"));
          }
        });
    stream.onEvent(
        "jdk.MetaspaceSummary",
        e -> {
          if (!before(e)) {
            gc(e.getLong("gcId")).metaspaceMB = Fmt.round(e.getLong("metaspace.used") / MB, 1);
          }
        });
    FlightRecorder.addPeriodicEvent(ChunkEnd.class, chunkEnd);
    stream.enable(ChunkEnd.class);
    stream.onEvent(
        "jvmeter.ChunkEnd",
        e -> {
          synchronized (lock) {
            processed = e.getLong("seq");
            lock.notifyAll();
          }
        });
    // startAsync() uses a non-daemon thread, which would keep the JVM alive after main returns.
    // Run start() in our own daemon thread instead
    thread =
        new Thread(
            () -> {
              try {
                stream.start();
              } finally {
                synchronized (lock) {
                  ended = true;
                  lock.notifyAll();
                }
              }
            },
            "jvmeter-jfr");
    thread.setDaemon(true);
    thread.start();
  }

  private static boolean before(RecordedEvent e) {
    return "Before GC".equals(e.getString("when"));
  }

  private synchronized <K, V> void put(Map<K, V> m, K k, V v) {
    m.put(k, v);
  }

  /** when Run GC called System.gc(): [from, to], to null while it runs */
  private final List<Instant[]> runGc = new ArrayList<>();

  /** Run GC: the GCs that System.gc() starts until {@link #runGcDone} are marked manual */
  synchronized Instant[] runGc() {
    Instant[] w = {Instant.now(), null};
    runGc.add(w);
    return w;
  }

  synchronized void runGcDone(Instant[] w) {
    w[1] = Instant.now();
  }

  /**
   * started by Run GC, not by the application's own System.gc(); 50 ms either side, as JFR's clock
   * and Instant.now() may differ by a few ms
   */
  synchronized boolean byRunGc(Instant t) {
    Duration slack = Duration.ofMillis(50);
    for (Instant[] w : runGc) {
      if (!t.isBefore(w[0].minus(slack)) && (w[1] == null || !t.isAfter(w[1].plus(slack)))) {
        return true;
      }
    }
    return false;
  }

  private synchronized GcEvent gc(long id) {
    return gcs.computeIfAbsent(id, k -> new GcEvent());
  }

  private void gc(RecordedEvent e) {
    GcEvent g = gc(e.getLong("gcId"));
    String name = e.getString("name");
    g.t = Duration.between(start, e.getStartTime()).toNanos() / 1e9;
    g.name =
        name.contains("Old") || name.contains("Full") || name.contains("MarkSweep")
            ? "Full"
            : "Young";
    g.cause = e.getString("cause");
    g.pauseMs = e.getDuration("sumOfPauses").toNanos() / 1e6;
    if ("System.gc()".equals(g.cause) && byRunGc(e.getStartTime())) {
      g.manual = Boolean.TRUE;
    }
  }

  /**
   * What a sample of one stack adds to: its top method's counters, each distinct method's (once,
   * even if it recurses) and its call tree leaf. JFR shares one RecordedStackTrace per stack and
   * one RecordedMethod per method within a chunk, so a stack seen before allocates nothing.
   */
  private record Stack(long[] top, long[][] total, TreeNode leaf) {}

  private final Map<RecordedStackTrace, Stack> stacks = new IdentityHashMap<>();

  /** RecordedMethod -> {name, key} */
  private final Map<RecordedMethod, String[]> methods = new IdentityHashMap<>();

  private synchronized void sample(RecordedEvent e) {
    RecordedStackTrace st = e.getStackTrace();
    if (st == null) {
      return;
    }
    Stack s = stacks.get(st);
    if (s == null) {
      s = stack(st.getFrames());
      if (s == null) {
        return;
      }
      if (stacks.size() >= 20_000) { // new chunks bring new objects: keep the caches bounded
        stacks.clear();
        methods.clear();
      }
      stacks.put(st, s);
    }
    s.top[0]++;
    for (long[] t : s.total) {
      t[1]++;
    }
    s.leaf.self++;
  }

  private synchronized void allocation(RecordedEvent e) {
    RecordedStackTrace st = e.getStackTrace();
    if (st == null || e.getClass("objectClass") == null) {
      return;
    }
    String site = sites.get(st);
    if (site == null) {
      List<RecordedFrame> frames = st.getFrames();
      if (frames.isEmpty()) {
        return;
      }
      String top = null, mine = null;
      boolean agent = false;
      for (RecordedFrame f : frames) {
        String n = methods.computeIfAbsent(f.getMethod(), m -> new String[] {name(m), key(m)})[0];
        top = top == null ? n : top;
        mine = mine == null && app.test(n) ? n : mine;
        agent |= n.startsWith("jvmeter.agent.");
      }
      // the agent's own allocations (thread samples, the counters) are profiling overhead, as in
      // the call tree: ""
      site = agent ? "" : mine != null ? mine : top;
      if (sites.size() >= 20_000) {
        sites.clear();
      }
      sites.put(st, site);
    }
    if (site.isEmpty()) {
      return;
    }
    allocations
            .computeIfAbsent(
                site + '\0' + Histogram.name(e.getClass("objectClass").getName()),
                k -> new double[1])[0] +=
        e.getLong("weight");
  }

  /** the 100 largest allocation sites and classes */
  public synchronized List<Snapshot.Allocation> allocations() {
    List<Snapshot.Allocation> out = new ArrayList<>();
    allocations.forEach(
        (k, v) -> {
          Snapshot.Allocation a = new Snapshot.Allocation();
          a.site = k.substring(0, k.indexOf('\0'));
          a.cls = k.substring(k.indexOf('\0') + 1);
          a.mb = Fmt.round(v[0] / MB, 1);
          out.add(a);
        });
    out.sort((a, b) -> Double.compare(b.mb, a.mb));
    return out.subList(0, Math.min(100, out.size()));
  }

  private Stack stack(List<RecordedFrame> frames) {
    if (frames.isEmpty()) {
      return null;
    }
    Set<long[]> total = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    String[][] nk = new String[frames.size()][];
    for (int i = 0; i < nk.length; i++) {
      nk[i] =
          methods.computeIfAbsent(frames.get(i).getMethod(), m -> new String[] {name(m), key(m)});
      total.add(samples.computeIfAbsent(nk[i][1], k -> new long[2]));
    }
    // call tree: from the bottom of the stack (thread root) to the top
    TreeNode n = root;
    for (int i = nk.length - 1; i >= 0; i--) {
      n = n.children.computeIfAbsent(nk[i][0], TreeNode::new);
    }
    return new Stack(samples.get(nk[0][1]), total.toArray(new long[0][]), n);
  }

  /**
   * Stops the recording, then closes the stream once it has processed the last ChunkEnd, which
   * comes after every event of the recording. Whoever stops the recording (this, or JFR's shutdown
   * hook at JVM exit), that ChunkEnd is recorded; when JFR's shutdown hook ends the stream itself,
   * the wait ends with it. (RecordingStream.stop() waits for data up to the stop time, which ends a
   * little after the last chunk: at JVM exit no chunk follows and it waits forever. It also exists
   * from JDK 20 only.)
   */
  public void stop() {
    try {
      own.stop();
    } catch (IllegalStateException stoppedByJfr) {
      // JFR's shutdown hook stopped it first
    }
    try {
      synchronized (lock) {
        while (processed < emitted && !ended) {
          lock.wait();
        }
      }
      stream.close();
      thread.join();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      FlightRecorder.removePeriodicEvent(chunkEnd);
    }
  }

  public Instant startTime() {
    return start;
  }

  /**
   * the call tree in ms; the agent's own frames (the +1 per call) are left out, they are profiling
   * overhead
   */
  public synchronized Snapshot.Node tree() {
    return toNode(root, period.toNanos() / 1e6);
  }

  private static Snapshot.Node toNode(TreeNode t, double msPerSample) {
    Snapshot.Node n = new Snapshot.Node();
    n.name = t.name;
    n.self = t.self * msPerSample;
    for (TreeNode c : t.children.values()) {
      if (!c.name.startsWith("jvmeter.agent.")) {
        n.children.add(toNode(c, msPerSample));
      }
    }
    return n;
  }

  /** complete GC events in time order */
  public synchronized List<GcEvent> gcEvents() {
    List<GcEvent> out = new ArrayList<>();
    for (Map.Entry<Long, GcEvent> e : gcs.entrySet()) {
      GcEvent g = e.getValue();
      if (g.name == null) {
        continue; // heap summary without its GC event (cut off at the end)
      }
      if ("Mixed".equals(g1Types.get(e.getKey()))) {
        g.name = "Mixed";
      }
      Long young = youngUsed.get(e.getKey());
      if (young != null) {
        g.oldAfterMB = Fmt.round(g.afterMB - young / MB, 1);
      }
      g.beforeMB = Fmt.round(g.beforeMB, 1);
      g.afterMB = Fmt.round(g.afterMB, 1);
      g.t = Fmt.round(g.t, 3);
      g.pauseMs = Fmt.round(g.pauseMs, 2);
      out.add(g);
    }
    out.sort((a, b) -> Double.compare(a.t, b.t));
    return out;
  }

  /** Same format as MethodRegistry: "app.Fib.fibRecursive(I)J" */
  static String key(RecordedMethod m) {
    return name(m) + m.getDescriptor();
  }

  /** "app.Fib.fibRecursive" (overloads share a name in the call tree) */
  static String name(RecordedMethod m) {
    return m.getType().getName() + "." + m.getName();
  }
}
