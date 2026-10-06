package jvmeter.core;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a session as snapshot JSON.
 *
 * <p>The file starts with {@code about} and {@code summary}: a short, self-describing digest (hot
 * spots, GC KPIs and problems, blocked threads, findings) meant to be read first by people or by an
 * LLM asked to find the bottleneck. The raw data follows in the format {@link Snapshot#parse}
 * reads; {@code summary} is ignored when loading.
 */
public final class SnapshotWriter {

  public static final String FORMAT = "jvmeter-snapshot/1";
  private static final int TOP = 10;

  private SnapshotWriter() {}

  private static final io.avaje.jsonb.JsonType<Snapshot> JSON =
      Jsonb.builder().build().type(Snapshot.class);

  /** the snapshot file: about and summary first (for a reader), then the raw snapshot fields */
  public static String write(Session s) {
    Snapshot r = raw(s);
    r.format = FORMAT;
    r.about = about();
    r.summary = summary(s);
    return JSON.toJsonPretty(r) + "\n";
  }

  /**
   * Writes the snapshot as gzip-compressed JSON (read it with {@code zcat file.json.gz} or {@link
   * Snapshot#read}).
   */
  public static void write(Session s, Path file) throws IOException {
    try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
      out.write(write(s).getBytes(StandardCharsets.UTF_8));
    }
  }

  /** the "about" lines */
  private static List<String> about() {
    return List.of(
        "jvmeter JVM profiling snapshot. 'summary' is derived from the raw data that follows"
            + " and is the place to start; it is ignored when the file is loaded.",
        "Units: times in milliseconds (keys ending in Ms), seconds where the key ends in Sec,"
            + " sizes in MB.",
        "CPU times are estimated from JFR execution samples taken every 1 ms; call counts are"
            + " exact (bytecode instrumentation). self = time in the method's own code, total ="
            + " self + callees.",
        "cpu.tree: call tree from the thread roots; each node has name, self (ms), calls and"
            + " children. cpu.methods, when present, holds the exact call count per method.",
        "gc.events: one entry per GC with t (seconds since start), name (Young | Mixed | Full),"
            + " cause, pauseMs, heap beforeMB / afterMB, oldAfterMB and metaspaceMB; manual: true"
            + " when the GUI's Run GC started it (not the application's own System.gc()).",
        "threads[].segs: [startSec, endSec, state] with state run | wait | block (waiting for a"
            + " lock) | io; threads[].sec: seconds per state (sampled every 100 ms),"
            + " threads[].stack: the latest stack, top first.",
        "waits: where threads waited, from the same samples: state block (a monitor, or a lock"
            + " another thread owns) or io, site = the first frame in the application's code"
            + " (target.include: the counted packages), lock and owner, sec in total.",
        "memory.allocations: MB of each class allocated at each site (the same kind of site),"
            + " estimated from JFR allocation samples.",
        "Over time, older data is coarser: telemetry points (t = the second a point ends at, n"
            + " = the seconds it averages), memory.history (class histograms) and thread"
            + " segments cover 1 s for the last 10 min, 10 s up to 1 h, 1 min up to 6 h, then"
            + " 10 min. gc.events are all kept.");
  }

  /**
   * the session's current data as a snapshot (counts, GC events and threads change while recording)
   */
  static Snapshot raw(Session s) {
    Snapshot r = new Snapshot();
    r.target = s.snap.target;
    r.sample = s.snap.sample;
    r.startedAt =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(s.startMs), ZoneId.systemDefault())
            .toString();
    r.durationSec = s.elapsed;
    r.cpu = s.snap.cpu;
    r.memory = new Snapshot.Memory();
    r.memory.heapMaxMB = s.snap.memory.heapMaxMB;
    r.memory.xmsMB = s.snap.memory.xmsMB;
    r.memory.classes = s.classes;
    r.memory.history = s.chist.isEmpty() ? null : Session.history(s.chist);
    r.memory.allocations = s.snap.memory.allocations;
    r.telemetry = s.tel.isEmpty() ? null : s.tel;
    r.waits = s.snap.waits;
    for (ThreadTrack t : s.threads) {
      Snapshot.RawThread rt = new Snapshot.RawThread();
      rt.name = t.name;
      rt.stack = t.stack;
      rt.sec = t.sec;
      rt.segs = new ArrayList<>();
      for (Seg g : t.segs) {
        rt.segs.add(List.of(g.start, g.end, g.state));
      }
      r.threads.add(rt);
    }
    if (s.gc != null) {
      r.gc = new Snapshot.Gc();
      r.gc.collector = s.gc.collector;
      r.gc.heapMaxMB = s.gc.heapMaxMB;
      r.gc.generations = s.gc.gen;
      r.gc.events = s.gc.events;
    }
    return r;
  }

  // ---------- summary ----------

  static Map<String, Object> summary(Session s) {
    Map<String, Object> sum = new LinkedHashMap<>();
    List<String> findings = new ArrayList<>();
    double totalCpu = s.tree.root.total;

    Map<String, Object> cpu = new LinkedHashMap<>();
    cpu.put("sampledCpuMs", r1(totalCpu));
    List<CallTree.HotSpot> hot = new ArrayList<>(s.tree.hot);
    hot.sort(Comparator.comparingDouble((CallTree.HotSpot h) -> h.self).reversed());
    List<Object> hs = new ArrayList<>();
    for (CallTree.HotSpot h : hot.subList(0, Math.min(TOP, hot.size()))) {
      // total from the whole tree, so recursion and self-0 call sites are counted correctly
      CallTree.MethodInfo mi =
          Objects.requireNonNull(s.tree.methodInfo(h.name)); // every hot spot is in the tree
      Map<String, Object> m =
          map(
              "method",
              h.name,
              "selfMs",
              r1(h.self),
              "selfPercent",
              pct(h.self, totalCpu),
              "totalMs",
              r1(mi.total()));
      if (mi.calls() > 0) {
        m.put("calls", mi.calls());
        m.put("avgTotalPerCallMs", Fmt.round(mi.total() / mi.calls(), 6));
      }
      hs.add(m);
    }
    cpu.put("hotSpotsBySelfTime", hs);
    List<Object> path = new ArrayList<>();
    CallTree.Node cur = s.tree.root;
    while (!cur.children.isEmpty()) {
      CallTree.Node best = cur.children.get(0);
      for (CallTree.Node c : cur.children) {
        if (c.total > best.total) {
          best = c;
        }
      }
      cur = best;
      path.add(
          map(
              "method",
              cur.name,
              "totalMs",
              r1(cur.total),
              "totalPercent",
              pct(cur.total, totalCpu),
              "selfMs",
              r1(cur.self)));
    }
    cpu.put("hottestPath", path);
    sum.put("cpu", cpu);
    // what the Overview shows under "Where to look"; GC problems are listed one by one below
    for (Findings.Finding f : Findings.of(s, null, null)) {
      if (!f.sev.equals("ok") && !"gc".equals(f.memTab)) {
        findings.add(f.text() + ".");
      }
    }

    GcAnalysis a = s.analyzeGc();
    if (a != null) {
      Map<String, Object> gc = new LinkedHashMap<>();
      gc.put("collector", s.gc.collector);
      gc.put("gcCount", a.n);
      gc.put("young", a.young);
      gc.put("mixed", a.mixed);
      gc.put("full", a.full);
      gc.put("throughputPercent", r2(a.throughput));
      gc.put("avgPauseMs", r1(a.avg));
      gc.put("p95PauseMs", r1(a.p95));
      gc.put("maxPauseMs", r1(a.max));
      gc.put("maxPauseCause", a.maxEv.causeLabel());
      gc.put("allocationRateMBPerSec", r1(a.allocRate));
      gc.put("promotionRateMBPerSec", r2(a.promoRate));
      gc.put("heapAfterGcTrendMBPerMin", r1(a.slopePerMin));
      List<Object> causes = new ArrayList<>();
      for (GcAnalysis.Cause c : a.causes) {
        causes.add(
            map(
                "cause",
                c.cause,
                "count",
                c.count,
                "totalPauseMs",
                r1(c.total),
                "maxPauseMs",
                r1(c.max)));
      }
      gc.put("causes", causes);
      List<Object> problems = new ArrayList<>();
      for (GcAnalysis.Problem p : a.problems) {
        problems.add(map("severity", p.sev, "title", p.title, "detail", p.detail));
        if (!p.sev.equals("info")) {
          findings.add("GC: " + p.title + ". " + p.detail + ".");
        }
      }
      gc.put("problems", problems);
      sum.put("gc", gc);
    }

    Map<String, Object> th = new LinkedHashMap<>();
    th.put("count", s.threads.size());
    List<ThreadTrack> blocked = new ArrayList<>();
    for (ThreadTrack t : s.threads) {
      if (t.sum("block") > 0) {
        blocked.add(t);
      }
    }
    blocked.sort(Comparator.comparingDouble((ThreadTrack t) -> t.sum("block")).reversed());
    List<Object> bl = new ArrayList<>();
    for (ThreadTrack t : blocked) {
      bl.add(
          map(
              "thread",
              t.name,
              "blockedSec",
              r1(t.sum("block")),
              "stackTop",
              t.stack.isEmpty() ? null : t.stack.get(0)));
    }
    th.put("blockedOnLocks", bl);
    if (s.snap.waits != null) {
      List<Object> ws = new ArrayList<>();
      for (Snapshot.Wait w : s.snap.waits.subList(0, Math.min(TOP, s.snap.waits.size()))) {
        Map<String, Object> m = map("state", w.state, "waitedSec", r1(w.sec), "site", w.site);
        if (w.lock != null) {
          m.put("lock", w.lock);
          m.put("owner", w.owner);
        }
        m.put("top", w.top);
        m.put("threads", w.threads);
        ws.add(m);
      }
      th.put("waits", ws);
    }
    sum.put("threads", th);

    Map<String, Object> mem = new LinkedHashMap<>();
    mem.put("heapMaxMB", s.snap.memory.heapMaxMB);
    if (s.snap.memory.xmsMB != null) {
      mem.put("heapInitialMB", s.snap.memory.xmsMB);
    }
    List<ClassStat> cls = new ArrayList<>(s.classes);
    cls.sort(Comparator.comparingDouble((ClassStat c) -> c.bytes).reversed());
    List<Object> lc = new ArrayList<>();
    for (ClassStat c : cls.subList(0, Math.min(5, cls.size()))) {
      lc.add(map("class", c.name, "instances", c.count, "sizeMB", r1(c.bytes / 1048576.0)));
    }
    mem.put("largestClasses", lc);
    List<Snapshot.Allocation> al = s.snap.memory.allocations;
    if (al != null) {
      double total = al.stream().mapToDouble(x -> x.mb).sum();
      List<Object> top = new ArrayList<>();
      for (Snapshot.Allocation x : al.subList(0, Math.min(5, al.size()))) {
        top.add(
            map(
                "site",
                x.site,
                "class",
                x.cls,
                "allocatedMB",
                r1(x.mb),
                "percent",
                pct(x.mb, total)));
      }
      mem.put("allocationHotSpots", top);
    }
    sum.put("memory", mem);
    sum.put("findings", findings);
    return sum;
  }

  // ---------- helpers ----------

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static double r1(double v) {
    return Fmt.round(v, 1);
  }

  private static double r2(double v) {
    return Fmt.round(v, 2);
  }

  private static double pct(double v, double total) {
    return total > 0 ? r1(v / total * 100) : 0;
  }
}
