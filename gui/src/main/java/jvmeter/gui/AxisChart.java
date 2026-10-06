package jvmeter.gui;

import static jvmeter.gui.Ui.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import javax.swing.JComponent;
import jvmeter.core.Fmt;
import jvmeter.core.GcEvent;
import jvmeter.core.Session;

/**
 * The prototype's axis chart (.axis-chart): y labels on the left, a plot with a left and bottom
 * axis line, time labels below and "Time" under them. The plot is an SVG viewBox of W x H stretched
 * to the plot, so x is scaled and y is not. Full GCs get a dashed line and a trash icon just below
 * the time axis, whose labels then move up (.gc-below).
 */
final class AxisChart extends JComponent {

  /** paints the plot content; x(t) and y(v) map to pixels inside the plot */
  interface Inner {
    void paint(Graphics2D g, AxisChart c);
  }

  record Mark(double t, String label) {}

  static final Font LABEL = Theme.sans(11, 400),
      HLIM = Theme.sans(11, 400),
      TITLE = Theme.sans(11, 400);

  final Session s;
  final double vbW, vbH, ymax, x0, x1;

  /** padding of the .chart / .gc-chart box: top, right, bottom, left */
  final int[] pad;

  Inner inner;
  List<Mark> marks = new ArrayList<>();
  List<Session.Limit> hlines = new ArrayList<>();
  List<GcEvent> fulls = new ArrayList<>();

  /** the first x tick line is skipped (GC chart) */
  boolean skipFirstTickLine;

  /**
   * picking a reference time: label ("Before" / "After"), the earliest allowed time, and what to do
   * with the pick
   */
  String pickLabel;

  Double pickMin, hoverT;
  DoubleConsumer onPick;

  // plot geometry, set in layout: the svg area inside the plot's left and bottom border
  double px, py, pw, ph;
  int yw;

