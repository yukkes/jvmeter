package jvmeter.core;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded snapshot plus everything derived from it: the call tree, telemetry, class history,
 * threads and GC data; a live recording updates it ({@link #apply}).
 */
public final class Session {

  /**
   * Per-class counts and sizes at one time; the history lets reference points be picked in the
   * past.
   */
  public record ClassSnap(double t, Map<String, Long> count, Map<String, Long> bytes) {}

  public record AbRow(ClassStat c, double a, double b, double diff, double dbytes) {}

  public record Limit(double v, String label) {}

  public final Snapshot snap;

  /** replaced while a live recording runs */
  public CallTree tree;

  public final Telemetry tel;
  public final List<ClassStat> classes = new ArrayList<>();
  public final List<ClassSnap> chist = new ArrayList<>();
  public final List<ThreadTrack> threads = new ArrayList<>();

  /** null when the snapshot has no GC data */
  public final GcData gc;

  public final long startMs;
  public int elapsed;

  public Session(Snapshot s) {
    snap = s;
    elapsed = s.durationSec > 0 ? (int) Math.round(s.durationSec) : 120;
    startMs = startMs(s.startedAt, elapsed);
    tree = new CallTree(s.cpu);
    classes.addAll(s.memory.classes);
    tel = s.telemetry != null ? s.telemetry : new Telemetry();
    if (s.memory.history != null) {
      chist.addAll(history(s.memory.history));
    } else if (!classes.isEmpty()) {
      chist.add(takeSnap());
    }
    for (Snapshot.RawThread t : s.threads) {
      List<Seg> segs = new ArrayList<>();
      for (List<Object> g : t.segs != null ? t.segs : List.<List<Object>>of()) {
        segs.add(
            new Seg(
                ((Number) g.get(0)).doubleValue(),
                ((Number) g.get(1)).doubleValue(),
                String.valueOf(g.get(2))));
      }
      threads.add(new ThreadTrack(t.name, segs, new ArrayList<>(t.stack), t.sec));
    }
    gc = loadGc(s);
    syncHeapTelemetry(false);
  }

  static List<ClassSnap> history(Snapshot.History h) {
    List<ClassSnap> out = new ArrayList<>();
    for (int k = 0; k < h.t.size(); k++) {
      Map<String, Long> count = new HashMap<>(), bytes = new HashMap<>();
      for (int i = 0; i < h.classes.size(); i++) {
        count.put(h.classes.get(i), h.count.get(k).get(i));
        bytes.put(h.classes.get(i), h.bytes.get(k).get(i));
      }
      out.add(new ClassSnap(h.t.get(k), count, bytes));
    }
    return out;
  }

  public static Snapshot.History history(List<ClassSnap> l) {
    Snapshot.History h = new Snapshot.History();
    java.util.Set<String> names = new java.util.TreeSet<>();
    l.forEach(e -> names.addAll(e.count.keySet()));
    h.classes.addAll(names);
    for (ClassSnap e : l) {
      h.t.add(e.t);
      h.count.add(h.classes.stream().map(c -> e.count.getOrDefault(c, 0L)).toList());
      h.bytes.add(h.classes.stream().map(c -> e.bytes.getOrDefault(c, 0L)).toList());
    }
    return h;
  }

  /** startedAt, or (without it) the time of loading minus the duration */
  private static long startMs(String startedAt, int elapsed) {
    if (startedAt != null) {
      try {
        return OffsetDateTime.parse(startedAt).toInstant().toEpochMilli();
      } catch (DateTimeParseException ignored) {
        // treated as absent
      }
    }
    return System.currentTimeMillis() - elapsed * 1000L;
  }

  private GcData loadGc(Snapshot s) {
    if (s.gc == null) {
      return null;
    }
    double heapMax =
        s.gc.heapMaxMB != null && s.gc.heapMaxMB != 0 ? s.gc.heapMaxMB : s.memory.heapMaxMB;
    GcData g = new GcData(s.gc.collector != null ? s.gc.collector : "?", heapMax, s.gc.generations);
    if (s.gc.events != null) {
      g.events.addAll(s.gc.events); // the session adds events, never changes one
    }
    return g;
  }

  // ---------- heap ----------

  /**
   * Heap usage derived from GC events: drops right after a GC and grows with allocation until the
   * next one, so every drop in the chart is a GC. null without events.
   */
  public Double heapAt(double t) {
    if (gc == null || gc.events.isEmpty()) {
      return null;
    }
    List<GcEvent> ev = gc.events;
    GcEvent first = ev.get(0);
    if (t <= first.t) { // before the first GC: extrapolate back at the next interval's rate
      double r1 =
          ev.size() > 1
              ? (ev.get(1).beforeMB - first.afterMB) / Math.max(1e-3, ev.get(1).t - first.t)
              : 0;
      return Math.max(first.afterMB, first.beforeMB - r1 * (first.t - t));
    }
    int lo = 0, hi = ev.size() - 1; // last GC at or before t
    while (lo < hi) {
      int mid = (lo + hi + 1) >> 1;
      if (ev.get(mid).t <= t) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    GcEvent e = ev.get(lo);
    if (lo + 1 < ev.size()) {
      GcEvent nx = ev.get(lo + 1);
      return e.afterMB + (nx.beforeMB - e.afterMB) * (t - e.t) / Math.max(1e-6, nx.t - e.t);
    }
    // after the last GC: extend at the latest allocation rate
    double rate =
        lo > 0
            ? Math.max(
                0, (e.beforeMB - ev.get(lo - 1).afterMB) / Math.max(1e-3, e.t - ev.get(lo - 1).t))
            : 0;
    return Math.min(gc.heapMaxMB, e.afterMB + rate * (t - e.t));
  }

  /** align the per-second values (overview tiles, current value) with the GC events */
  void syncHeapTelemetry(boolean lastOnly) {
    if (gc == null || gc.events.isEmpty() || tel.isEmpty()) {
      return;
    }
    int n = tel.heap.size();
    for (int i = lastOnly ? n - 1 : 0; i < n; i++) {
      tel.heap.set(i, heapAt(tel.t.get(i)));
    }
  }

  /**
   * heap limit (-Xmx) and initial size (-Xms), drawn as horizontal lines when the snapshot has them
   */
  public List<Limit> heapLimits() {
    List<Limit> l = new ArrayList<>();
    double xmx = snap.memory.heapMaxMB;
    if (xmx != 0) {
      l.add(new Limit(xmx, "-Xmx " + Fmt.fmtInt(xmx) + " MB"));
    }
    Double xms = snap.memory.xmsMB;
    if (xms != null && xms != 0 && xms < xmx) {
      l.add(new Limit(xms, "-Xms " + Fmt.fmtInt(xms) + " MB"));
    }
    return l;
  }

  public GcAnalysis analyzeGc() {
    return gc == null ? null : GcAnalysis.of(gc, elapsed);
  }

  // ---------- class history and Before / After ----------

  public ClassSnap takeSnap() {
    Map<String, Long> count = new HashMap<>(), bytes = new HashMap<>();
    for (ClassStat c : classes) {
      count.put(c.name, c.count);
      bytes.put(c.name, c.bytes);
    }
    return new ClassSnap(elapsed, count, bytes);
  }

  /** state at time t (closest entry in the history) */
  public ClassSnap snapAt(double t) {
    if (chist.isEmpty()) {
      return takeSnap();
    }
    ClassSnap best = chist.get(0);
    for (ClassSnap h : chist) {
      if (Math.abs(h.t - t) < Math.abs(best.t - t)) {
        best = h;
      }
    }
    return new ClassSnap(best.t, new HashMap<>(best.count), new HashMap<>(best.bytes));
  }

  /** rows comparing Before (a) with After, or with now when after is null */
  public List<AbRow> abRows(ClassSnap before, ClassSnap after) {
    List<AbRow> rows = new ArrayList<>();
    for (ClassStat c : classes) {
      long b = after != null ? after.count.getOrDefault(c.name, 0L) : c.count;
      long bb = after != null ? after.bytes.getOrDefault(c.name, 0L) : c.bytes;
      long a = before.count.getOrDefault(c.name, 0L);
      rows.add(new AbRow(c, a, b, b - a, bb - before.bytes.getOrDefault(c.name, 0L)));
    }
    return rows;
  }

  // ---------- a live recording (the agent's messages, docs/targets.md) ----------

  /** an empty session for a recording that starts now, from the agent's hello */
  public static Session live(Live.Message hello) {
    Snapshot s = new Snapshot();
    s.target = hello.target;
    s.startedAt = OffsetDateTime.now().toString();
    s.cpu = new Snapshot.Cpu();
    s.cpu.tree = new Snapshot.Node();
    s.cpu.tree.name = "All threads";
    s.memory = new Snapshot.Memory();
    s.memory.heapMaxMB = hello.heapMaxMB != null ? hello.heapMaxMB : 0;
    s.memory.xmsMB = hello.xmsMB;
    s.gc = new Snapshot.Gc();
    s.gc.collector = hello.collector;
    s.gc.heapMaxMB = hello.heapMaxMB;
    s.gc.events = new ArrayList<>();
    s.telemetry = new Telemetry();
    Session x = new Session(s);
    x.elapsed = 0;
    return x;
  }

  /** applies a message of the recording: tick, cpu or classes (others change nothing) */
  public void apply(Live.Message m) {
    switch (m.type) {
      case "tick" -> {
        elapsed = (int) Math.round(m.t);
        if (m.gc != null) {
          gc.events.addAll(m.gc);
        }
        tel.add(m.t, m.cpu, m.heap, m.threads);
        tel.compact(m.t);
        syncHeapTelemetry(true);
        for (Live.ThreadSec ts : m.states == null ? List.<Live.ThreadSec>of() : m.states) {
          ThreadTrack th = thread(ts.name);
          if (ts.state != null) {
            Retention.append(th.segs, new Seg(m.t - 1, m.t, ts.state));
          }
          Retention.segs(th.segs, m.t);
          th.sec.putAll(Map.of("run", ts.run, "wait", ts.wait, "block", ts.block, "io", ts.io));
        }
      }
      case "cpu" -> {
        snap.cpu = new Snapshot.Cpu();
        snap.cpu.tree = m.tree;
        snap.cpu.methods = m.methods;
        tree = new CallTree(snap.cpu);
        if (m.stacks != null) {
          m.stacks.forEach(
              (name, stack) -> {
                ThreadTrack th = thread(name);
                th.stack.clear();
                th.stack.addAll(stack);
              });
        }
        if (m.waits != null) {
          snap.waits = m.waits;
        }
        if (m.allocations != null) {
          snap.memory.allocations = m.allocations;
        }
        if (m.generations != null) {
          gc.gen = m.generations;
        }
      }
      case "classes" -> {
        classes.clear();
        classes.addAll(m.classes);
        ClassSnap c = takeSnap();
        chist.add(new ClassSnap(m.t, c.count, c.bytes));
        Retention.history(chist, m.t);
      }
      default -> {}
    }
  }

  private ThreadTrack thread(String name) {
    for (ThreadTrack t : threads) {
      if (t.name.equals(name)) {
        return t;
      }
    }
    ThreadTrack t = new ThreadTrack(name, new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    threads.add(t);
    return t;
  }
}
