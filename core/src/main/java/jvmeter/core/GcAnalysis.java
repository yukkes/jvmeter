package jvmeter.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GCeasy-style KPIs, statistics, causes and problems, computed from GC events (see
 * docs/gc-analysis.md).
 */
public final class GcAnalysis {

  public record Bucket(String label, double lo, int count) {}

  public static final class Cause {
    public final String cause;
    public int count;
    public double total;
    public double max;

    Cause(String cause) {
      this.cause = cause;
    }
  }

  /**
   * sev: "bad" | "warn" | "info"; action fields are null when there is no link; alloc: less
   * allocation would help
   */
  public static final class Problem {
    public final String sev, icon, title, detail;
    public String actionLabel, actionGo, actionMemTab, actionCpuTab, actionQuery;
    public boolean alloc;

    Problem(String sev, String icon, String title, String detail) {
      this.sev = sev;
      this.icon = icon;
      this.title = title;
      this.detail = detail;
    }

    Problem alloc(boolean a) {
      alloc = a;
      return this;
    }
  }

  /** the title of the leak rule, which Findings looks for */
  public static final String HEAP_GROWS = "Heap after GC keeps growing";

  public int n;
  public double dur, throughput, avg, p95, max, totalPause, allocRate, promoRate, allocTotal;
  public GcEvent maxEv;
  public final List<Bucket> hist = new ArrayList<>();
  public final List<Cause> causes = new ArrayList<>();

  /** regression of after-GC heap: value at t = my + slopePerSec * (t - mx) */
  public double slopePerMin;

  private double regMx, regMy, slopePerSec;
  public final List<GcEvent> fulls = new ArrayList<>();
  public int fullBurst;
  public double peakYoung, peakOld, peakMeta;
  public Snapshot.Generations gen;
  public int young, mixed, full;
  public final List<Problem> problems = new ArrayList<>();

  public double regAt(double t) {
    return regMy + slopePerSec * (t - regMx);
  }

