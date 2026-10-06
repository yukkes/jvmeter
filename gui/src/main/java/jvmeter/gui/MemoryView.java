package jvmeter.gui;

import static jvmeter.gui.OverviewView.gap;
import static jvmeter.gui.OverviewView.h2;
import static jvmeter.gui.OverviewView.note;
import static jvmeter.gui.Ui.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JComponent;
import jvmeter.core.ClassStat;
import jvmeter.core.Findings;
import jvmeter.core.Fmt;
import jvmeter.core.GcAnalysis;
import jvmeter.core.GcEvent;
import jvmeter.core.Session;
import jvmeter.core.Snapshot;
import jvmeter.core.Telemetry;

/**
 * Memory: heap usage over time with Before / After reference points and the classes, or the GC
 * analysis.
 */
final class MemoryView {

  private MemoryView() {}

  static JComponent build(App app) {
    Box root = col(0);
    root.add(head(app));
    if (app.state.memTab.equals("gc")) {
      gc(app, root);
    } else {
      root.add(gap(14));
      heap(app, root);
    }
    return root;
  }

  private static Box head(App app) {
    App.State st = app.state;
    List<JComponent> c = new ArrayList<>();
    c.add(
        seg(
            new String[][] {
              {"gc", "delete_sweep", "GC analysis"}, {"heap", "memory", "Heap & classes"}
            },
            st.memTab,
            k -> {
              st.memTab = k;
              st.copied = null;
              app.render();
            }));
    // the same controls on both tabs, so they stay in place; a tab's own buttons sit in its panels
    c.add(
        btn(
            "delete_sweep",
            "Run GC",
            () -> {
              if (app.live == null) {
                app.openStart(); // a snapshot or the sample: GC needs a JVM
                return;
              }
              // the GC event and a class histogram follow
              app.live.send(new jvmeter.core.Live.Command("gc", null));
              st.copied = null;
              app.render();
            }));
    return OverviewView.head("Memory", c.toArray(JComponent[]::new));
  }

  /** Before / After: in the Classes panel, whose table they compare */
  private static Box markBtns(App app) {
    App.State st = app.state;
    Btn before =
        btn(
            "bookmark_add",
            st.snapBefore != null ? "Re-mark Before" : "Mark Before",
            () -> pick(app, "before"));
    before.setToolTipText("Record the current per-class counts and sizes as Before");
    Btn after =
        btn(
            "bookmark_add",
            st.snapAfter != null ? "Re-mark After" : "Mark After",
            () -> pick(app, "after"));
    after.disabled(st.snapBefore == null);
    after.setToolTipText(
        st.snapBefore != null
            ? "Record the current state as After (shows the change from Before to After)"
            : "Mark Before first");
    return row(8, before, after);
  }

  /** .link with a 16px icon (Clear, Copy GC log) */
  static Btn linkIc(String icon, String label, Runnable action) {
    Btn b = new Btn(action, label);
    b.pad(4, 6, 4, 6).radius(6);
    b.hoverBg = Theme::accentSoft;
    b.with(ic(icon, 16, Theme::accent), txt(label, Theme.sans(13, 500), Theme::accent, 18));
    return b;
  }

  private static void pick(App app, String which) {
    if (which.equals("after") && app.state.snapBefore == null) {
      return;
    }
    app.state.picking = which;
    app.state.hoverT = null;
    app.render();
  }

  // ---------- heap & classes ----------

