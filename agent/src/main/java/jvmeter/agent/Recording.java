package jvmeter.agent;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jvmeter.core.ClassStat;
import jvmeter.core.GcEvent;
import jvmeter.core.Live;
import jvmeter.core.Retention;
import jvmeter.core.Session;
import jvmeter.core.Snapshot;
import jvmeter.core.Telemetry;

/**
 * One recording: JFR samples and GC events, thread states every 100 ms, generation sizes every
 * second, and call counts since it started. {@link #snapshot} turns it into the snapshot JSON's raw
 * data.
 */
final class Recording {

  final JfrRecorder jfr;
  final ThreadSampler threads;
  private final long startNanos = System.nanoTime();

  /**
   * call counts when the recording started: counters only grow, so a recording reports its own
   * calls
   */
  private final Map<Integer, Long> base = counts();

  private final Snapshot.Generations gen = new Snapshot.Generations();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "jvmeter-sampler");
            t.setDaemon(true);
            return t;
          });
  private int sec;

  /** the latest class histogram, and the earlier ones (older ones coarser) */
  volatile List<ClassStat> classes = List.of();

  final List<Session.ClassSnap> history = new ArrayList<>();

  /**
   * seconds between class histograms (each pauses the JVM about 50 ms per GB of heap); 0: only when
   * asked
   */
  private final int histogramEvery;

  final Telemetry tel = new Telemetry();
  private final OperatingSystemMXBean os =
      ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

  /**
   * where messages for the GUI go; null for the recording -javaagent starts (its snapshot is
   * written at exit)
   */
  final Consumer<Live.Message> sink;

  private double sentGcT = -1;

  /** include: the counted packages, the application's code where waits are placed */
  Recording(Duration period, int histogramEvery, String include, Consumer<Live.Message> sink) {
    threads = new ThreadSampler(include);
    this.histogramEvery = histogramEvery;
    this.sink = sink;
    jfr = new JfrRecorder(period, include);
    jfr.start();
    timer.scheduleAtFixedRate(this::sample, 0, 1000 / ThreadSampler.PER_SEC, TimeUnit.MILLISECONDS);
  }

  double t() {
    return (System.nanoTime() - startNanos) / 1e9;
  }

  private void sample() {
    try {
      double t = t();
      threads.sample(t);
      if (t >= sec + 1) {
        sec++;
        List<Live.ThreadSec> states = threads.second(sec);
        generations();
        second(sec);
        send(tick(sec, states));
        if (sec % 5 == 0) {
          send(cpu());
        }
        if (histogramEvery > 0 && sec % histogramEvery == 0) {
          histogram();
        }
      }
    } catch (Throwable e) {
      // a failed sample must not stop the next ones
    }
  }

  private void send(Live.Message m) {
    if (sink != null) {
      sink.accept(m);
    }
  }

  private synchronized Live.Message tick(int t, List<Live.ThreadSec> states) {
    Live.Message m = new Live.Message("tick");
    m.t = (double) t;
    m.cpu = Telemetry.last(tel.cpu);
    m.heap = Telemetry.last(tel.heap);
    m.threads = Telemetry.last(tel.threads);
    m.gc = new ArrayList<>();
    for (GcEvent e : jfr.gcEvents()) {
      if (e.t > sentGcT) {
        m.gc.add(e);
      }
    }
    if (!m.gc.isEmpty()) {
      sentGcT = m.gc.get(m.gc.size() - 1).t;
    }
    m.states = states;
    return m;
  }

  /** the call tree, call counts, thread stacks and generation sizes so far */
  synchronized Live.Message cpu() {
    Live.Message m = new Live.Message("cpu");
    m.tree = jfr.tree();
    m.methods = methods();
    m.stacks = new java.util.LinkedHashMap<>();
    for (Snapshot.RawThread r : threads.threads()) {
      m.stacks.put(r.name, r.stack);
    }
    m.waits = threads.waits();
    m.allocations = jfr.allocations();
    m.generations = gen.youngMB > 0 ? copy(gen) : null;
    return m;
  }

  /** the last data, then "end" */
  void finish() {
    send(cpu());
    send(new Live.Message("end"));
  }

  /** the per-second values: process CPU, heap used, live threads */
  private synchronized void second(int t) {
    double cpu = os.getProcessCpuLoad();
    tel.add(
        t,
        cpu < 0 ? 0 : Math.round(cpu * 1000) / 10.0,
        Math.round(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 104857.6)
            / 10.0,
        threads.count());
    tel.compact(t);
  }

  /** the largest committed size seen per generation (they grow and shrink with the heap) */
  private synchronized void generations() {
    double young = 0, old = 0;
    for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
      double mb = p.getUsage().getCommitted() / 1048576.0;
      String n = p.getName();
      if (p.getType() == MemoryType.HEAP
          && (n.contains("Eden") || n.contains("Survivor") || n.contains("Young"))) {
        young += mb;
      } else if (p.getType() == MemoryType.HEAP && (n.contains("Old") || n.contains("Tenured"))) {
        old += mb;
      } else if (n.equals("Metaspace")) {
        gen.metaspaceMB = Math.max(gen.metaspaceMB, Math.round(mb * 10) / 10.0);
        long max = p.getUsage().getMax();
        gen.metaspaceMaxMB = max > 0 ? (double) Math.round(max / 1048576.0) : null;
      }
    }
    gen.youngMB = Math.max(gen.youngMB, Math.round(young));
    gen.oldMB = Math.max(gen.oldMB, Math.round(old));
  }

  void histogram() {
    try {
      List<ClassStat> h = Histogram.take();
      Map<String, Long> count = new java.util.HashMap<>(), bytes = new java.util.HashMap<>();
      h.forEach(
          c -> {
            count.put(c.name, c.count);
            bytes.put(c.name, c.bytes);
          });
      double t = Math.round(t() * 10) / 10.0;
      synchronized (this) {
        classes = h;
        history.add(new Session.ClassSnap(t, count, bytes));
        Retention.history(history, t);
      }
      Live.Message m = new Live.Message("classes");
      m.t = t;
      m.classes = h;
      send(m);
    } catch (Exception e) {
      System.err.println("[jvmeter] class histogram failed: " + e);
    }
  }

  void stop() {
    timer.shutdownNow();
    jfr.stop();
  }

  /** method id -> calls so far */
  static Map<Integer, Long> counts() {
    Map<Integer, Long> c = new TreeMap<>();
    for (int id = 1;
        id < CallCounter.MAX_METHODS && !MethodRegistry.name(id).startsWith("<unknown");
        id++) {
      c.put(id, CallCounter.count(id));
    }
    return c;
  }

  /** method name ("app.Fib.fib(I)J") -> calls since the recording started */
  Map<String, Long> calls() {
    Map<String, Long> out = new TreeMap<>();
    counts().forEach((id, n) -> out.put(MethodRegistry.name(id), n - base.getOrDefault(id, 0L)));
    return out;
  }

  /** the raw data of the snapshot (what the file holds after about and summary) */
  synchronized Snapshot snapshot(Snapshot.Target target) {
    Snapshot s = new Snapshot();
    s.target = target;
    s.startedAt = jfr.startTime().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
    s.durationSec = Math.max(1, Math.round(t()));
    s.cpu = new Snapshot.Cpu();
    s.cpu.tree = jfr.tree();
    s.cpu.methods = methods();
    s.memory = new Snapshot.Memory();
    s.memory.heapMaxMB = (double) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
    s.memory.xmsMB =
        (double)
            (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit() / (1024 * 1024));
    s.memory.classes = new ArrayList<>(classes);
    s.memory.history = history.isEmpty() ? null : Session.history(history);
    s.memory.allocations = jfr.allocations();
    s.telemetry = tel.copy();
    s.threads = threads.threads();
    s.waits = threads.waits();
    s.gc = new Snapshot.Gc();
    // "G1 Young Generation" -> G1, "PS Scavenge" -> Parallel, "Copy" -> Serial, "ZGC Cycles" -> ZGC
    s.gc.collector = collector();
    s.gc.heapMaxMB = s.memory.heapMaxMB;
    s.gc.generations = gen.youngMB > 0 ? copy(gen) : null;
    s.gc.events = jfr.gcEvents();
    return s;
  }

  /** exact counts per method; overloads share a name, as in the call tree */
  private List<Snapshot.MethodCalls> methods() {
    Map<String, Long> byName = new TreeMap<>();
    calls().forEach((k, v) -> byName.merge(k.substring(0, k.indexOf('(')), v, Long::sum));
    byName.values().removeIf(v -> v == 0);
    List<Snapshot.MethodCalls> l = new ArrayList<>();
    byName.forEach(
        (k, v) -> {
          Snapshot.MethodCalls m = new Snapshot.MethodCalls();
          m.name = k;
          m.calls = v;
          l.add(m);
        });
    return l;
  }

  /**
   * "G1 Young Generation" -> G1, "PS Scavenge" -> Parallel, "Copy" -> Serial, "ZGC Cycles" -> ZGC
   */
  static String collector() {
    String gc = ManagementFactory.getGarbageCollectorMXBeans().get(0).getName().split(" ")[0];
    return gc.equals("PS") ? "Parallel" : gc.equals("Copy") ? "Serial" : gc;
  }

  private static Snapshot.Generations copy(Snapshot.Generations g) {
    Snapshot.Generations c = new Snapshot.Generations();
    c.youngMB = g.youngMB;
    c.oldMB = g.oldMB;
    c.metaspaceMB = g.metaspaceMB;
    c.metaspaceMaxMB = g.metaspaceMaxMB;
    return c;
  }
}
