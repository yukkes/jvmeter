package jvmeter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "Where to look": the bottlenecks found in a session, most severe first, from the data already
 * recorded (call tree, thread samples, class histograms, telemetry, GC events). The Overview shows
 * them and the snapshot's summary.findings repeats them as text. Port of findings() in
 * demo/prototype.html.
 */
public final class Findings {

  /** kind: t (text), m (a method or class name), b (bold), n (a bold number) */
  public record Part(String kind, String text) {}

  /** sev: bad | warn | info | ok; the link opens a view (go: cpu | threads | memory) */
  public static final class Finding {
    public final String sev, icon;
    public final List<Part> line = new ArrayList<>();
    public String small, link, go, memTab, cpuTab, sel, thread;

    Finding(String sev, String icon) {
      this.sev = sev;
      this.icon = icon;
    }

    Finding t(String s) {
      line.add(new Part("t", s));
      return this;
    }

    Finding m(String s) {
      line.add(new Part("m", s));
      return this;
    }

    Finding b(String s) {
      line.add(new Part("b", s));
      return this;
    }

    Finding n(String s) {
      line.add(new Part("n", s));
      return this;
    }

    Finding small(String s) {
      small = s;
      return this;
    }

    Finding go(String link, String go) {
      this.link = link;
      this.go = go;
      return this;
    }

    /** the line and its detail as plain text */
    public String text() {
      StringBuilder b = new StringBuilder();
      line.forEach(p -> b.append(p.text));
      return b + (small != null ? " (" + small + ")" : "");
    }
  }

  private static final List<String> RANK = List.of("bad", "warn", "info", "ok");

  private Findings() {}