  private static void heap(App app, Box root) {
    App.State st = app.state;
    Session s = app.s;
    Double used = s.tel.isEmpty() ? s.heapAt(s.elapsed) : (Double) Telemetry.last(s.tel.heap);
    double max = s.snap.memory.heapMaxMB;
    int n = s.tel.heap.size();
    // the whole recording, as on the GC analysis chart (older telemetry points cover more seconds)
    double x1 = s.elapsed, x0 = 0;
    List<GcEvent> fulls = new ArrayList<>();
    if (s.gc != null) {
      for (GcEvent e : s.gc.events) {
        if (e.name.equals("Full") && e.t >= x0 && e.t <= x1) {
          fulls.add(e);
        }
      }
    }
    List<AxisChart.Mark> marks = new ArrayList<>();
    if (st.snapBefore != null) {
      marks.add(new AxisChart.Mark(st.snapBefore.t(), "Before"));
    }
    if (st.snapAfter != null) {
      marks.add(new AxisChart.Mark(st.snapAfter.t(), "After"));
    }
    String pk = st.picking == null ? null : st.picking.equals("before") ? "Before" : "After";

    AxisChart chart = new AxisChart(s, 600, 152, max, x0, x1, new int[] {6, 14, 12, 14});
    chart.marks = marks;
    chart.hlines = s.heapLimits();
    chart.fulls = fulls;
    chart.inner =
        (g, c) -> {
          // with GC events, before -> after at each event (every drop is a GC); otherwise the
          // per-second values
          Path2D.Double line = new Path2D.Double();
          boolean evs = s.gc != null && !s.gc.events.isEmpty();
          if (evs) {
            // heapAt() is null only without GC events
            //noinspection DataFlowIssue
            line.moveTo(c.x(x0), c.y(s.heapAt(x0)));
            for (GcEvent e : s.gc.events) {
              if (e.t > x0 && e.t < x1) {
                line.lineTo(c.x(e.t), c.y(e.beforeMB));
                line.lineTo(c.x(e.t), c.y(e.afterMB));
              }
            }
            //noinspection DataFlowIssue
            line.lineTo(c.x(x1), c.y(s.heapAt(x1)));
          } else if (n > 0) {
            line.moveTo(c.x(x0), c.y(s.tel.heap.get(0)));
            for (int i = 0; i < n; i++) {
              line.lineTo(c.x(s.tel.t.get(i)), c.y(s.tel.heap.get(i)));
            }
          }
          Path2D.Double area = new Path2D.Double();
          area.moveTo(c.px, c.py + c.ph);
          area.append(line, true);
          area.lineTo(c.px + c.pw, c.py + c.ph);
          area.closePath();
          g.setColor(Theme.alpha(Theme.run(), 16));
          g.fill(area);
          g.setColor(Theme.run());
          g.setStroke(new BasicStroke(evs ? 1.2f : 1.8f));
          g.draw(line);
        };
    if (pk != null) {
      chart.pickLabel = pk;
      chart.pickMin = st.picking.equals("after") ? st.snapBefore.t() : null;
      chart.hoverT = st.hoverT;
      chart.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
      chart.onPick =
          t -> {
            if (st.picking.equals("before")) {
              st.snapBefore = s.snapAt(t);
              if (st.snapAfter != null && st.snapAfter.t() <= t) {
                st.snapAfter = null;
              }
            } else {
              st.snapAfter = s.snapAt(t);
            }
            st.memSort = "diff";
            app.stopPicking();
          };
    }

    JComponent noteC;
    if (pk != null) {
      noteC =
          new Rich(20)
              .with("Click the chart to pick the ", Theme.sans(12, 400), Theme::fg)
              .with(pk, Theme.sans(12, 700), Theme::bad)
              .with(
                  " time" + (st.picking.equals("after") ? " (after Before)" : ""),
                  Theme.sans(12, 400),
                  Theme::fg);
    } else {
      Double xms = s.snap.memory.xmsMB;
      noteC =
          txt(
              (used != null ? "now " + Fmt.fixed(used, 0) + " MB · " : "")
                  + (xms != null && xms != 0 ? "-Xms" + Fmt.num(xms) + "m " : "")
                  + "-Xmx"
                  + Fmt.num(max)
                  + "m",
              Theme.num(Theme.sans(12, 400)),
              Theme::muted,
              20);
    }
    Box head = h2("Heap usage", noteC);
    if (pk != null) {
      Btn cancel = btn("close", "Cancel (Esc)", app::stopPicking);
      head.add(push(cancel));
    }
    List<Object> legend =
        new ArrayList<>(
            List.of(AxisChart.barItem(Theme::run, "Heap usage (drops = GC)"), AxisChart.gcItem()));
    if (!marks.isEmpty()) {
      legend.add(AxisChart.barItem(Theme::bad, "Before / After"));
    }
    Box panel = panel(Box.COL).with(head, chart, AxisChart.legend(legend.toArray()));
    app.pickTarget = pk != null ? panel : null;
    root.add(panel);
    root.add(gap(14));
    Box h =
        new Box(Box.ROW)
            .pad(12, 14, 0, 14)
            .gap(6)
            .with(
                txt("Classes", Theme.sans(13, 700), Theme::fg, 20),
                grow(new Box(Box.ROW)),
                markBtns(app));
    root.add(panel(Box.COL).with(h, abBar(app), classes(app)));
  }

