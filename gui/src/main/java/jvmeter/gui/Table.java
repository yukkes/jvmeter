package jvmeter.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;

/**
 * The prototype's tables: a sticky header with sort buttons (th: 12px, padding 8px 10px) and rows
 * of 29px (td: padding 6px 10px, line-height 16px, 1px rule). The first column takes the remaining
 * width; the others fit their content exactly like the prototype's fitCols(). With auto = true the
 * columns size like an auto-layout table instead.
 */
final class Table extends JComponent implements Scrollable {

  /**
   * a cell's content: its natural width and how to paint it at the content box (x, y = top of the
   * 16px line, w)
   */
  interface Cell {
    double width();

    void paint(Graphics2D g, double x, double y, double w);

    /**
     * true: painted across the whole content box and aligns itself (a meter stays at the left edge)
     */
    default boolean fill() {
      return false;
    }
  }

  record Col(String key, String label, boolean left) {}

  static final Font TH = Theme.sans(12, 500), TD = Theme.sans(13, 400), NUM = Theme.num(TD);
  static final int HEAD = 33, ROW = 29;

  final List<Col> cols = new ArrayList<>();
  final List<Cell[]> rows = new ArrayList<>();
  final List<String> tips = new ArrayList<>();
  String sortKey;
  boolean asc;
  Consumer<String> onSort;
  IntConsumer onClick;
  int selected = -1, hover = -1;

  /** auto table layout (the GC causes table) instead of fixed with fitted numeric columns */
  boolean auto;

  @Override
  public javax.swing.JToolTip createToolTip() {
    return Ui.plain(super.createToolTip());
  }

