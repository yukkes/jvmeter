package jvmeter.gui;

import static jvmeter.gui.Ui.*;

import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JComponent;
import jvmeter.core.Findings;
import jvmeter.core.Fmt;
import jvmeter.core.GcAnalysis;
import jvmeter.core.GcEvent;
import jvmeter.core.Session;
import jvmeter.core.Telemetry;
import jvmeter.core.ThreadTrack;

/** Overview: four tiles that open the detailed views, and "Where to look". */
final class OverviewView {

  private OverviewView() {}

  static JComponent build(App app) {
    Session s = app.s;
    App.State st = app.state;
    Box root = col(0);
    root.add(head("Overview"));
    root.add(gap(14));

    ThreadTrack blocked = null;
    double blockedSec = 0;
    for (ThreadTrack t : s.threads) {
      if (blocked == null || t.sum("block") > blockedSec) {
        blocked = t;
        blockedSec = t.sum("block");
      }
    }
    GcAnalysis ga = s.analyzeGc();
    // the sparklines show the one-second points (the last 10 minutes); before the first second
    // there are none
    Telemetry tel = s.tel;
    List<Double> cpus = tel.recent(tel.cpu),
        heaps = tel.recent(tel.heap),
        ths = tel.recent(tel.threads);
    Double heapNow = tel.isEmpty() ? s.heapAt(s.elapsed) : (Double) Telemetry.last(tel.heap);
    long blockedNow =
        s.threads.stream()
            .filter(t -> !t.segs.isEmpty() && t.segs.get(t.segs.size() - 1).state.equals("block"))
            .count();

    Box tiles = grid(new double[] {1, 1, 1, 1}, 14);
    tiles.stretch = true;
    tiles.minCol = 220;
    tiles.add(
        tile(
            "speed",
            "CPU (process)",
            "Open CPU hot spots",
            tel.isEmpty() ? "—" : Fmt.fixed(Telemetry.last(tel.cpu), 0) + " %",
            Theme::fg,
            "last " + cpus.size() + " s",
            spark(cpus, Theme::accent, 100, true),
            () -> {
              st.cpuTab = "hot";
              app.go("cpu");
            }));
    tiles.add(
        tile(
            "memory",
            "Heap usage",
            "Open memory: heap & classes",
            heapNow == null ? "—" : Fmt.fixed(heapNow, 0) + " MB",
            Theme::fg,
            "max " + Fmt.fmtInt(s.snap.memory.heapMaxMB) + " MB",
            spark(heaps, Theme::run, s.snap.memory.heapMaxMB, true),
            () -> {
              st.memTab = "heap";
              app.go("memory");
            }));
    if (ga != null) {
      List<Double> pauses = new ArrayList<>();
      List<GcEvent> ev = s.gc.events;
      for (GcEvent e : ev.subList(Math.max(0, ev.size() - 120), ev.size())) {
        pauses.add(e.pauseMs);
      }
      tiles.add(
          tile(
              "delete_sweep",
              "GC throughput",
              "Open memory: GC analysis",
              Fmt.fixed(ga.throughput, 1) + " %",
              ga.throughput < 95 ? Theme::bad : Theme::fg,
              Fmt.fmtInt(ga.n) + " GCs · max pause " + Fmt.fmtPause(ga.max),
              spark(pauses, Theme::bad, Math.max(50, ga.max), false),
              () -> {
                st.memTab = "gc";
                app.go("memory");
              }));
    } else {
      tiles.add(
          tile(
              "delete_sweep",
              "GC throughput",
              "Open memory: GC analysis",
              "—",
              Theme::fg,
              "No GC events",
              null,
              () -> {
                st.memTab = "gc";
                app.go("memory");
              }));
    }
    ThreadTrack longest = blocked;
    boolean anyBlocked = blockedSec > 0;
    tiles.add(
        tile(
            "view_timeline",
            "Threads",
            "Open threads (selects the longest-blocked thread)",
            tel.isEmpty() ? Fmt.num(s.threads.size()) : Fmt.num(Telemetry.last(tel.threads)),
            Theme::fg,
            blockedNow + " blocked",
            spark(ths, Theme::waitC, 40, true),
            () -> {
              if (anyBlocked) {
                st.thread = s.threads.indexOf(longest);
              }
              app.go("threads");
            }));
    root.add(tiles);
    root.add(gap(14));

    Box ins = panel(Box.COL);
    ins.add(h2("Where to look", null));
    boolean first = true;
    for (Findings.Finding f : Findings.of(s, st.snapBefore, st.snapAfter)) {
      Rich line = new Rich(20);
      for (Findings.Part p : f.line) {
        switch (p.kind()) {
          case "m" -> line.method(p.text(), 4);
          case "b" -> line.with(p.text(), Theme.sans(14, 700), Theme::fg);
          case "n" -> line.with(p.text(), Theme.num(Theme.sans(14, 700)), Theme::fg);
          default -> line.with(p.text(), Theme.sans(14, 400), Theme::fg);
        }
      }
      boolean warn = f.sev.equals("bad") || f.sev.equals("warn");
      ins.add(
          insight(
              first,
              f.icon,
              warn ? Theme::bad : Theme::accent,
              line,
              f.small,
              f.link,
              () -> {
                if (f.sel != null) {
                  st.sel = f.sel;
                }
                if (f.cpuTab != null) {
                  st.cpuTab = f.cpuTab;
                }
                if (f.memTab != null) {
                  st.memTab = f.memTab;
                }
                for (int i = 0; i < s.threads.size(); i++) {
                  if (s.threads.get(i).name.equals(f.thread)) {
                    st.thread = i;
                  }
                }
                app.go(f.go);
              }));
      first = false;
    }
    root.add(ins);
    return root;
  }