  /** .ab-bar: what the table compares, or how to start */
  private static Box abBar(App app) {
    App.State st = app.state;
    Session s = app.s;
    Box b = new Box(Box.ROW).pad(4, 14, 0, 14).gap(10);
    Font f = Theme.sans(13, 400);
    if (st.snapBefore == null) {
      return b.add(
          txt(
                  "Mark Before to see changes from that point. Mark After to compare Before with"
                      + " After.",
                  f,
                  Theme::muted,
                  19)
              .wrap());
    }
    b.add(chip("Before", Fmt.tod(s.startMs, st.snapBefore.t())));
    b.add(txt("→", f, Theme::muted, 19));
    b.add(
        st.snapAfter != null
            ? chip("After", Fmt.tod(s.startMs, st.snapAfter.t()))
            : txt("Now " + Fmt.tod(s.startMs, s.elapsed), Theme.sans(13, 400), Theme::fg, 19));
    b.add(
        txt(
            "(change over "
                + Fmt.num((st.snapAfter != null ? st.snapAfter.t() : s.elapsed) - st.snapBefore.t())
                + " s)",
            f,
            Theme::muted,
            19));
    return b.add(
        linkIc(
            "close",
            "Clear",
            () -> {
              st.snapBefore = st.snapAfter = null;
              st.memSort = "bytes";
              app.render();
            }));
  }

  /** .ab-chip: a red tag and a time */
  private static Box chip(String tag, String time) {
    Box t = new Box(Box.ROW).pad(0, 5, 0, 5).bg(Theme::bad).radius(3);
    t.add(txt(tag, Theme.sans(11, 400), Theme::onBad, normalLh(11)));
    return row(4, t, txt(time, Theme.sans(13, 400), Theme::fg, 19));
  }

  private static Table classes(App app) {
    App.State st = app.state;
    Session s = app.s;
    Table t = new Table();
    t.sortKey = st.memSort;
    t.asc = st.memSort.equals("name");
    t.onSort =
        k -> {
          st.memSort = k;
          app.render();
        };
    String dir = st.memSort.equals("name") ? "asc" : "desc";
    if (st.snapBefore == null) {
      t.col("name", "Class", true).col("count", "Instances", false).col("bytes", "Size", false);
      Function<ClassStat, Comparable<?>> val =
          switch (st.memSort) {
            case "name" -> c -> c.name;
            case "count" -> c -> c.count;
            default -> c -> c.bytes;
          };
      List<ClassStat> rows = new ArrayList<>(s.classes);
      rows.sort(CpuView.sorter(val, dir));
      double maxB = s.classes.stream().mapToDouble(c -> c.bytes).max().orElse(1);
      for (ClassStat c : rows) {
        t.row(
            c.name,
            Table.method(c.name, null),
            Table.num(Fmt.fmtInt(c.count)),
            Table.meterNum(c.bytes / maxB, Fmt.fmtBytes(c.bytes)));
      }
    } else {
      t.col("name", "Class", true)
          .col("a", "Before", false)
          .col("b", st.snapAfter != null ? "After" : "Now", false)
          .col("diff", "Δ instances", false)
          .col("dbytes", "Δ size", false);
      Function<Session.AbRow, Comparable<?>> val =
          switch (st.memSort) {
            case "name" -> r -> r.c().name;
            case "a" -> r -> r.a();
            case "b" -> r -> r.b();
            case "dbytes" -> r -> r.dbytes();
            default -> r -> r.diff();
          };
      if (!List.of("name", "a", "b", "diff", "dbytes").contains(st.memSort)) {
        t.sortKey = "diff";
      }
      List<Session.AbRow> rows = s.abRows(st.snapBefore, st.snapAfter);
      rows.sort(CpuView.sorter(val, dir));
      for (Session.AbRow r : rows) {
        t.row(
            r.c().name,
            Table.method(r.c().name, null),
            Table.num(Fmt.fmtInt(r.a())),
            Table.num(Fmt.fmtInt(r.b())),
            Table.num(Fmt.signedInt(r.diff()), sign(r.diff())),
            Table.num(Fmt.signedBytes(r.dbytes()), sign(r.dbytes())));
      }
    }
    return t;
  }

