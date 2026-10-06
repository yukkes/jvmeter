package jvmeter.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.LineMetrics;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;

/**
 * The few building blocks the prototype's CSS needs: a box with flex-like layout (row / column /
 * grid), text with CSS line height and wrapping, icons, and buttons.
 */
final class Ui {

  private Ui() {}

  /**
   * a tooltip that shows its text as it is: the text comes from snapshots and profiled JVMs
   * (method, thread and class names), and Swing would render one starting with "<html>" as HTML,
   * loading the images it names
   */
  static JToolTip plain(JToolTip t) {
    t.putClientProperty("html.disable", Boolean.TRUE);
    return t;
  }

  // ---------- box: background, border, radius, padding, and a flex / grid layout ----------

  static class Box extends JComponent {
    static final int ROW = 0, COL = 1, GRID = 2;

    int dir;
    int gap, rowGap;

    /** ROW: center children vertically (align-items: center); otherwise top */
    boolean center = true;

    /** COL: children take the full inner width (align-items: stretch) */
    boolean stretch = true;

    double[] fr; // GRID column weights

    /**
     * GRID repeat(auto-fit, minmax(minCol, 1fr)): as many equal columns as fit, at most one per
     * child
     */
    int minCol;

    Supplier<Color> bg, border, hoverBg, hoverBorder;
    int radius;
    int top, right, bottom, left;
    int minHeight;
    boolean hover;

    /** 1px rules on single sides (CSS border-top / -right / -bottom) */
    boolean ruleTop, ruleRight, ruleBottom, ruleLeft;

    /** ROW: justify-content: space-between */
    boolean between;

    /** clip the children to the rounded inner box (CSS overflow: hidden) */
    boolean clip;

    @Override
    public JToolTip createToolTip() {
      return plain(super.createToolTip());
    }

    Box(int dir) {
      this.dir = dir;
      setLayout(null);
    }