  /** null when there are no events */
  public static GcAnalysis of(GcData g, double durSec) {
    List<GcEvent> ev = g.events;
    int n = ev.size();
    if (n == 0) {
      return null;
    }
    GcAnalysis a = new GcAnalysis();
    a.n = n;
    double[] pauses = new double[n];
    for (int i = 0; i < n; i++) {
      pauses[i] = ev.get(i).pauseMs;
    }
    java.util.Arrays.sort(pauses);
    double totalPause = 0;
    for (double p : pauses) {
      totalPause += p;
    }
    a.totalPause = totalPause;
    a.dur = Math.max(Math.max(durSec, ev.get(n - 1).t), 1);
    a.throughput = 100 - totalPause / (a.dur * 1000) * 100;
    a.max = pauses[n - 1];
    a.avg = totalPause / n;
    a.p95 = pauses[Math.min(n - 1, (int) Math.floor(0.95 * n))];
    GcEvent maxEv = ev.get(0);
    for (GcEvent e : ev) {
      if (e.pauseMs > maxEv.pauseMs) {
        maxEv = e;
      }
    }
    a.maxEv = maxEv;
    double[][] buckets = {
      {0, 10}, {10, 20}, {20, 50}, {50, 100}, {100, 200}, {200, Double.POSITIVE_INFINITY}
    };
    for (double[] b : buckets) {
      int c = 0;
      for (GcEvent e : ev) {
        if (e.pauseMs >= b[0] && e.pauseMs < b[1]) {
          c++;
        }
      }
      String label =
          Double.isInfinite(b[1]) ? Fmt.num(b[0]) + "+" : Fmt.num(b[0]) + "–" + Fmt.num(b[1]);
      a.hist.add(new Bucket(label, b[0], c));
    }
    Map<String, Cause> causes = new LinkedHashMap<>();
    for (GcEvent e : ev) {
      Cause c = causes.computeIfAbsent(e.causeLabel(), Cause::new);
      c.count++;
      c.total += e.pauseMs;
      c.max = Math.max(c.max, e.pauseMs);
    }
    a.causes.addAll(causes.values());
    a.causes.sort(Comparator.comparingDouble((Cause c) -> c.total).reversed());
    // allocation = growth from the previous after-GC to this before-GC; promotion = growth of Old
    // after GC
    double alloc = 0, promo = 0;
    for (int i = 1; i < n; i++) {
      alloc += Math.max(0, ev.get(i).beforeMB - ev.get(i - 1).afterMB);
      promo += Math.max(0, or0(ev.get(i).oldAfterMB) - or0(ev.get(i - 1).oldAfterMB));
    }
    a.allocTotal = alloc;
    a.allocRate = alloc / a.dur;
    a.promoRate = promo / a.dur;
    // regression line of the after-GC heap (MB/min), including manual GCs
    double mx = 0, my = 0;
    for (GcEvent e : ev) {
      mx += e.t;
      my += e.afterMB;
    }
    mx /= n;
    my /= n;
    double sxy = 0, sxx = 0;
    for (GcEvent e : ev) {
      sxy += (e.t - mx) * (e.afterMB - my);
      sxx += (e.t - mx) * (e.t - mx);
    }
    a.slopePerSec = sxx != 0 ? sxy / sxx : 0;
    a.slopePerMin = a.slopePerSec * 60;
    a.regMx = mx;
    a.regMy = my;
    for (GcEvent e : ev) {
      if (e.name.equals("Full")) {
        a.fulls.add(e);
      }
    }
    for (GcEvent f : a.fulls) {
      int c = 0;
      for (GcEvent o : a.fulls) {
        if (o.t >= f.t && o.t < f.t + 60) {
          c++;
        }
      }
      a.fullBurst = Math.max(a.fullBurst, c);
    }
    a.gen = g.gen;
    a.peakYoung = Double.NEGATIVE_INFINITY;
    a.peakOld = Double.NEGATIVE_INFINITY;
    a.peakMeta = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < n; i++) {
      GcEvent e = ev.get(i);
      a.peakYoung =
          Math.max(
              a.peakYoung,
              e.beforeMB - (i > 0 ? or0(ev.get(i - 1).oldAfterMB) : or0(e.oldAfterMB)));
      a.peakOld = Math.max(a.peakOld, or0(e.oldAfterMB));
      a.peakMeta = Math.max(a.peakMeta, or0(e.metaspaceMB));
      switch (e.name) {
        case "Young" -> a.young++;
        case "Mixed" -> a.mixed++;
        default -> {}
      }
    }
    a.full = a.fulls.size();
    a.rules(g);
    return a;
  }

  private static double or0(Double d) {
    return d == null ? 0 : d;
  }

  private Cause cause(String name) {
    for (Cause c : causes) {
      if (c.cause.equals(name)) {
        return c;
      }
    }
    return null;
  }

  /** problem detection (rules in docs/gc-analysis.md), most severe first */
  private void rules(GcData g) {
    double growth = slopePerMin * dur / 60;
    if (slopePerMin > 0 && growth >= g.heapMaxMB * 0.03) {
      Problem p =
          new Problem(
              "bad",
              "error",
              HEAP_GROWS,
              "+"
                  + Fmt.fixed(slopePerMin, 1)
                  + " MB/min (+"
                  + Fmt.fixed(growth, 0)
                  + " MB during the recording). Possible leak");
      p.actionLabel = "View class diff";
      p.actionGo = "memory";
      p.actionMemTab = "heap";
      problems.add(p);
    }
    if (throughput < 95) {
      problems.add(
          new Problem(
                  "bad",
                  "error",
                  "Throughput is " + Fmt.fixed(throughput, 1) + " %",
                  "More than 5 % of the time is spent in GC. Review the heap size or the allocation"
                      + " volume")
              .alloc(true));
    }
    if (fullBurst >= 3) {
      problems.add(
          new Problem(
                  "bad",
                  "error",
                  fullBurst + " Full GCs within one minute",
                  "The heap is most likely too small")
              .alloc(true));
    }
    // the live data: what every GC of the second half left
    double low = Double.POSITIVE_INFINITY;
    int late = 0;
    for (GcEvent e : g.events) {
      if (e.t >= dur / 2) {
        low = Math.min(low, e.afterMB);
        late++;
      }
    }
    if (late >= 3 && low >= 0.8 * g.heapMaxMB) {
      problems.add(
          new Problem(
                  "bad",
                  "error",
                  "Heap stays " + Fmt.fixed(low / g.heapMaxMB * 100, 0) + " % full after GC",
                  "At least "
                      + Fmt.fmtInt(Math.round(low))
                      + " of "
                      + Fmt.fmtInt(g.heapMaxMB)
                      + " MB is in use after every GC in the second half. Raise -Xmx, or keep less"
                      + " data reachable")
              .alloc(true));
    }
    Cause sys = cause("System.gc()");
    if (sys != null) {
      Problem p =
          new Problem(
              "warn",
              "warning",
              "System.gc() called " + sys.count + (sys.count == 1 ? " time" : " times"),
              "Triggers a Full GC with pauses up to "
                  + Fmt.fmtPause(sys.max)
                  + ". Consider -XX:+DisableExplicitGC");
      p.actionLabel = "Find the caller";
      p.actionGo = "cpu";
      p.actionCpuTab = "tree";
      p.actionQuery = "System.gc";
      problems.add(p);
    }
    List<GcEvent> unasked = new ArrayList<>();
    for (GcEvent e : fulls) {
      if (!e.isManual()
          && !List.of(
                  "System.gc()",
                  "Diagnostic Command",
                  "Heap Inspection Initiated GC",
                  "Heap Dump Initiated GC")
              .contains(e.cause)) {
        unasked.add(e);
      }
    }
    if (List.of("G1", "ZGC", "Shenandoah").contains(g.collector)
        && !unasked.isEmpty()
        && fullBurst < 3) {
      int k = unasked.size();
      problems.add(
          new Problem(
                  "warn",
                  "warning",
                  k + " Full " + (k == 1 ? "GC" : "GCs") + " the application did not request",
                  g.collector
                      + " stopped all threads to collect the whole heap (cause "
                      + unasked.get(0).cause
                      + "). The heap is too small for the allocation rate, or humongous objects"
                      + " fragment it")
              .alloc(true));
    }
    if (max >= 200 || p95 >= 100) {
      problems.add(
          new Problem(
                  "warn",
                  "timer",
                  "Long pause: up to " + Fmt.fmtPause(max),
                  "p95 " + Fmt.fmtPause(p95) + " · cause " + maxEv.causeLabel())
              .alloc(!maxEv.isManual() && !"System.gc()".equals(maxEv.cause)));
    }
    // against MaxMetaspaceSize: without one (the default) Metaspace grows as needed, and used is
    // always close to committed
    if (gen != null
        && gen.metaspaceMaxMB != null
        && gen.metaspaceMaxMB != 0
        && peakMeta / gen.metaspaceMaxMB >= 0.9) {
      problems.add(
          new Problem(
              "warn",
              "warning",
              "Metaspace is nearly full",
              "peak "
                  + Fmt.num(peakMeta)
                  + " / "
                  + Fmt.num(gen.metaspaceMaxMB)
                  + " MB (MaxMetaspaceSize)"));
    }
    if (dur >= 60 && promoRate >= 1 && promoRate >= 0.2 * allocRate) {
      problems.add(
          new Problem(
                  "warn",
                  "warning",
                  "Objects reach the old generation quickly",
                  Fmt.fixed(promoRate, 1)
                      + " of "
                      + Fmt.fmtInt(Math.round(allocRate))
                      + " MB/s allocated is promoted: short-lived objects survive young GCs. Raise"
                      + " the young generation size, or find what holds them")
              .alloc(true));
    }
    GcEvent m0 = null, m1 = null;
    for (GcEvent e : g.events) {
      if (e.metaspaceMB != null && e.metaspaceMB > 0) {
        if (m0 == null) {
          m0 = e;
        }
        m1 = e;
      }
    }
    if (m0 != null
        && m1.t - m0.t >= 60
        && m1.metaspaceMB - m0.metaspaceMB >= Math.max(20, 0.2 * m0.metaspaceMB)) {
      problems.add(
          new Problem(
              "warn",
              "warning",
              "Metaspace keeps growing",
              "+"
                  + Fmt.fixed(m1.metaspaceMB - m0.metaspaceMB, 0)
                  + " MB ("
                  + Fmt.num(m0.metaspaceMB)
                  + " → "
                  + Fmt.num(m1.metaspaceMB)
                  + " MB): classes are loaded and not unloaded. Look for class loaders created"
                  + " again and again (proxies, scripts, redeploys)"));
    }
    Cause hum = cause("G1 Humongous Allocation");
    if (hum != null) {
      problems.add(
          new Problem(
              "info",
              "info",
              hum.count + " humongous " + (hum.count == 1 ? "allocation" : "allocations"),
              "Arrays larger than half a region. Split them or raise G1HeapRegionSize"));
    }
  }

  /** unified logging (-Xlog:gc) format, ready to paste into GCeasy / GCViewer */
  public static String gcLog(GcData g) {
    StringBuilder b = new StringBuilder("[0.001s][info][gc] Using ").append(g.collector);
    for (int i = 0; i < g.events.size(); i++) {
      GcEvent e = g.events.get(i);
      String kind;
      if (e.name.equals("Full")) {
        kind = "Pause Full (" + (e.isManual() ? "System.gc()" : e.cause) + ")";
      } else if (e.name.equals("Mixed")) {
        kind = "Pause Young (Mixed) (" + e.cause + ")";
      } else if (e.cause.equals("G1 Humongous Allocation")) {
        kind = "Pause Young (Concurrent Start) (" + e.cause + ")";
      } else {
        kind = "Pause Young (Normal) (" + e.cause + ")";
      }
      b.append('\n')
          .append('[')
          .append(Fmt.fixed(e.t, 3))
          .append("s][info][gc] GC(")
          .append(i)
          .append(") ")
          .append(kind)
          .append(' ')
          .append(Fmt.num(e.beforeMB))
          .append("M->")
          .append(Fmt.num(e.afterMB))
          .append("M(")
          .append(Fmt.num(g.heapMaxMB))
          .append("M) ")
          .append(Fmt.fixed(e.pauseMs, 3))
          .append("ms");
    }
    return b.toString();
  }
}