  /**
   * the application's code: the counted packages (as the agent matches them), else everything
   * outside the JDK
   */
  public static Predicate<String> own(String include) {
    List<String> p =
        include == null
            ? List.of()
            : Arrays.stream(include.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .map(
                    x ->
                        x.endsWith(".") || Character.isUpperCase(x.charAt(x.lastIndexOf('.') + 1))
                            ? x
                            : x + ".")
                .toList();
    return p.isEmpty()
        ? n -> !n.matches("(java|javax|jdk|sun|com\\.sun)\\..*")
        : n -> p.stream().anyMatch(n::startsWith);
  }

  /** before / after: the reference points picked on the Memory view (null: none) */
  public static List<Finding> of(Session s, Session.ClassSnap before, Session.ClassSnap after) {
    Predicate<String> own = own(s.snap.target.include);
    GcAnalysis ga = s.analyzeGc();
    List<Finding> out = new ArrayList<>();
    cpu(s, own, out);
    waits(s, own, out);
    memory(s, ga, before, after, out);
    threadGrowth(s, out);
    allocations(s, ga, out);
    if (ga != null) {
      GcAnalysis.Problem gp = ga.problems.isEmpty() ? null : ga.problems.get(0);
      Finding f =
          gp != null
              ? new Finding(gp.sev, gp.icon)
                  .t(gp.title)
                  .small(
                      gp.detail
                          + (ga.problems.size() > 1
                              ? " · " + (ga.problems.size() - 1) + " more"
                              : ""))
              : new Finding("ok", "check_circle")
                  .t("GC looks healthy")
                  .small(
                      "throughput "
                          + Fmt.fixed(ga.throughput, 1)
                          + " % · max pause "
                          + Fmt.fmtPause(ga.max));
      f.go("View GC analysis", "memory").memTab = "gc";
      out.add(f);
    }
    out.sort((a, b) -> RANK.indexOf(a.sev) - RANK.indexOf(b.sev));
    return out;
  }

  /**
   * CPU time of the library code a method calls counts for the nearest method of the application
   * above it
   */
  private static void cpu(Session s, Predicate<String> own, List<Finding> out) {
    List<Double> cpus = s.tel.recent(s.tel.cpu);
    List<Double> last = cpus.subList(Math.max(0, cpus.size() - 60), cpus.size());
    double avg = last.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    if (last.size() >= 10 && avg >= 90) {
      Finding f =
          new Finding("warn", "speed")
              .t("The process uses ")
              .n(Fmt.fixed(avg, 0) + " %")
              .t(" of all CPUs")
              .small("Work waits for a CPU: reduce the hot spots, or add CPUs")
              .go("View hot spots", "cpu");
      f.cpuTab = "hot";
      out.add(f);
    }
    double total = s.tree.root.total;
    if (total <= 0) {
      return;
    }
    Map<String, Double> mine = new LinkedHashMap<>();
    Map<String, Map<String, Double>> lib = new LinkedHashMap<>();
    for (CallTree.Node c : s.tree.root.children) {
      attribute(c, null, own, mine, lib);
    }
    String top = null;
    for (Map.Entry<String, Double> e : mine.entrySet()) {
      if (top == null || e.getValue() > mine.get(top)) {
        top = e.getKey();
      }
    }
    List<Map.Entry<String, Double>> libs =
        new ArrayList<>(lib.getOrDefault(top, Map.of()).entrySet());
    libs.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
    List<String> small = new ArrayList<>();
    for (Map.Entry<String, Double> e : libs.subList(0, Math.min(2, libs.size()))) {
      small.add(Fmt.shortName(e.getKey()) + " " + Fmt.fmtMs(e.getValue()));
    }
    double callees = libs.stream().mapToDouble(Map.Entry::getValue).sum();
    small.add("self " + Fmt.fmtMs(mine.get(top) - callees));
    String name = top;
    CallTree.HotSpot h =
        s.tree.hot.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
    if (h != null && h.calls > 0) {
      small.add("calls " + Fmt.calls(h.calls));
    }
    Finding f =
        new Finding("info", "local_fire_department")
            .t(Fmt.fixed(mine.get(top) / total * 100, 0) + " % of CPU time is in ")
            .m(top)
            .t(libs.isEmpty() ? "" : " and the library code it calls")
            .small(String.join(" · ", small))
            .go("View hot spots", "cpu");
    f.cpuTab = "hot";
    f.sel = top;
    out.add(f);
  }

  private static void attribute(
      CallTree.Node n,
      String owner,
      Predicate<String> own,
      Map<String, Double> mine,
      Map<String, Map<String, Double>> lib) {
    String o = own.test(n.name) ? n.name : owner;
    String k = o != null ? o : n.name;
    mine.merge(k, n.self, Double::sum);
    if (!k.equals(n.name)) {
      lib.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(n.name, n.self, Double::sum);
    }
    for (CallTree.Node c : n.children) {
      attribute(c, o, own, mine, lib);
    }
  }

  /**
   * where threads waited: the lock waited for longest, and the application method that waited
   * longest for I/O
   */
  private static void waits(Session s, Predicate<String> own, List<Finding> out) {
    List<Snapshot.Wait> waits = s.snap.waits == null ? List.of() : s.snap.waits;
    Group lock = top(waits, w -> w.state.equals("block"), w -> w.lock != null ? w.lock : w.site);
    Group io = top(waits, w -> w.state.equals("io") && own.test(w.site), w -> w.site);
    if (lock != null) {
      Snapshot.Wait w = lock.first;
      Finding f =
          new Finding(lock.sec >= 0.1 * Math.max(1, s.elapsed) ? "bad" : "warn", "lock")
              .t("Threads waited ")
              .n(sec(lock.sec))
              .t(w.lock != null ? " for " : " for a lock")
              .b(w.lock != null ? shortLock(w.lock) : "")
              .t(" in ")
              .m(w.site)
              .small(
                  (lock.owner != null ? "held by " + lock.owner + " · " : "")
                      + threads(lock.threads.size())
                      + " waiting")
              .go("View threads", "threads");
      f.thread = lock.threads.iterator().next();
      out.add(f);
    } else if (s.snap.waits == null) {
      // older snapshots: the thread blocked longest
      ThreadTrack b = null;
      for (ThreadTrack t : s.threads) {
        if (t.sum("block") > 0 && (b == null || t.sum("block") > b.sum("block"))) {
          b = t;
        }
      }
      if (b != null) {
        Finding f =
            new Finding("warn", "lock")
                .b(b.name)
                .t(" was blocked for " + Fmt.num(b.sum("block")) + " s in total")
                .small(b.stack.isEmpty() ? null : b.stack.get(0))
                .go("View threads", "threads");
        f.thread = b.name;
        out.add(f);
      }
    }
    if (io != null) {
      Finding f =
          new Finding("warn", "timer")
              .m(io.first.site)
              .t(" waited ")
              .n(sec(io.sec))
              .t(" for I/O")
              .small("in " + Fmt.shortName(io.first.top) + " · " + threads(io.threads.size()))
              .go("View threads", "threads");
      f.thread = io.threads.iterator().next();
      out.add(f);
    }
    if (out.stream().noneMatch(f -> "threads".equals(f.go))) {
      out.add(
          new Finding("ok", "view_timeline")
              .t(
                  "No thread waited for a lock"
                      + (s.snap.waits != null ? " or for I/O in the application's code" : ""))
              .go("View threads", "threads"));
    }
  }

  private static final class Group {
    double sec;
    Snapshot.Wait first;
    String owner;
    final Set<String> threads = new LinkedHashSet<>();
  }

  /**
   * the waits that match, grouped by key; the group waited longest (its largest wait first), or
   * null
   */
  private static Group top(
      List<Snapshot.Wait> waits,
      Predicate<Snapshot.Wait> match,
      java.util.function.Function<Snapshot.Wait, String> key) {
    Map<String, Group> groups = new LinkedHashMap<>();
    for (Snapshot.Wait w : waits) {
      if (!match.test(w)) {
        continue;
      }
      Group g = groups.computeIfAbsent(key.apply(w), k -> new Group());
      g.sec += w.sec;
      if (g.first == null || w.sec > g.first.sec) {
        g.first = w;
      }
      if (g.owner == null) {
        g.owner = w.owner;
      }
      g.threads.addAll(w.threads);
    }
    Group best = null;
    for (Group g : groups.values()) {
      if (g.sec > 0 && !g.threads.isEmpty() && (best == null || g.sec > best.sec)) {
        best = g;
      }
    }
    return best;
  }

  /**
   * the growth since Before, when it is set; else, when the heap after GC keeps growing, the class
   * that grew with it. Class histograms count unreachable objects too, so a class that grows while
   * the heap after GC does not is no leak.
   */
  private static void memory(
      Session s,
      GcAnalysis ga,
      Session.ClassSnap before,
      Session.ClassSnap after,
      List<Finding> out) {
    Finding f;
    if (before != null) {
      Session.AbRow g = null;
      for (Session.AbRow r : s.abRows(before, after)) {
        if (g == null || r.diff() > g.diff()) {
          g = r;
        }
      }
      String span = after != null ? "from Before to After" : "since Before";
      f =
          g != null && g.diff() > 0
              ? new Finding("warn", "memory")
                  .m(g.c().name)
                  .t(" grew by ")
                  .n("+" + Fmt.fmtInt(g.diff()))
                  .t(" instances " + span)
                  .small("A possible leak if it does not shrink after GC")
              : new Finding("ok", "memory").t("No class grew " + span);
    } else if (ga != null
        && ga.problems.stream().anyMatch(p -> p.title.equals(GcAnalysis.HEAP_GROWS))) {
      List<Session.ClassSnap> h = s.chist;
      String best = growing(h, own(s.snap.target.include));
      if (best == null) {
        return; // the GC finding says so, and links to the class diff
      }
      Session.ClassSnap a = h.get(0), z = h.get(h.size() - 1);
      f =
          new Finding("bad", "memory")
              .m(best)
              .t(" grew by ")
              .n("+" + Fmt.fmtInt(z.count().get(best) - a.count().get(best)))
              .t(" instances in " + span(z.t() - a.t()))
              .small(
                  "+"
                      + Fmt.fmtBytes(
                          z.bytes().getOrDefault(best, 0L) - a.bytes().getOrDefault(best, 0L))
                      + " across "
                      + h.size()
                      + " class histograms while the heap after GC grows. A possible leak");
    } else {
      f =
          new Finding("ok", "memory")
              .t("No sign of a memory leak")
              .small(
                  ga != null
                      ? "The heap after GC does not keep growing"
                      : "No GC yet: a leak shows as heap after GC that keeps growing");
    }
    f.go("View memory", "memory").memTab = "heap";
    out.add(f);
  }

  /**
   * the class whose instances grew most (by 10 % and 1 MB at least, falling in at most a tenth of
   * the histograms: leaked objects stay reachable), the application's own classes first (they say
   * what leaks, byte[] only what it holds), or null
   */
  private static String growing(List<Session.ClassSnap> h, Predicate<String> own) {
    int n = h.size();
    String best = null;
    double bestRatio = 0;
    if (n < 3 || h.get(n - 1).t() - h.get(0).t() < 60) {
      return null;
    }
    for (String c : h.get(n - 1).count().keySet().stream().sorted().toList()) {
      long first = h.get(0).count().getOrDefault(c, 0L), last = h.get(n - 1).count().get(c);
      int down = 0;
      for (int k = 1; k < n; k++) {
        if (h.get(k).count().getOrDefault(c, 0L) < h.get(k - 1).count().getOrDefault(c, 0L)) {
          down++;
        }
      }
      double ratio = (double) last / first;
      long grew = h.get(n - 1).bytes().getOrDefault(c, 0L) - h.get(0).bytes().getOrDefault(c, 0L);
      boolean better =
          best == null
              || own.test(c) && !own.test(best)
              || own.test(c) == own.test(best) && ratio > bestRatio;
      if (first > 0 && grew >= 1048576 && ratio >= 1.1 && down <= 0.1 * (n - 1) && better) {
        best = c;
        bestRatio = ratio;
      }
    }
    return best;
  }

  /** who allocates most, when a GC problem comes from allocation */
  private static void allocations(Session s, GcAnalysis ga, List<Finding> out) {
    List<Snapshot.Allocation> allocs =
        s.snap.memory.allocations == null ? List.of() : s.snap.memory.allocations;
    if (ga == null || allocs.isEmpty() || ga.problems.stream().noneMatch(p -> p.alloc)) {
      return;
    }
    Map<String, Double> bySite = new LinkedHashMap<>();
    double total = 0;
    for (Snapshot.Allocation a : allocs) {
      bySite.merge(a.site, a.mb, Double::sum);
      total += a.mb;
    }
    String top =
        bySite.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElseThrow()
            .getKey(); // the first on a tie
    Snapshot.Allocation cls =
        allocs.stream()
            .filter(a -> a.site.equals(top))
            .max(Comparator.comparingDouble(a -> a.mb))
            .orElseThrow();
    Finding f =
        new Finding("warn", "delete_sweep")
            .m(top)
            .t(" allocates ")
            .n(Fmt.fixed(bySite.get(top) / total * 100, 0) + " %")
            .t(" of the memory")
            .small(
                "mostly "
                    + cls.cls
                    + " · "
                    + sizeText(bySite.get(top))
                    + " in the recording. Fewer allocations mean fewer GCs")
            .go("View allocations", "memory");
    f.memTab = "gc";
    out.add(f);
  }

  /** threads that are started and never end */
  private static void threadGrowth(Session s, List<Finding> out) {
    Telemetry tel = s.tel;
    if (tel.t.size() < 2 || tel.t.get(tel.t.size() - 1) - tel.t.get(0) < 60) {
      return;
    }
    double first = tel.threads.get(0), last = Telemetry.last(tel.threads);
    if (last - first >= 20 && last >= 1.5 * first) {
      out.add(
          new Finding("warn", "view_timeline")
              .t("The thread count grew from ")
              .n(Fmt.fmtInt(first))
              .t(" to ")
              .n(Fmt.fmtInt(last))
              .small("Threads are started and do not end: check thread pools and executors")
              .go("View threads", "threads"));
    }
  }

  /** "com.example.OrderCache@3c4d" -> "OrderCache@3c4d" */
  public static String shortLock(String lock) {
    int at = lock.indexOf('@');
    return lock.substring(lock.lastIndexOf('.', at < 0 ? lock.length() : at) + 1);
  }

  public static String sizeText(double mb) {
    return mb >= 1024 ? Fmt.fixed(mb / 1024, 1) + " GB" : Fmt.fixed(mb, 0) + " MB";
  }

  public static String sec(double s) {
    return Fmt.num(Math.round(s * 10) / 10.0) + " s";
  }

  static String span(double s) {
    return s < 120 ? Fmt.num(Math.round(s)) + " s" : Fmt.num(Math.round(s / 60)) + " min";
  }

  static String threads(int n) {
    return n + (n == 1 ? " thread" : " threads");
  }
}