    /** JComponent has none of its own: null, so a name could not be set on a box */
    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) {
        accessibleContext =
            new AccessibleJComponent() {
              @Override
              public javax.accessibility.AccessibleRole getAccessibleRole() {
                return role();
              }
            };
      }
      return accessibleContext;
    }

    /** what a screen reader calls it */
    javax.accessibility.AccessibleRole role() {
      return javax.accessibility.AccessibleRole.PANEL;
    }

    Box pad(int t, int r, int b, int l) {
      top = t;
      right = r;
      bottom = b;
      left = l;
      return this;
    }

    Box gap(int g) {
      gap = g;
      rowGap = g;
      return this;
    }

    Box bg(Supplier<Color> c) {
      bg = c;
      return this;
    }

    Box border(Supplier<Color> c) {
      border = c;
      return this;
    }

    Box radius(int r) {
      radius = r;
      return this;
    }

    Box with(Component... cs) {
      for (Component c : cs) {
        super.add(c);
      }
      return this;
    }

    @Override
    public Box add(Component c) {
      super.add(c);
      return this;
    }

    int bw() {
      return border != null ? 1 : 0;
    }

    @Override
    public Insets getInsets() {
      int b = bw();
      return new Insets(
          top + b + (ruleTop ? 1 : 0),
          left + b + (ruleLeft ? 1 : 0),
          bottom + b + (ruleBottom ? 1 : 0),
          right + b + (ruleRight ? 1 : 0));
    }

    private List<Component> kids() {
      List<Component> l = new ArrayList<>();
      for (Component c : getComponents()) {
        if (c.isVisible()) {
          l.add(c);
        }
      }
      return l;
    }

    static int prefH(Component c, int w) {
      return c instanceof Box b
          ? b.heightFor(w)
          : c instanceof Txt t ? t.heightFor(w) : c.getPreferredSize().height;
    }

    static boolean grows(Component c) {
      return c instanceof JComponent j && Boolean.TRUE.equals(j.getClientProperty("grow"));
    }

    static boolean pushed(Component c) {
      return c instanceof JComponent j && Boolean.TRUE.equals(j.getClientProperty("push"));
    }

    @Override
    public Dimension getPreferredSize() {
      if (isPreferredSizeSet()) {
        return super.getPreferredSize();
      }
      int w = (int) Math.ceil(fracWidth() - 0.01);
      return new Dimension(w, heightFor(w));
    }

    /** the exact (fractional) preferred width, like the browser's max-content width */
    double fracWidth() {
      if (isPreferredSizeSet()) {
        return super.getPreferredSize().width;
      }
      Insets in = getInsets();
      List<Component> k = kids();
      double w = 0;
      for (Component c : k) {
        w = dir == ROW ? w + fracW(c) : Math.max(w, fracW(c));
      }
      if (dir == ROW) {
        w += gap * Math.max(0, k.size() - 1);
      }
      return w + in.left + in.right;
    }

    static double fracW(Component c) {
      return c instanceof Box b
          ? b.fracWidth()
          : c instanceof Txt t && !t.isPreferredSizeSet()
              ? t.width(t.text)
              : c instanceof Rich r ? r.fracWidth() : c.getPreferredSize().width;
    }

    /** preferred height when laid out at width w */
    int heightFor(int w) {
      if (isPreferredSizeSet()) {
        return super.getPreferredSize().height;
      }
      Insets in = getInsets();
      int iw = w - in.left - in.right;
      List<Component> k = kids();
      int h = 0;
      if (dir == ROW) {
        int[] ws = rowWidths(k, iw);
        for (int i = 0; i < k.size(); i++) {
          h = Math.max(h, prefH(k.get(i), ws[i]));
        }
      } else if (dir == COL) {
        for (Component c : k) {
          h += prefH(c, stretch ? iw : Math.min(iw, c.getPreferredSize().width));
        }
        h += gap * Math.max(0, k.size() - 1);
      } else {
        double[] xs = gridXs(iw);
        int n = fr.length;
        for (int r = 0; r * n < k.size(); r++) {
          int rh = 0;
          for (int i = r * n; i < Math.min(k.size(), r * n + n); i++) {
            int col = i - r * n;
            rh = Math.max(rh, prefH(k.get(i), (int) Math.round(xs[col * 2 + 1])));
          }
          h += rh + (r > 0 ? rowGap : 0);
        }
      }
      return Math.max(minHeight, h + in.top + in.bottom);
    }

    private int[] rowWidths(List<Component> k, int iw) {
      double[] f = rowWidthsF(k, iw);
      int[] ws = new int[f.length];
      for (int i = 0; i < f.length; i++) {
        ws[i] = (int) Math.round(f[i]);
      }
      return ws;
    }

    private double[] rowWidthsF(List<Component> k, double iw) {
      double[] ws = new double[k.size()];
      double used = gap * Math.max(0, k.size() - 1);
      int growers = 0;
      for (int i = 0; i < k.size(); i++) {
        if (grows(k.get(i))) {
          growers++;
        } else {
          ws[i] = fracW(k.get(i));
          used += ws[i];
        }
      }
      for (int i = 0; i < k.size(); i++) {
        if (grows(k.get(i))) {
          ws[i] = Math.max(0, (iw - used) / growers);
        }
      }
      return ws;
    }

    /** [x0, w0, x1, w1, ...] relative to the inner left edge */
    private double[] gridXs(int iw) {
      if (minCol > 0) {
        int n = Math.max(1, Math.min(getComponentCount(), (iw + gap) / (minCol + gap)));
        fr = new double[n];
        java.util.Arrays.fill(fr, 1);
      }
      double total = 0;
      for (double f : fr) {
        total += f;
      }
      double free = iw - gap * (fr.length - 1.0), x = 0;
      double[] out = new double[fr.length * 2];
      for (int i = 0; i < fr.length; i++) {
        double cw = free * fr[i] / total;
        out[i * 2] = x;
        out[i * 2 + 1] = cw;
        x += cw + gap;
      }
      return out;
    }

    @Override
    public void doLayout() {
      Insets in = getInsets();
      int iw = getWidth() - in.left - in.right, ih = getHeight() - in.top - in.bottom;
      List<Component> k = kids();
      // horizontal positions are fractional like the browser's and snapped to pixels the same way:
      // each edge rounds its exact position (ox = this box's exact x minus its pixel x)
      double ox = ox(this);
      if (dir == ROW) {
        double[] ws = rowWidthsF(k, iw);
        double used = gap * Math.max(0, k.size() - 1);
        for (double w : ws) {
          used += w;
        }
        double x = ox + in.left,
            extra = between && k.size() > 1 ? Math.max(0, iw - used) / (k.size() - 1) : 0;
        for (int i = 0; i < k.size(); i++) {
          Component c = k.get(i);
          if (pushed(c)) {
            x += Math.max(0, iw - used);
          }
          if (i > 0) {
            x += extra;
          }
          int l = (int) Math.round(x), r = (int) Math.round(x + ws[i]);
          int h = Math.min(ih, prefH(c, r - l));
          int y = center ? in.top + (int) Math.round((ih - h) / 2.0) : in.top;
          c.setBounds(l, y, r - l, h);
          setOx(c, x - l);
          x += ws[i] + gap;
        }
      } else if (dir == COL) {
        int y = in.top;
        for (Component c : k) {
          int w = stretch ? iw : Math.min(iw, c.getPreferredSize().width);
          int h = grows(c) ? Math.max(0, in.top + ih - y) : prefH(c, w);
          c.setBounds(in.left, y, w, h);
          setOx(c, ox);
          y += h + gap;
        }
      } else {
        double[] xs = gridXs(iw);
        int n = fr.length, y = in.top;
        for (int r = 0; r * n < k.size(); r++) {
          int rh = 0;
          for (int i = r * n; i < Math.min(k.size(), r * n + n); i++) {
            rh = Math.max(rh, prefH(k.get(i), (int) Math.round(xs[(i - r * n) * 2 + 1])));
          }
          for (int i = r * n; i < Math.min(k.size(), r * n + n); i++) {
            int col = i - r * n;
            double ex = ox + in.left + xs[col * 2];
            int x0 = (int) Math.round(ex) - in.left,
                x1 = (int) Math.round(ex + xs[col * 2 + 1]) - in.left;
            Component c = k.get(i);
            setOx(c, ex - in.left - x0);
            // align-items: start keeps each card at its own height; stretch fills the row
            c.setBounds(in.left + x0, y, x1 - x0, stretch ? rh : prefH(c, x1 - x0));
          }
          y += rh + rowGap;
        }
      }
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Color b = hover && hoverBg != null ? hoverBg.get() : bg != null ? bg.get() : null;
      Color line =
          hover && hoverBorder != null ? hoverBorder.get() : border != null ? border.get() : null;
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      // CSS clamps the corner radius to half the shorter side (border-radius: 999px makes a pill)
      int arc = Math.min(radius * 2, Math.min(getWidth(), getHeight()));
      if (ruleTop || ruleRight || ruleBottom || ruleLeft) {
        g.setColor(Theme.line());
        if (ruleLeft) {
          g.fillRect(0, 0, 1, getHeight());
        }
        if (ruleTop) {
          g.fillRect(0, 0, getWidth(), 1);
        }
        if (ruleBottom) {
          g.fillRect(0, getHeight() - 1, getWidth(), 1);
        }
        if (ruleRight) {
          g.fillRect(getWidth() - 1, 0, 1, getHeight());
        }
      }
      if (b != null) {
        g.setColor(b);
        int t = ruleTop ? 1 : 0;
        int l = ruleLeft ? 1 : 0;
        g.fill(
            new RoundRectangle2D.Double(
                l,
                t,
                getWidth() - l - (ruleRight ? 1 : 0),
                getHeight() - t - (ruleBottom ? 1 : 0),
                arc,
                arc));
      }
      if (line != null) {
        g.setColor(line);
        g.setStroke(new BasicStroke(1));
        g.draw(
            new RoundRectangle2D.Double(
                0.5, 0.5, getWidth() - 1, getHeight() - 1, arc - 1, arc - 1));
      }
      g.dispose();
    }

    @Override
    protected void paintChildren(Graphics g0) {
      if (!clip) {
        super.paintChildren(g0);
        return;
      }
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      int b = bw(),
          arc = Math.max(0, Math.min(radius * 2, Math.min(getWidth(), getHeight())) - 2 * b);
      g.clip(new RoundRectangle2D.Double(b, b, getWidth() - 2 * b, getHeight() - 2 * b, arc, arc));
      super.paintChildren(g);
      g.dispose();
    }
  }

  /** a component's exact x minus its pixel x (set by Box layouts) */
  static double ox(Component c) {
    return c instanceof JComponent j && j.getClientProperty("ox") instanceof Double d ? d : 0;
  }

  static void setOx(Component c, double v) {
    if (c instanceof JComponent j) {
      j.putClientProperty("ox", v);
    }
  }

  static Box row(int gap, Component... cs) {
    return new Box(Box.ROW).gap(gap).with(cs);
  }

  static Box col(int gap, Component... cs) {
    return new Box(Box.COL).gap(gap).with(cs);
  }

  static Box grid(double[] fr, int gap, Component... cs) {
    Box b = new Box(Box.GRID).gap(gap).with(cs);
    b.fr = fr;
    return b;
  }

  /** .panel: surface card with a 1px line border and 10px radius */
  static Box panel(int dir) {
    return new Box(dir).bg(Theme::surface).border(Theme::line).radius(10);
  }

  static <T extends JComponent> T grow(T c) {
    c.putClientProperty("grow", true);
    return c;
  }

  static <T extends JComponent> T push(T c) {
    c.putClientProperty("push", true);
    return c;
  }

  // ---------- text ----------

  /**
   * Text laid out like a CSS line box: line height lh, baseline at half-leading + ascent; wraps
   * when narrower than the text.
   */
  static class Txt extends JComponent {
    String text;
    Font font;
    Supplier<Color> color;
    double lh;
    boolean wrap, ellipsis;

    /** overflow-wrap: anywhere (a word longer than the line breaks between any characters) */
    boolean anywhere;

    /** command-line options (-XX:…) stay whole: the prototype's nowrap spans (nobrOpts) */
    boolean options;

    /** "left" | "right" | "center" */
    String align = "left";

    Txt(String text, Font font, Supplier<Color> color, double lh) {
      this.text = text;
      this.font = font;
      this.color = color;
      this.lh = lh;
    }

    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) {
        accessibleContext =
            new AccessibleJComponent() {
              @Override
              public javax.accessibility.AccessibleRole getAccessibleRole() {
                return javax.accessibility.AccessibleRole.LABEL;
              }

              @Override
              public String getAccessibleName() {
                return text;
              }
            };
      }
      return accessibleContext;
    }

    Txt wrap() {
      wrap = true;
      return this;
    }

    Txt ellipsis() {
      ellipsis = true;
      return this;
    }

    Txt align(String a) {
      align = a;
      return this;
    }

    double width(String s) {
      return Ui.width(font, s);
    }

    @Override
    public Dimension getPreferredSize() {
      return isPreferredSizeSet()
          ? super.getPreferredSize()
          : new Dimension((int) Math.ceil(width(text)), (int) Math.ceil(lh));
    }

    int heightFor(int w) {
      return wrap ? (int) Math.ceil(lines(w).size() * lh) : (int) Math.ceil(lh);
    }

    /** lines at width w, broken where Unicode allows (spaces, after hyphens), like the browser */
    List<String> lines(int w) {
      List<String> out = new ArrayList<>();
      if (!wrap || width(text) <= w + 0.5) {
        out.add(text);
        return out;
      }
      int start = 0, last = 0;
      for (int end : breaks(text, options)) {
        if (width(text.substring(start, end).stripTrailing()) > w + 0.5 && last > start) {
          out.add(text.substring(start, last).stripTrailing());
          start = last;
        }
        last = end;
      }
      out.add(text.substring(start).stripTrailing());
      if (anywhere) {
        List<String> split = new ArrayList<>();
        for (String l : out) {
          while (width(l) > w + 0.5 && l.length() > 1) {
            int n = l.length() - 1;
            while (n > 1 && width(l.substring(0, n)) > w + 0.5) {
              n--;
            }
            split.add(l.substring(0, n));
            l = l.substring(n);
          }
          split.add(l);
        }
        return split;
      }
      return out;
    }

    /**
     * break opportunities like the browser's for this kind of text: after spaces, and after a
     * hyphen before a letter; with options, not inside a word that starts with a hyphen (a
     * command-line option in a nowrap span)
     */
    static List<Integer> breaks(String t, boolean options) {
      List<Integer> b = new ArrayList<>();
      boolean option = options && (t.startsWith("-") || t.startsWith("\"-"));
      for (int i = 1; i < t.length(); i++) {
        char p = t.charAt(i - 1);
        if (p == ' ' && t.charAt(i) != ' ') {
          b.add(i);
          option = options && (t.startsWith("-", i) || t.startsWith("\"-", i));
        } else if (p == '-' && Character.isLetter(t.charAt(i)) && !option) {
          b.add(i);
        }
      }
      b.add(t.length());
      return b;
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      g.setFont(font);
      g.setColor(color.get());
      double y = Ui.baseline(font, lh);
      for (String line : lines(getWidth())) {
        String s = ellipsis ? fit(line, getWidth()) : line;
        double w = width(s);
        double x =
            align.equals("right")
                ? getWidth() - w
                : align.equals("center") ? (getWidth() - w) / 2 : ox(this);
        draw(g, s, x, Math.round(y));
        y += lh;
      }
      g.dispose();
    }

    String fit(String s, int w) {
      return ellipsize(font, s, w);
    }
  }

  /**
   * when set, receives every drawn string with its device position (start x, baseline y, width);
   * used by the screenshot tests
   */
  static TextSink trace;

  interface TextSink {
    void text(String s, double x, double y, double w, double size);
  }

  /** draws s with the graphics' font and color, baseline at y */
  static void draw(Graphics2D g, String s, double x, double y) {
    Font f = g.getFont();
    String t = Theme.shown(f, s);
    g.setFont(Theme.forText(f, t));
    g.drawString(t, (float) x, (float) y);
    g.setFont(f);
    if (trace != null && !s.isBlank()) {
      java.awt.geom.Point2D p =
          g.getTransform().transform(new java.awt.geom.Point2D.Double(x, y), null);
      double k = Math.sqrt(Math.abs(g.getTransform().getDeterminant()));
      trace.text(s, p.getX(), p.getY(), width(g.getFont(), s) * k, g.getFont().getSize2D() * k);
    }
  }

  /** advance width of s, measured like the painting (fractional, antialiased) */
  static double width(Font f, String s) {
    String t = Theme.shown(f, s);
    return Theme.forText(f, t).getStringBounds(t, Theme.FRC).getWidth();
  }

  /**
   * baseline offset in a CSS line box of height lh, computed like Blink: the font's ascent and
   * descent rounded to pixels, and the half-leading above the text floored
   */
  static double baseline(Font f, double lh) {
    LineMetrics m = f.getLineMetrics("Hg", Theme.FRC);
    double a = Math.round(m.getAscent()), d = Math.round(m.getDescent());
    return Math.floor((lh - (a + d)) / 2) + a;
  }

  /** draws s in a line box of height lh whose top is at y; returns the advance */
  static double text(Graphics2D g, String s, Font f, Color c, double x, double y, double lh) {
    g.setFont(f);
    g.setColor(c);
    draw(g, s, x, Math.round(y + baseline(f, lh)));
    return width(f, s);
  }

  /**
   * the longest prefix of s that fits w with an ellipsis (CSS text-overflow: ellipsis); s itself
   * when it fits
   */
  static String ellipsize(Font f, String s, double w) {
    if (width(f, s) <= w + 0.5) {
      return s;
    }
    // like the browser, the first character stays even when it and the ellipsis do not fit (they
    // are clipped)
    int n = s.length();
    while (n > 1 && width(f, s.substring(0, n) + "…") > w + 0.01) {
      n--;
    }
    return s.substring(0, n) + "…";
  }

  static final Font M_FONT = Theme.sans(13, 400);

  /** natural width of a method name (the prototype's .m) */
  static double methodWidth(String name) {
    return width(M_FONT, name);
  }

  /**
   * Paints a method name like the prototype's .m: package muted, Class.method in fg. When it does
   * not fit w the package shrinks first (down to 2ch) and only then Class.method, each with an
   * ellipsis. Matches of q are highlighted.
   */
  static void method(Graphics2D g, String name, String q, double x, double y, double w, Color fg) {
    int i = jvmeter.core.Fmt.packageEnd(name);
    String pkg = i > 0 ? name.substring(0, i) : "", cm = name.substring(pkg.length());
    double wp = width(M_FONT, pkg), wc = width(M_FONT, cm), over = wp + wc - w;
    double ap = wp, ac = wc;
    if (over > 0) {
      ap = Math.max(Math.min(wp, 2 * width(M_FONT, "0")), wp - over);
      ac = Math.max(0, wc - (over - (wp - ap)));
      ap = Math.floor(ap * 64) / 64;
      ac = Math.floor(ac * 64) / 64;
    }
    Graphics2D c = (Graphics2D) g.create();
    c.clip(new java.awt.geom.Rectangle2D.Double(x, y, Math.max(0, w), 16));
    if (!pkg.isEmpty()) {
      segment(c, pkg, q, x, y, ap, Theme.muted());
    }
    segment(c, cm, q, x + ap, y, ac, fg);
    c.dispose();
  }

  private static void segment(
      Graphics2D g, String s, String q, double x, double y, double w, Color color) {
    String shown = ellipsize(M_FONT, s, w);
    if (q != null && !q.isEmpty()) {
      String low = shown.toLowerCase(Locale.ROOT), ql = q.toLowerCase(Locale.ROOT);
      for (int k = low.indexOf(ql); k >= 0; k = low.indexOf(ql, k + ql.length())) {
        double a = x + width(M_FONT, shown.substring(0, k)),
            b = width(M_FONT, shown.substring(k, k + ql.length()));
        g.setColor(Theme.alpha(Theme.waitC(), 45));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(a, y, b, 16, 4, 4));
      }
    }
    Graphics2D c = (Graphics2D) g.create();
    c.clip(new java.awt.geom.Rectangle2D.Double(x, y, Math.max(0, w), 16));
    text(c, shown, M_FONT, color, x, y, 16);
    c.dispose();
  }

  static Txt txt(String s, Font f, Supplier<Color> c, double lh) {
    return new Txt(s, f, c, lh);
  }

  /**
   * One line of mixed runs (plain, bold, method names), each with its own CSS box: top offset and
   * line height.
   */
  static class Rich extends JComponent {
    record Run(String text, Font font, Supplier<Color> color, double top, double lh) {}

    final List<Run> runs = new ArrayList<>();
    final double lh;

    Rich(double lh) {
      this.lh = lh;
    }

    Rich with(String text, Font font, Supplier<Color> color) {
      runs.add(new Run(text, font, color, 0, lh));
      return this;
    }

    Rich with(String text, Font font, Supplier<Color> color, double top, double runLh) {
      runs.add(new Run(text, font, color, top, runLh));
      return this;
    }

    /**
     * a method name: package muted, Class.method in the text color (the prototype's .m, line-height
     * 16px)
     */
    void method(String name, double top) {
      int i = jvmeter.core.Fmt.packageEnd(name);
      Font f = Theme.sans(13, 400);
      if (i > 0) {
        with(name.substring(0, i), f, Theme::muted, top, 16);
      }
      with(name.substring(i), f, Theme::fg, top, 16);
    }

    double width(Run r) {
      return Ui.width(r.font, r.text);
    }

    double fracWidth() {
      double w = 0;
      for (Run r : runs) {
        w += width(r);
      }
      return w;
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension((int) Math.ceil(fracWidth() - 0.01), (int) Math.ceil(lh));
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      double x = ox(this);
      for (Run r : runs) {
        double base = r.top + baseline(r.font, r.lh);
        g.setFont(r.font);
        g.setColor(r.color.get());
        draw(g, r.text, x, Math.round(base));
        x += width(r);
      }
      g.dispose();
    }
  }

  // ---------- icons ----------

  static class Ic extends JComponent {
    String name;
    boolean fill;
    int size;
    Supplier<Color> color;

    Ic(String name, boolean fill, int size, Supplier<Color> color) {
      this.name = name;
      this.fill = fill;
      this.size = size;
      this.color = color;
      setPreferredSize(new Dimension(size, size));
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      double x = ox(this) + (getWidth() - size) / 2.0;
      int y = (getHeight() - size) / 2;
      Icons.paint(g, name, fill, x, y, size, color.get());
      g.dispose();
    }
  }

  static Ic ic(String name, int size, Supplier<Color> c) {
    return new Ic(name, false, size, c);
  }

  static Ic icFill(String name, int size, Supplier<Color> c) {
    return new Ic(name, true, size, c);
  }

  // ---------- buttons ----------

  /**
   * A box that acts as a button: hover background, hand cursor, click / Enter / Space, accessible
   * name.
   */
  static class Btn extends Box {
    Runnable action;
    boolean enabled = true;

    Btn(Runnable action, String accessibleName) {
      super(ROW);
      this.action = action;
      setFocusable(true);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext().setAccessibleName(accessibleName);
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
              hover = true;
              repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
              hover = false;
              repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
              if (enabled && Btn.this.action != null && e.getButton() == MouseEvent.BUTTON1) {
                Btn.this.action.run();
              }
            }
          });
      for (String k : new String[] {"ENTER", "SPACE"}) {
        getInputMap().put(KeyStroke.getKeyStroke(k), "press");
      }
      getActionMap()
          .put(
              "press",
              new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                  if (enabled && Btn.this.action != null) {
                    Btn.this.action.run();
                  }
                }
              });
    }

    @Override
    javax.accessibility.AccessibleRole role() {
      return javax.accessibility.AccessibleRole.PUSH_BUTTON;
    }

    Btn disabled(boolean off) {
      enabled = !off;
      setCursor(Cursor.getPredefinedCursor(off ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
      return this;
    }

    @Override
    public void paint(Graphics g) {
      if (!enabled) {
        // .btn[disabled] { opacity: .45 }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(
            java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
        super.paint(g2);
        g2.dispose();
      } else {
        super.paint(g);
      }
    }
  }

  /** .btn: icon + label, 1px line border, radius 8, padding 6px 12px, hover sunken */
  static Btn btn(String icon, String label, Runnable action) {
    Btn b = new Btn(action, label);
    b.gap(6)
        .pad(6, label == null ? 6 : 12, 6, label == null ? 6 : 12)
        .bg(Theme::surface)
        .border(Theme::line)
        .radius(8);
    b.hoverBg = Theme::sunken;
    if (icon != null) {
      b.add(ic(icon, 20, Theme::fg));
    }
    if (label != null) {
      // without an icon the 20px line box (.btn line-height) sets the height
      b.add(txt(label, Theme.sans(13, 500), Theme::fg, icon == null ? 20 : normalLh(13)));
    }
    return b;
  }

  /** .btn.primary: accent fill and surface text; hover mixes 12 % of the text color in */
  static Btn primary(String label, Runnable action) {
    Btn b = new Btn(action, label);
    b.gap(6).pad(6, 12, 6, 12).bg(Theme::accent).border(Theme::accent).radius(8);
    b.hoverBg =
        () -> {
          Color a = Theme.accent(), f = Theme.fg();
          return new Color(
              (int) Math.round(a.getRed() * .88 + f.getRed() * .12),
              (int) Math.round(a.getGreen() * .88 + f.getGreen() * .12),
              (int) Math.round(a.getBlue() * .88 + f.getBlue() * .12));
        };
    b.add(txt(label, Theme.sans(13, 500), Theme::surface, 20));
    return b;
  }

  /** .link: accent text, padding 4px 6px, radius 6, hover accent-soft */
  static Btn link(String label, Runnable action) {
    Btn b = new Btn(action, label);
    b.pad(4, 6, 4, 6).radius(6);
    b.hoverBg = Theme::accentSoft;
    b.add(txt(label, Theme.sans(13, 500), Theme::accent, normalLh(13)));
    return b;
  }

  /** .seg: a segmented control of {key, icon, label}; the current one is accent on accent-soft */
  static Box seg(String[][] items, String current, java.util.function.Consumer<String> on) {
    Box seg = new Box(Box.ROW).border(Theme::line).radius(8);
    seg.clip = true;
    for (int i = 0; i < items.length; i++) {
      String[] it = items[i];
      boolean pressed = it[0].equals(current);
      Supplier<Color> col = pressed ? Theme::accent : Theme::muted;
      Btn b = new Btn(() -> on.accept(it[0]), it[2]);
      b.pad(6, 12, 6, 12).gap(6).bg(pressed ? Theme::accentSoft : Theme::surface);
      b.ruleLeft = i > 0;
      b.with(ic(it[1], 20, col), txt(it[2], Theme.sans(13, 500), col, 20));
      seg.add(b);
    }
    return seg;
  }

  /**
   * .split: the main panel and a side panel of clamp(340px, 30 %, 480px) rounded up to a pixel,
   * 14px apart; equalHeights = stretch both
   */
  static Box split(JComponent main, JComponent side, boolean equalHeights) {
    Box b =
        new Box(Box.ROW) {
          double side(int w) {
            return Math.ceil(Math.max(340, Math.min(480, w * 0.30)));
          }

          @Override
          public void doLayout() {
            int w = getWidth(),
                mw = (int) Math.round(w - 14 - side(w)),
                sx = (int) Math.round(w - side(w));
            main.setBounds(0, 0, mw, equalHeights ? getHeight() : prefH(main, mw));
            side.setBounds(sx, 0, w - sx, equalHeights ? getHeight() : prefH(side, w - sx));
          }

          @Override
          int heightFor(int w) {
            int mw = (int) Math.round(w - 14 - side(w)), sx = (int) Math.round(w - side(w));
            return Math.max(prefH(main, mw), prefH(side, w - sx));
          }
        };
    return b.with(main, side);
  }

  /** CSS line-height: normal (ascent + descent of the font) */
  static double normalLh(double size) {
    Font f = Theme.sans(size, 400);
    LineMetrics m = f.getLineMetrics("Hg", Theme.FRC);
    return Math.round(m.getAscent()) + Math.round(m.getDescent());
  }

  /** a component that only paints, with a fixed preferred size */
  static JComponent canvas(
      int w, int h, java.util.function.BiConsumer<Graphics2D, JComponent> paint) {
    JComponent c =
        new JComponent() {
          @Override
          protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hints((Graphics2D) g0.create());
            paint.accept(g, this);
            g.dispose();
          }
        };
    c.setPreferredSize(new Dimension(w, h));
    return c;
  }

  /**
   * a scrolled column that takes the viewport's width, so its text wraps there (like a block in an
   * overflow: auto box)
   */
  static final class Fit extends Box implements javax.swing.Scrollable {
    Fit() {
      super(COL);
    }

    @Override
    public Dimension getPreferredSize() {
      java.awt.Container vp =
          javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, this);
      int w = vp != null ? vp.getWidth() : super.getPreferredSize().width;
      return new Dimension(w, heightFor(w));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
      return r.height - 24;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  /**
   * a vertical scroll pane with an overlay scroll bar: the content keeps the full width, like the
   * browser
   */
  static JScrollPane scroll(JComponent view) {
    if (!(view instanceof javax.swing.Scrollable)) {
      view = new Fit().add(view);
    }
    JScrollPane sp =
        new JScrollPane(
            view,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
          @Override
          public void updateUI() {
            // no border, also after a theme switch (the look and feel would install its own again)
            super.updateUI();
            setBorder(null);
            setViewportBorder(null);
          }
        };
    sp.getViewport().setOpaque(false);
    sp.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
    sp.setOpaque(false);
    sp.getVerticalScrollBar().setUnitIncrement(24);
    sp.setLayout(
        new javax.swing.ScrollPaneLayout() {
          @Override
          public void layoutContainer(java.awt.Container parent) {
            super.layoutContainer(parent);
            java.awt.Rectangle r = parent.getBounds();
            viewport.setBounds(0, 0, r.width, r.height);
            if (vsb != null && vsb.isVisible()) {
              vsb.setBounds(r.width - 10, 0, 10, r.height);
            }
          }
        });
    sp.setComponentZOrder(sp.getVerticalScrollBar(), 0);
    sp.getVerticalScrollBar().setOpaque(false);
    sp.getVerticalScrollBar().putClientProperty("FlatLaf.style", BAR_HIDDEN);
    AUTO_HIDE.run();
    return sp;
  }

  /**
   * overlay scroll bars appear while the pointer moves or the wheel turns over their pane, like the
   * browser's
   */
  private static final String BAR_HIDDEN =
      "track: #0000; thumb: #0000; hoverTrackColor: #0000; hoverThumbColor: #0000";

  private static String barShown() {
    String c = String.format("#%06x80", Theme.muted().getRGB() & 0xffffff);
    return "track: #0000; hoverTrackColor: #0000; thumb: "
        + c
        + "; hoverThumbColor: "
        + c
        + "; pressedThumbColor: "
        + c;
  }

  private static final Runnable AUTO_HIDE =
      new Runnable() {
        boolean installed;

        @Override
        public void run() {
          if (installed) {
            return;
          }
          installed = true;
          java.awt.Toolkit.getDefaultToolkit()
              .addAWTEventListener(
                  e -> {
                    if (!(e.getSource() instanceof Component c)) {
                      return;
                    }
                    for (Component a = c; a != null; a = a.getParent()) {
                      if (a instanceof JScrollPane sp) {
                        show(sp.getVerticalScrollBar());
                      }
                    }
                  },
                  java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
                      | java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        }

        void show(javax.swing.JScrollBar bar) {
          bar.putClientProperty("FlatLaf.style", barShown());
          javax.swing.Timer t = (javax.swing.Timer) bar.getClientProperty("hide");
          if (t == null) {
            t =
                new javax.swing.Timer(
                    1200, ev -> bar.putClientProperty("FlatLaf.style", BAR_HIDDEN));
            t.setRepeats(false);
            bar.putClientProperty("hide", t);
          }
          t.restart();
        }
      };
}