  private static Supplier<Color> sign(double v) {
    return v > 0 ? Theme::bad : v < 0 ? Theme::good : Theme::fg;
  }

  // ---------- GC analysis ----------

  private static void gc(App app, Box root) {
    Session s = app.s;
    if (s.gc == null) {
      root.add(gap(14));
      root.add(
          panel(Box.COL)
              .add(
                  CpuView.empty(
                      "This snapshot has no GC data. Once the agent writes JFR"
                          + " jdk.GarbageCollection and related events (gc.events), a GCeasy-style"
                          + " analysis appears here.")));
      return;
    }
    GcAnalysis a = s.analyzeGc();
    if (a == null) {
      root.add(gap(14));
      root.add(panel(Box.COL).add(CpuView.empty("No GC has happened yet.")));
      return;
    }
    root.add(gap(8));
    // the summary line, with Copy GC log: the log of exactly these GCs
    App.State st = app.state;
    Btn copy =
        linkIc(
            "content_copy",
            "ok".equals(st.copied) ? "Copied" : "Copy GC log",
            () -> {
              java.awt.Toolkit.getDefaultToolkit()
                  .getSystemClipboard()
                  .setContents(new StringSelection(GcAnalysis.gcLog(s.gc)), null);
              st.copied = "ok";
              app.render();
            });
    copy.setToolTipText("Unified logging format (-Xlog:gc), ready to paste into GCeasy / GCViewer");
    root.add(
        new Box(Box.ROW)
            .gap(10)
            .with(
                grow(
                    txt(
                            s.gc.collector
                                + " · max heap "
                                + Fmt.fmtInt(s.gc.heapMaxMB)
                                + " MB · "
                                + Fmt.fmtClock(Math.round(a.dur))
                                + " recorded · "
                                + Fmt.fmtInt(a.n)
                                + " GCs (Young "
                                + Fmt.fmtInt(a.young)
                                + " / Mixed "
                                + a.mixed
                                + " / Full "
                                + a.full
                                + ")",
                            Theme.sans(12, 400),
                            Theme::muted,
                            18)
                        .wrap()),
                copy));
    root.add(gap(12));

    boolean tpOk = a.throughput >= 95, longP = a.max >= 200 || a.p95 >= 100;
    Box kpis = grid(new double[] {1}, 12);
    kpis.minCol = 140;
    kpis.add(
        kpi(
            tpOk ? Theme::good : Theme::bad,
            tpOk ? "check_circle" : "error",
            "Throughput",
            Fmt.fixed(a.throughput, 1) + " %",
            "target ≥ 95 %",
            true));
    kpis.add(
        kpi(null, null, "Avg pause", Fmt.fmtPause(a.avg), "p95 " + Fmt.fmtPause(a.p95), false));
    kpis.add(
        kpi(
            longP ? Theme::waitC : null,
            longP ? "warning" : null,
            "Max pause",
            Fmt.fmtPause(a.max),
            a.maxEv.causeLabel(),
            false));
    kpis.add(
        kpi(
            null,
            null,
            "Allocation rate",
            Fmt.fmtInt(Math.round(a.allocRate)) + " MB/s",
            "total " + Fmt.fixed(a.allocTotal / 1024, 1) + " GB",
            false));
    kpis.add(
        kpi(
            null,
            null,
            "Promotion rate",
            Fmt.fixed(a.promoRate, 1) + " MB/s",
            "Young → Old",
            false));

    // like the CPU view: the main column scrolls on its own, the detected problems fill the side
    // panel
    Box row2 = grid(new double[] {1, 1.3}, 14);
    row2.stretch = false;
    row2.with(hist(a), causes(a));
    Box main =
        new Box(Box.COL)
            .gap(14)
            .with(
                kpis,
                heapChart(s, a),
                row2,
                gens(a),
                allocations(s),
                txt(
                        "Source: JFR jdk.GarbageCollection / jdk.GCHeapSummary / jdk.G1HeapSummary"
                            + " / jdk.MetaspaceSummary. Metrics follow GCeasy's definitions"
                            + " (throughput = 100 − total pause ÷ recording time).",
                        Theme.sans(12, 400),
                        Theme::muted,
                        18)
                    .wrap());
    root.add(grow(split(scroll(main), panel(Box.COL).add(grow(scroll(problems(app, a)))), true)));
  }