  Table() {
    setOpaque(false);
    setToolTipText("");
    MouseAdapter m =
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getButton() != MouseEvent.BUTTON1) {
              return;
            }
            int top = getVisibleRect().y;
            if (e.getY() < top + HEAD) {
              int c = colAt(e.getX());
              if (onSort != null && c >= 0 && cols.get(c).key() != null) {
                onSort.accept(cols.get(c).key());
              }
            } else if (onClick != null) {
              int r = (e.getY() - HEAD) / ROW;
              if (r >= 0 && r < rows.size()) {
                onClick.accept(r);
              }
            }
          }

          @Override
          public void mouseMoved(MouseEvent e) {
            int r = e.getY() < getVisibleRect().y + HEAD ? -1 : (e.getY() - HEAD) / ROW;
            if (onClick != null && r != hover) {
              hover = r < rows.size() ? r : -1;
              repaint();
            }
          }

          @Override
          public void mouseExited(MouseEvent e) {
            if (hover >= 0) {
              hover = -1;
              repaint();
            }
          }
        };
    addMouseListener(m);
    addMouseMotionListener(m);
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "down");
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "up");
    getActionMap().put("down", move(1));
    getActionMap().put("up", move(-1));
  }

  private AbstractAction move(int d) {
    return new AbstractAction() {
      @Override
      public void actionPerformed(java.awt.event.ActionEvent e) {
        int r = Math.max(0, Math.min(rows.size() - 1, selected + d));
        if (onClick != null && r != selected && !rows.isEmpty()) {
          onClick.accept(r);
        }
      }
    };
  }

  Table col(String key, String label, boolean left) {
    cols.add(new Col(key, label, left));
    return this;
  }

  Table row(String tip, Cell... cells) {
    rows.add(cells);
    tips.add(tip);
    return this;
  }

  /** a clickable table gets keyboard focus and a hand cursor */
  Table clickable(IntConsumer c) {
    onClick = c;
    setFocusable(true);
    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    return this;
  }

  private double headWidth(int k) {
    Col c = cols.get(k);
    return Ui.width(TH, c.label()) + (c.key() != null && c.key().equals(sortKey) ? 18 : 0);
  }

  /** column x positions [x0, x1, ..., xn] for the table width w */
  double[] xs(double w) {
    int n = cols.size();
    double[] cw = new double[n];
    if (auto) {
      // auto layout: every column gets its content width, the extra width is shared in proportion
      double sum = 0;
      for (int k = 0; k < n; k++) {
        cw[k] = Math.max(Ui.width(TH, cols.get(k).label()), max(k)) + 20;
        sum += cw[k];
      }
      for (int k = 0; k < n; k++) {
        cw[k] += (w - sum) * cw[k] / sum;
      }
    } else {
      double rest = w;
      for (int k = 1; k < n; k++) {
        cw[k] = Math.max(40, Math.ceil(Math.max(headWidth(k), max(k)) + 26));
        rest -= cw[k];
      }
      cw[0] = rest;
    }
    double[] x = new double[n + 1];
    for (int k = 0; k < n; k++) {
      x[k + 1] = x[k] + cw[k];
    }
    return x;
  }

  private double max(int k) {
    double m = 0;
    for (Cell[] r : rows) {
      if (r[k] != null) {
        m = Math.max(m, r[k].width());
      }
    }
    return m;
  }

  private int colAt(int px) {
    double[] x = xs(getWidth());
    for (int k = 0; k < cols.size(); k++) {
      if (px >= x[k] && px < x[k + 1]) {
        return k;
      }
    }
    return -1;
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    int r = (e.getY() - HEAD) / ROW;
    if (e.getY() >= getVisibleRect().y + HEAD
        && r >= 0
        && r < tips.size()
        && colAt(e.getX()) == 0) {
      return tips.get(r);
    }
    int c = colAt(e.getX());
    return e.getY() < getVisibleRect().y + HEAD && c >= 0 && cols.get(c).key() != null
        ? "Sort by " + cols.get(c).label()
        : null;
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(560, HEAD + rows.size() * ROW);
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = Theme.hints((Graphics2D) g0.create());
    double[] x = xs(getWidth());
    int w = getWidth();
    Rectangle vis = getVisibleRect();
    for (int r = 0; r < rows.size(); r++) {
      int y = HEAD + r * ROW;
      if (y > vis.y + vis.height || y + ROW < vis.y) {
        continue;
      }
      Color bg = r == selected ? Theme.accentSoft() : r == hover ? Theme.sunken() : null;
      if (bg != null) {
        g.setColor(bg);
        g.fillRect(0, y, w, ROW);
      }
      g.setColor(Theme.line());
      g.fillRect(0, y + ROW - 1, w, 1);
      Cell[] cells = rows.get(r);
      for (int k = 0; k < cols.size(); k++) {
        if (cells[k] == null) {
          continue;
        }
        double cx = x[k] + 10, cwid = x[k + 1] - x[k] - 20;
        Graphics2D c = (Graphics2D) g.create();
        c.clip(new Rectangle2D.Double(x[k], y, x[k + 1] - x[k], ROW));
        if (cols.get(k).left() || cells[k].fill()) {
          cells[k].paint(c, cx, y + 6, cwid);
        } else {
          double cw = cells[k].width();
          cells[k].paint(c, cx + cwid - cw, y + 6, cw);
        }
        c.dispose();
      }
    }
    // sticky header
    int hy = vis.y;
    g.setColor(Theme.surface());
    g.fillRect(0, hy, w, HEAD);
    g.setColor(Theme.line());
    g.fillRect(0, hy + HEAD - 1, w, 1);
    for (int k = 0; k < cols.size(); k++) {
      Col c = cols.get(k);
      boolean on = c.key() != null && c.key().equals(sortKey);
      double bw = headWidth(k);
      double bx = c.left() ? x[k] + 10 : x[k + 1] - 10 - bw;
      Color col = on ? Theme.fg() : Theme.muted();
      double tw = Ui.text(g, c.label(), TH, col, bx, hy + 8, 16);
      if (on) {
        Graphics2D ic = (Graphics2D) g.create();
        if (asc) {
          ic.rotate(Math.PI, bx + tw + 2 + 8, hy + 8 + 8);
        }
        Icons.paint(ic, "expand_more", false, bx + tw + 2, hy + 8, 16, col);
        ic.dispose();
      }
    }
    g.dispose();
  }

  // ---------- cells ----------

  static Cell text(String s, Font f, Supplier<Color> c) {
    return new Cell() {
      @Override
      public double width() {
        return Ui.width(f, s);
      }

      @Override
      public void paint(Graphics2D g, double x, double y, double w) {
        Ui.text(g, Ui.ellipsize(f, s, w), f, c.get(), x, y, 16);
      }
    };
  }

  static Cell num(String s) {
    return text(s, NUM, Theme::fg);
  }

  static Cell num(String s, Supplier<Color> c) {
    return text(s, NUM, c);
  }

  static Cell method(String name, String q) {
    return new Cell() {
      @Override
      public double width() {
        return Ui.methodWidth(name);
      }

      @Override
      public void paint(Graphics2D g, double x, double y, double w) {
        Ui.method(g, name, q, x, y, w, Theme.fg());
      }
    };
  }

  /**
   * a 64x8 meter at the left edge (4px below the line top) and the number at the right, at least
   * 8px apart
   */
  static Cell meterNum(double frac, String s) {
    return new Cell() {
      @Override
      public double width() {
        return 72 + Ui.width(NUM, s);
      }

      @Override
      public void paint(Graphics2D g, double x, double y, double w) {
        meter(g, x, y + 4, 64, 8, frac, -1);
        Ui.text(g, s, NUM, Theme.fg(), x + w - Ui.width(NUM, s), y, 16);
      }

      @Override
      public boolean fill() {
        return true;
      }
    };
  }

  /** .meter: sunken track, radius 2, bar fill (and optionally the self part in bar-self) */
  static void meter(
      Graphics2D g, double x, double y, double w, double h, double frac, double selfFrac) {
    Graphics2D c = (Graphics2D) g.create();
    java.awt.geom.RoundRectangle2D.Double track =
        new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, 4, 4);
    c.setColor(Theme.sunken());
    c.fill(track);
    c.clip(track);
    c.setColor(Theme.bar());
    c.fill(new Rectangle2D.Double(x, y, w * pct(frac), h));
    if (selfFrac >= 0) {
      c.setColor(Theme.barSelf());
      c.fill(new Rectangle2D.Double(x, y, w * pct(selfFrac), h));
    }
    c.dispose();
  }

  /** the prototype writes bar widths as toFixed(1) percentages */
  static double pct(double f) {
    return Math.round(f * 1000) / 1000.0;
  }

  // ---------- Scrollable: the header stays at the top of the viewport ----------

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
    return ROW;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
    return Math.max(ROW, r.height - HEAD - ROW);
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