  // ---------- pieces shared with other views ----------

  /**
   * .view-head: the h1 and the view's controls; min-height 34 so the title sits at the same place
   * on every view
   */
  static Box head(String title, JComponent... controls) {
    Box h = new Box(Box.ROW).gap(14);
    h.minHeight = 34;
    h.add(grow(txt(title, Theme.sans(18, 700), Theme::fg, 28)));
    h.with(controls);
    return h;
  }

  /** .panel h2: bold 13px title with an optional note (padding 12px 14px 0, gap 6) */
  static Box h2(String title, JComponent note) {
    Box b = new Box(Box.ROW).pad(12, 14, 0, 14).gap(6);
    b.add(txt(title, Theme.sans(13, 700), Theme::fg, 20));
    if (note != null) {
      b.add(note);
    }
    return b;
  }

  /** .note in a heading: 12px muted */
  static Txt note(String text) {
    return txt(text, Theme.sans(12, 400), Theme::muted, 20);
  }

  static JComponent gap(int h) {
    JComponent c = new JComponent() {};
    c.setPreferredSize(new Dimension(0, h));
    return c;
  }

  private static Btn tile(
      String icon,
      String label,
      String tip,
      String value,
      Supplier<Color> valueColor,
      String sub,
      JComponent spark,
      Runnable action) {
    Btn t = new Btn(action, label);
    t.dir = Box.COL;
    t.pad(12, 14, 8, 14).gap(2).bg(Theme::surface).border(Theme::line).radius(10);
    t.hoverBorder = Theme::accent;
    t.setToolTipText(tip);
    Supplier<Color> go = () -> t.hover ? Theme.accent() : Theme.alpha(Theme.muted(), 55);
    Box lab =
        row(
            6,
            ic(icon, 20, Theme::muted),
            grow(txt(label, Theme.sans(12, 400), Theme::muted, 18)),
            ic("arrow_forward", 18, go));
    t.with(
        lab,
        txt(value, Theme.num(Theme.sans(22, 700)), valueColor, 33),
        txt(sub, Theme.sans(12, 400), Theme::muted, 18));
    if (spark != null) {
      t.add(new Box(Box.COL).pad(4, 0, 0, 0).add(spark));
    }
    return t;
  }

  /**
   * sparkline: an svg viewBox 300x48 stretched to the tile width (points rounded to 0.1 like the
   * prototype), 1.6px stroke, 14 % fill, a dot on the last value
   */
  static JComponent spark(List<Double> vals, Supplier<Color> color, double max, boolean fill) {
    if (vals.isEmpty()) {
      return null;
    }
    return canvas(
        0,
        48,
        (g, c) -> {
          int n = vals.size();
          if (n == 0) {
            return;
          }
          double h = 48, m = max > 0 ? max : 1, sx = c.getWidth() / 300.0;
          java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
          double lx = 0, ly = 0;
          for (int i = 0; i < n; i++) {
            lx = (double) i / Math.max(1, n - 1) * 300;
            ly = h - 2 - vals.get(i) / m * (h - 6);
            double x = Math.round(lx * 10) / 10.0 * sx, y = Math.round(ly * 10) / 10.0;
            if (i == 0) {
              p.moveTo(x, y);
            } else {
              p.lineTo(x, y);
            }
          }
          Color col = color.get();
          if (fill) {
            java.awt.geom.Path2D.Double a = (java.awt.geom.Path2D.Double) p.clone();
            a.lineTo(300 * sx, h);
            a.lineTo(0, h);
            a.closePath();
            g.setColor(Theme.alpha(col, 14));
            g.fill(a);
          }
          g.setColor(col);
          g.setStroke(
              new java.awt.BasicStroke(
                  SPARK_STROKE, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
          g.draw(p);
          g.fill(new java.awt.geom.Ellipse2D.Double((lx - 2.5) * sx, ly - 2.5, 5 * sx, 5));
        });
  }

  private static final float SPARK_STROKE = 1.6f;

  private static Box insight(
      boolean first,
      String icon,
      Supplier<Color> iconColor,
      Rich line,
      String small,
      String link,
      Runnable action) {
    Box r = new Box(Box.ROW).pad(10, 14, 10, 14).gap(10);
    r.ruleTop = !first;
    Box icBox = new Box(Box.ROW);
    icBox.setPreferredSize(new Dimension(28, 20));
    icBox.add(ic(icon, 20, iconColor));
    icBox.center = true;
    Box p = col(0, line);
    if (small != null) {
      p.add(txt(small, Theme.sans(12, 400), Theme::muted, 18));
    }
    r.with(icBox, grow(p), link(link, action));
    return r;
  }
}