  /**
   * .kpi: label (with an icon on the right), a big value and a small line; the color marks good /
   * bad / warn
   */
  private static Box kpi(
      Supplier<Color> color,
      String icon,
      String label,
      String value,
      String sub,
      boolean subColored) {
    Box k = panel(Box.COL).pad(12, 14, 12, 14).gap(2);
    Box l = new Box(Box.ROW).gap(6);
    l.add(grow(txt(label, Theme.sans(12, 400), Theme::muted, 18)));
    if (icon != null) {
      l.add(icFill(icon, 20, color));
    }
    k.add(l);
    k.add(txt(value, Theme.num(Theme.sans(22, 700)), Theme::fg, 33));
    k.add(txt(sub, Theme.sans(12, 400), subColored ? color : Theme::muted, 18));
    return k;
  }

  /** the side panel's content (.side: padding-bottom 12px) */
  private static Box problems(App app, GcAnalysis a) {
    Box p = new Box(Box.COL).pad(0, 0, 12, 0);
    p.add(h2("Detected problems", note("rule-based · " + a.problems.size())));
    Box list = new Box(Box.COL).pad(10, 12, 12, 12).gap(8);
    p.add(list);
    if (a.problems.isEmpty()) {
      list.add(
          problem(
              app,
              "ok",
              "check_circle",
              "No problems found",
              "Throughput, pauses and the heap trend are all within limits",
              null));
    }
    for (GcAnalysis.Problem pr : a.problems) {
      list.add(problem(app, pr.sev, pr.icon, pr.title, pr.detail, pr));
    }
    return p;
  }