  AxisChart(Session s, double vbW, double vbH, double ymax, double x0, double x1, int[] pad) {
    this.s = s;
    this.vbW = vbW;
    this.vbH = vbH;
    this.ymax = ymax;
    this.x0 = x0;
    this.x1 = x1;
    this.pad = pad;
    setToolTipText("");
    MouseAdapter m =
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            if (pickLabel != null) {
              hoverT = inPlot(e) ? pickTime(e.getX()) : null;
              repaint();
            }
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            if (pickLabel != null && onPick != null && inPlot(e)) {
              onPick.accept(pickTime(e.getX()));
            }
          }
        };
    addMouseListener(m);
    addMouseMotionListener(m);
  }

  private boolean inPlot(MouseEvent e) {
    return e.getX() >= px - 1 && e.getX() <= px + pw && e.getY() >= py && e.getY() <= py + ph + 1;
  }

  /** the time under x, whole seconds; After is limited to after Before */
  double pickTime(double x) {
    double f = Math.min(1, Math.max(0, (x - (px - 1)) / (pw + 1)));
    double t = Math.round(x0 + f * (x1 - x0));
    if (pickMin != null) {
      t = Math.max(t, pickMin + 1);
    }
    return Math.min(t, x1);
  }

  double dur() {
    return Math.max(1, x1 - x0);
  }

  /** x of time t: the prototype's X(t).toFixed(1) in the viewBox, stretched to the plot */
  double x(double t) {
    return px + Math.round((t - x0) / dur() * vbW * 10) / 10.0 * pw / vbW;
  }

  double y(double v) {
    return py + Math.round((vbH - v / ymax * vbH) * 10) / 10.0;
  }

  /** position 0..1 of t on the axis (the prototype's percentages, toFixed(2)) */
  double pos(double t) {
    return Math.round((t - x0) / dur() * 10000) / 10000.0;
  }

  List<Double> yTicks() {
    List<Double> l = new ArrayList<>();
    for (double f : new double[] {0, 0.25, 0.5, 0.75, 1}) {
      l.add((double) Math.round(ymax * f));
    }
    return l;
  }

  List<Double> xTicks() {
    return Fmt.todTicks(s.startMs, x0, x1, Fmt.tickStep(dur()));
  }

  /** .gc-below: there are Full GC icons below the time axis */
  boolean gcBelow() {
    return !fulls.isEmpty();
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(0, pad[0] + 18 + (int) vbH + 1 + (gcBelow() ? 24 : 26) + 16 + pad[2]);
  }

  @Override
  public void doLayout() {
    yw = 38;
    for (double v : yTicks()) {
      yw = (int) Math.max(yw, Math.ceil(width(LABEL, Fmt.fmtInt(v))));
    }
    px = pad[3] + yw + 8 + 1;
    py = pad[0] + 18;
    pw = getWidth() - pad[1] - px;
    ph = vbH;
  }

  /**
   * below the mouse, moved so the tooltip stays inside the window (then it is drawn in it, not in a
   * window of its own)
   */
  @Override
  public java.awt.Point getToolTipLocation(MouseEvent e) {
    String text = getToolTipText(e);
    javax.swing.JRootPane root = getRootPane();
    if (text == null || root == null) {
      return null;
    }
    javax.swing.JToolTip tip = createToolTip();
    tip.setTipText(text);
    Dimension d = tip.getPreferredSize();
    java.awt.Point p = javax.swing.SwingUtilities.convertPoint(this, e.getX(), e.getY() + 20, root);
    p.x = Math.max(0, Math.min(p.x, root.getWidth() - d.width));
    if (p.y + d.height > root.getHeight()) {
      p.y -= 20 + d.height + 4;
    }
    return javax.swing.SwingUtilities.convertPoint(root, p, this);
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    for (GcEvent ev : fulls) {
      double cx = (px - 1) + pos(ev.t) * (pw + 1), cy = py + ph + 1 + 1 + 10.5;
      if (Math.abs(e.getX() - cx) <= 11 && Math.abs(e.getY() - cy) <= 12) {
        return "Full GC "
            + Fmt.tod(s.startMs, ev.t)
            + " · "
            + ev.causeLabel()
            + " · "
            + Fmt.num(ev.beforeMB)
            + " → "
            + Fmt.num(ev.afterMB)
            + " MB · pause "
            + Fmt.fmtPause(ev.pauseMs);
      }
    }
    return null;
  }

  @Override
  protected void paintComponent(Graphics g0) {
    doLayout();
    Graphics2D g = Theme.hints((Graphics2D) g0.create());
    Color muted = Theme.muted();
    // y title and labels
    text(g, "MB", TITLE, muted, pad[3] + yw - width(TITLE, "MB"), pad[0], 16);
    for (double v : yTicks()) {
      String l = Fmt.fmtInt(v);
      double top = py + Math.round((100 - v / ymax * 100) * 100) / 10000.0 * ph - 7;
      text(g, l, LABEL, muted, pad[3] + yw - width(LABEL, l), top, 14);
    }
    // grid
    Graphics2D p = (Graphics2D) g.create();
    p.setStroke(new BasicStroke(1));
    p.setColor(Theme.line());
    List<Double> yt = yTicks();
    for (double v : yt.subList(1, yt.size() - 1)) {
      p.draw(new Line2D.Double(px, y(v), px + pw, y(v)));
    }
    p.setColor(Theme.alpha(Theme.line(), 60));
    List<Double> xt = xTicks();
    for (double t : skipFirstTickLine && !xt.isEmpty() ? xt.subList(1, xt.size()) : xt) {
      p.draw(new Line2D.Double(x(t), py, x(t), py + ph));
    }
    if (inner != null) {
      Graphics2D c = (Graphics2D) p.create();
      inner.paint(c, this);
      c.dispose();
    }
    p.setColor(Theme.event());
    p.setStroke(
        new BasicStroke(
            1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {3, 3}, 0));
    for (GcEvent e : fulls) {
      p.draw(new Line2D.Double(x(e.t), py, x(e.t), py + ph));
    }
    p.setColor(Theme.limit());
    p.setStroke(
        new BasicStroke(
            0.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {5, 4}, 0));
    for (Session.Limit l : hlines) {
      p.draw(new Line2D.Double(px, y(l.v()), px + pw, y(l.v())));
    }
    p.setColor(Theme.bad());
    p.setStroke(new BasicStroke(2));
    for (Mark m : marks) {
      if (m.t() >= x0 && m.t() <= x1) {
        p.draw(new Line2D.Double(x(m.t()), py, x(m.t()), py + ph));
      }
    }
    p.dispose();
    // axis lines (the plot's border-left and border-bottom)
    g.setColor(muted);
    g.fill(new Rectangle2D.Double(px - 1, py, 1, ph + 1));
    g.fill(new Rectangle2D.Double(px - 1, py + ph, pw + 1, 1));
    // -Xmx / -Xms labels: right 6px, just above their line
    for (Session.Limit l : hlines) {
      double tw = width(HLIM, l.label()),
          top = py + Math.round((100 - l.v() / ymax * 100) * 100) / 10000.0 * ph - 15;
      double lx = px + pw - 6 - tw - 8;
      g.setColor(Theme.alpha(Theme.surface(), 85));
      g.fill(new RoundRectangle2D.Double(lx, top, tw + 8, 14, 6, 6));
      text(g, l.label(), HLIM, Theme.limit(), lx + 4, top, 14);
    }
    // Before / After labels at the top of their line
    Font mf = Theme.sans(11, 700);
    double mlh = normalLh(11);
    for (Mark m : marks) {
      if (m.t() >= x0 && m.t() <= x1) {
        double tw = width(mf, m.label()) + 10, cx = px + pos(m.t()) * pw;
        g.setColor(Theme.bad());
        g.fill(new RoundRectangle2D.Double(cx - tw / 2, py + 2, tw, mlh, 6, 6));
        text(g, m.label(), mf, Theme.onBad(), cx - tw / 2 + 5, py + 2, mlh);
      }
    }
    // time labels: centered on their tick, kept inside at both ends
    double axisTop = py + ph + 1, axisW = pw + 1;
    for (double t : xt) {
      String l = Fmt.tod(s.startMs, t);
      double q = pos(t) * 100, w = width(LABEL, l), lx = (px - 1) + pos(t) * axisW;
      lx = q < 3 ? lx : q > 97 ? lx - w : lx - w / 2;
      text(g, l, LABEL, muted, lx, axisTop + (gcBelow() ? 3 : 12), 14);
    }
    // Full GC icons
    for (GcEvent e : fulls) {
      Icons.paint(
          g, "delete", false, (px - 1) + pos(e.t) * axisW - 10.5, axisTop + 1, 21, Theme.event());
    }
    text(
        g,
        "Time",
        TITLE,
        muted,
        (px - 1) + (axisW - width(TITLE, "Time")) / 2,
        axisTop + (gcBelow() ? 24 : 26),
        16);
    // picking: hatched time before Before, and a red line with the time under the mouse
    if (pickLabel != null) {
      if (pickMin != null && pickMin >= x0) {
        // repeating-linear-gradient(45deg, muted 28% 0 6px, transparent 6px 12px)
        double w = Math.min(1, pos(pickMin)) * pw,
            period = 12 * Math.sqrt(2),
            stripe = 6 * Math.sqrt(2);
        Graphics2D h = (Graphics2D) g.create();
        h.clip(new Rectangle2D.Double(px, py, w, ph));
        h.setColor(Theme.alpha(muted, 28));
        for (double a = px - ph; a < px + w; a += period) {
          java.awt.geom.Path2D.Double st = new java.awt.geom.Path2D.Double();
          st.moveTo(a, py);
          st.lineTo(a + stripe, py);
          st.lineTo(a + stripe + ph, py + ph);
          st.lineTo(a + ph, py + ph);
          st.closePath();
          h.fill(st);
        }
        h.dispose();
      }
      if (hoverT != null) {
        double lx = px + pos(hoverT) * pw;
        g.setColor(Theme.bad());
        g.fill(new Rectangle2D.Double(lx, py, 2, ph + 1));
        String l = pickLabel + " " + Fmt.tod(s.startMs, hoverT);
        Font lf = Theme.sans(11, 400);
        double lh = normalLh(11), tw = width(lf, l) + 10;
        g.fill(new RoundRectangle2D.Double(lx + 4, py - 2, tw, lh, 6, 6));
        text(g, l, lf, Theme.onBad(), lx + 9, py - 2, lh);
      }
    }
    g.dispose();
  }

  // ---------- legend (.gc-legend) ----------

  /** a legend line: items of [swatch painter, text], gap 16, padding 0 14 12, 12px/18px muted */
  static JComponent legend(Object... items) {
    Box b = new Box(Box.ROW).pad(0, 14, 12, 14).gap(16);
    b.center = false;
    for (Object o : items) {
      b.add((JComponent) o);
    }
    return b;
  }

  /** a legend item with a 14x4 bar (vertical-align 3px) */
  static Box barItem(java.util.function.Supplier<Color> c, String label) {
    JComponent sw =
        canvas(
            20,
            18,
            (g, k) -> {
              g.setColor(c.get());
              g.fill(new Rectangle2D.Double(0, 6, 14, 4));
            });
    return row(0, sw, txt(label, Theme.sans(12, 400), Theme::muted, 18));
  }

  /** a legend item with the trash icon (15px, margin-right 3, vertical-align -3px) */
  static Box gcItem() {
    JComponent sw =
        canvas(18, 18, (g, k) -> Icons.paint(g, "delete", false, 0, 1, 15, Theme.event()));
    return row(0, sw, txt("Full GC", Theme.sans(12, 400), Theme::muted, 18));
  }
}