  private static Box problem(
      App app, String sev, String icon, String title, String detail, GcAnalysis.Problem pr) {
    Supplier<Color> c =
        switch (sev) {
          case "bad" -> Theme::bad;
          case "warn" -> Theme::waitC;
          case "ok" -> Theme::good;
          default -> Theme::accent;
        };
    Supplier<Color> bg =
        switch (sev) {
          case "bad" -> () -> Theme.alpha(Theme.bad(), 12);
          case "warn" -> () -> Theme.alpha(Theme.waitC(), 16);
          case "ok" -> () -> Theme.alpha(Theme.good(), 12);
          default -> Theme::accentSoft;
        };
    Box b = new Box(Box.ROW).pad(10, 12, 10, 12).gap(10).bg(bg).radius(8);
    b.center = false;
    Box icon24 = new Box(Box.ROW);
    icon24.center = false;
    icon24.setPreferredSize(new java.awt.Dimension(24, 22));
    icon24.add(icFill(icon, 22, c));
    Box text = new Box(Box.COL).gap(4);
    text.add(txt(title, Theme.sans(14, 700), Theme::fg, 21).wrap());
    text.add(txt(detail, Theme.sans(13, 400), Theme::muted, 19).wrap());
    if (pr != null && pr.actionLabel != null) {
      Btn link =
          new Btn(
              () -> {
                App.State st = app.state;
                st.view = pr.actionGo;
                if (pr.actionMemTab != null) {
                  st.memTab = pr.actionMemTab;
                }
                if (pr.actionCpuTab != null) {
                  st.cpuTab = pr.actionCpuTab;
                }
                if (pr.actionQuery != null) {
                  app.setQuery(pr.actionQuery);
                }
                app.render();
              },
              pr.actionLabel);
      link.pad(2, 0, 2, 0).radius(6);
      link.hoverBg = Theme::accentSoft;
      link.add(txt(pr.actionLabel + " →", Theme.sans(13, 500), Theme::accent, 18));
      text.add(new Box(Box.ROW).add(link));
    } else {
      text.add(gap(0));
    }
    return b.with(icon24, grow(text));
  }

  private static Box heapChart(Session s, GcAnalysis a) {
    double maxMB = s.gc.heapMaxMB, dur = a.dur;
    boolean up = a.slopePerMin > 0;
    Supplier<Color> trend = up ? Theme::bad : Theme::good;
    AxisChart chart = new AxisChart(s, 600, 200, maxMB, 0, dur, new int[] {10, 14, 4, 14});
    chart.skipFirstTickLine = true;
    chart.fulls = a.fulls;
    chart.hlines = s.heapLimits();
    List<GcEvent> ev = s.gc.events;
    chart.inner =
        (g, c) -> {
          // from the start to the end of the recording, like the heap chart: heapAt() extends the
          // line before the first and after the last GC
          Path2D.Double saw = new Path2D.Double();
          // this chart is drawn only with GC events: heapAt() is not null
          //noinspection DataFlowIssue
          saw.moveTo(c.x(0), c.y(s.heapAt(0)));
          for (GcEvent e : ev) {
            saw.lineTo(c.x(e.t), c.y(e.beforeMB));
            saw.lineTo(c.x(e.t), c.y(e.afterMB));
          }
          //noinspection DataFlowIssue
          saw.lineTo(c.x(dur), c.y(s.heapAt(dur)));
          Path2D.Double area = new Path2D.Double();
          area.moveTo(c.px, c.py + c.ph);
          area.append(saw, true);
          area.lineTo(c.px + c.pw, c.py + c.ph);
          area.closePath();
          g.setColor(Theme.alpha(Theme.run(), 16));
          g.fill(area);
          g.setColor(Theme.alpha(Theme.run(), 70));
          g.setStroke(new BasicStroke(1));
          g.draw(saw);
          g.setColor(Theme.accent());
          double sx = c.pw / c.vbW, step = Math.ceil(ev.size() / 400.0);
          for (int i = 0; i < ev.size(); i++) {
            if (ev.size() < 400 || i % step == 0) {
              GcEvent e = ev.get(i);
              g.fill(
                  new Ellipse2D.Double(c.x(e.t) - 1.8 * sx, c.y(e.afterMB) - 1.8, 3.6 * sx, 3.6));
            }
          }
          g.setColor(trend.get());
          g.setStroke(
              new BasicStroke(
                  2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {6, 4}, 0));
          g.draw(new Line2D.Double(c.px, c.y(a.regAt(0)), c.px + c.pw, c.y(a.regAt(dur))));
        };
    Box dot =
        row(
            0,
            canvas(
                12,
                18,
                (g, k) -> {
                  g.setColor(Theme.accent());
                  g.fill(new Ellipse2D.Double(0, 4, 6, 6));
                }),
            txt("after GC", Theme.sans(12, 400), Theme::muted, 18));
    Rich tr =
        new Rich(18)
            .with("after-GC trend ", Theme.sans(12, 400), Theme::muted)
            .with(
                (a.slopePerMin >= 0 ? "+" : "") + Fmt.fixed(a.slopePerMin, 1) + " MB/min",
                Theme.num(Theme.sans(12, 700)),
                trend);
    Box trendItem =
        row(
            0,
            canvas(
                20,
                18,
                (g, k) -> {
                  g.setColor(trend.get());
                  g.fill(new Rectangle2D.Double(0, 6, 14, 4));
                }),
            tr);
    return panel(Box.COL)
        .with(
            h2("Heap (before / after GC)", note("after-GC points trending up suggest a leak")),
            chart,
            AxisChart.legend(
                AxisChart.barItem(Theme::run, "before → after GC"),
                dot,
                trendItem,
                AxisChart.gcItem()));
  }

  /** pause histogram: an svg of 300x170 scaled to fit (preserveAspectRatio meet) */
  private static Box hist(GcAnalysis a) {
    int hmax = Math.max(1, a.hist.stream().mapToInt(b -> b.count()).max().orElse(1));
    Font count = Theme.sans(11, 400), label = Theme.sans(10, 400);
    JComponent svg =
        canvas(
            0,
            170,
            (g, c) -> {
              double k = Math.min(c.getWidth() / 300.0, 1), ox = (c.getWidth() - 300 * k) / 2;
              g.translate(ox, 0);
              g.scale(k, k);
              for (int i = 0; i < a.hist.size(); i++) {
                GcAnalysis.Bucket b = a.hist.get(i);
                double h = b.count() > 0 ? Math.max(2, (double) b.count() / hmax * 120) : 0,
                    x = i * 50 + 7;
                g.setColor(
                    b.lo() >= 200 ? Theme.bad() : b.lo() >= 50 ? Theme.waitC() : Theme.accent());
                g.fill(new RoundRectangle2D.Double(x, 136 - h, 36, h, 4, 4));
                String n = String.valueOf(b.count());
                g.setFont(count);
                g.setColor(Theme.fg());
                draw(g, n, x + 18 - width(count, n) / 2, 130 - h);
                g.setFont(label);
                g.setColor(Theme.muted());
                draw(g, b.label(), x + 18 - width(label, b.label()) / 2, 154);
              }
              g.setColor(Theme.line());
              g.setStroke(new BasicStroke(1));
              g.draw(new Line2D.Double(0, 136, 300, 136));
            });
    return panel(Box.COL)
        .with(
            h2("Pause distribution", note("ms · " + Fmt.fmtInt(a.n) + " GCs")),
            new Box(Box.COL).pad(8, 14, 12, 14).add(svg));
  }

  private static Box causes(GcAnalysis a) {
    Table t =
        new Table()
            .col(null, "Cause", true)
            .col(null, "Count", false)
            .col(null, "Total", false)
            .col(null, "Avg", false)
            .col(null, "Max", false);
    t.auto = true;
    Font sans = Theme.sans(13, 400), bold = Theme.sans(13, 700), boldNum = Theme.num(bold);
    for (GcAnalysis.Cause c : a.causes) {
      boolean hl = c.cause.equals("System.gc()");
      t.row(
          null,
          Table.text(c.cause, hl ? bold : sans, hl ? Theme::bad : Theme::fg),
          Table.num(Fmt.fmtInt(c.count)),
          Table.num(Fmt.fmtPause(c.total)),
          Table.num(Fmt.fmtPause(c.total / c.count)),
          Table.num(Fmt.fmtPause(c.max)));
    }
    t.row(
        null,
        Table.text("Total", bold, Theme::fg),
        Table.text(Fmt.fmtInt(a.n), boldNum, Theme::fg),
        Table.text(Fmt.fmtPause(a.totalPause), boldNum, Theme::fg),
        Table.text(Fmt.fmtPause(a.avg), boldNum, Theme::fg),
        Table.text(Fmt.fmtPause(a.max), boldNum, Theme::fg));
    return panel(Box.COL).with(h2("GC causes", null), new Box(Box.COL).pad(0, 0, 0, 0).add(t));
  }

  /**
   * who allocates: allocation samples by place (the application method nearest to the allocation)
   * and class, the 20 largest
   */
  private static Box allocations(Session s) {
    List<Snapshot.Allocation> l =
        new ArrayList<>(s.snap.memory.allocations == null ? List.of() : s.snap.memory.allocations);
    l.sort((x, y) -> Double.compare(y.mb, x.mb));
    Box p = panel(Box.COL).with(h2("Who allocates", note("JFR allocation samples · estimated")));
    if (l.isEmpty()) {
      return p.with(
          CpuView.empty(
              s.snap.memory.allocations != null
                  ? "No allocation samples yet"
                  : "This snapshot has no allocation samples: newer agents record them"));
    }
    double total = l.stream().mapToDouble(x -> x.mb).sum(), max = l.get(0).mb;
    Table t =
        new Table()
            .col(null, "Where", true)
            .col(null, "Class", true)
            .col(null, "Allocated", false)
            .col(null, "Share", false);
    for (Snapshot.Allocation x : l.subList(0, Math.min(20, l.size()))) {
      t.row(
          x.site,
          Table.method(x.site, null),
          Table.text(x.cls, Table.TD, Theme::fg),
          Table.meterNum(x.mb / max, Findings.sizeText(x.mb)),
          Table.num(Fmt.fixed(x.mb / total * 100, 1) + " %"));
    }
    return p.with(t);
  }

  private static Box gens(GcAnalysis a) {
    // Young / Old / Metaspace side by side: repeat(auto-fit, minmax(180px, 1fr)), gap 14px 24px
    Box list = grid(new double[] {1}, 24).pad(12, 14, 14, 14);
    list.minCol = 180;
    list.rowGap = 14;
    list.stretch = false;
    if (a.gen == null || a.gen.youngMB == 0) {
      list.add(txt("No gc.generations", Theme.sans(13, 400), Theme::muted, 20));
    } else {
      double[][] g = {
        {a.peakYoung, a.gen.youngMB}, {a.peakOld, a.gen.oldMB}, {a.peakMeta, a.gen.metaspaceMB}
      };
      String[] names = {"Young", "Old", "Metaspace"};
      for (int i = 0; i < 3; i++) {
        double peak = g[i][0], alloc = g[i][1], pc = Math.min(100, peak / alloc * 100);
        Box head = new Box(Box.ROW);
        head.add(grow(txt(names[i], Theme.sans(13, 700), Theme::fg, 20)));
        head.add(
            txt(
                Fmt.fmtInt(Math.round(peak))
                    + " / "
                    + Fmt.fmtInt(alloc)
                    + " MB · "
                    + Fmt.fixed(pc, 0)
                    + " %",
                Theme.num(Theme.sans(12, 400)),
                Theme::muted,
                20));
        JComponent bar =
            canvas(
                0,
                14,
                (gr, c) -> {
                  RoundRectangle2D.Double track =
                      new RoundRectangle2D.Double(0, 4, c.getWidth(), 10, 6, 6);
                  gr.setColor(Theme.sunken());
                  gr.fill(track);
                  gr.clip(track);
                  gr.setColor(pc >= 80 ? Theme.waitC() : Theme.accent());
                  gr.fill(new Rectangle2D.Double(0, 4, c.getWidth() * Table.pct(pc / 100), 10));
                });
        list.add(col(0, head, bar));
      }
    }
    return panel(Box.COL).with(h2("Generation sizes", note("peak / committed")), list);
  }
}
