package jvmeter.gui;

import static jvmeter.gui.Ui.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import jvmeter.core.CallTree;
import jvmeter.core.Fmt;
import jvmeter.core.Session;

/** CPU: hot spots table or call tree, and the selected method's details. */
final class CpuView {

  private CpuView() {}

  static JComponent build(App app) {
    App.State st = app.state;
    Box root = col(0);
    root.add(
        OverviewView.head(
            "CPU",
            seg(
                new String[][] {
                  {"hot", "local_fire_department", "Hot spots"},
                  {"tree", "account_tree", "Call tree"}
                },
                st.cpuTab,
                k -> {
                  st.cpuTab = k;
                  app.render();
                }),
            app.search()));
    root.add(OverviewView.gap(14));
    Box main = panel(Box.COL);
    if (st.cpuTab.equals("hot")) {
      main.add(grow(hotTable(app)));
    } else {
      main.add(treeTools(app));
      main.add(grow(tree(app)));
    }
    Box side = panel(Box.COL).add(grow(scroll(detail(app))));
    root.add(grow(split(main, side, true)));
    return root;
  }

  // ---------- hot spots ----------

  private static JComponent hotTable(App app) {
    Session s = app.s;
    App.State st = app.state;
    String q = st.q.toLowerCase(Locale.ROOT);
    Function<CallTree.HotSpot, Comparable<?>> val =
        switch (st.sortKey) {
          case "name" -> h -> h.name;
          case "calls" -> h -> h.calls;
          case "avg" -> h -> h.self / Math.max(1, h.calls);
          default -> h -> h.self;
        };
    List<CallTree.HotSpot> rows = new ArrayList<>();
    for (CallTree.HotSpot h : s.tree.hot) {
      if (q.isEmpty() || h.name.toLowerCase(Locale.ROOT).contains(q)) {
        rows.add(h);
      }
    }
    rows.sort(sorter(val, st.sortDir));
    if (rows.isEmpty()) {
      return empty("No methods match \"" + st.q + "\"");
    }
    double max = s.tree.hot.stream().mapToDouble(h -> h.self).max().orElse(1);
    Table t =
        new Table()
            .col("name", "Method", true)
            .col("self", "Self time", false)
            .col("pct", "Share", false)
            .col("calls", "Calls", false)
            .col("avg", "Average", false);
    t.sortKey = st.sortKey;
    t.asc = st.sortDir.equals("asc");
    t.onSort =
        k -> {
          st.sortDir =
              k.equals(st.sortKey)
                  ? (st.sortDir.equals("asc") ? "desc" : "asc")
                  : k.equals("name") ? "asc" : "desc";
          st.sortKey = k;
          app.render();
        };
    for (CallTree.HotSpot h : rows) {
      t.row(
          h.name,
          Table.method(h.name, st.q),
          Table.meterNum(h.self / max, Fmt.fmtMs(h.self)),
          Table.num(Fmt.fixed(h.self / s.tree.root.total * 100, 1) + " %"),
          Table.num(Fmt.calls(h.calls)),
          Table.num(Fmt.perCall(h.self, h.calls)));
      if (h.name.equals(st.sel)) {
        t.selected = t.rows.size() - 1;
      }
    }
    t.clickable(
        i -> {
          st.sel = rows.get(i).name;
          app.render();
        });
    return scroll(t);
  }

  /** strings lexicographically, numbers by value; dir "asc" | "desc" */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static <T> Comparator<T> sorter(Function<T, Comparable<?>> val, String dir) {
    java.text.Collator coll = java.text.Collator.getInstance(Locale.ENGLISH);
    Comparator<T> c =
        (a, b) -> {
          Comparable x = val.apply(a), y = val.apply(b);
          return x instanceof String xs ? coll.compare(xs, y) : x.compareTo(y);
        };
    return dir.equals("asc") ? c : c.reversed();
  }

  /** .empty: muted 13px text, padding 18px 14px */
  static Box empty(String text) {
    return new Box(Box.COL)
        .pad(18, 14, 18, 14)
        .add(txt(text, Theme.sans(13, 400), Theme::muted, 20).wrap());
  }

  // ---------- call tree ----------

  private static Box treeTools(App app) {
    App.State st = app.state;
    Btn hot =
        btn(
            "route",
            "Expand hot path",
            () -> {
              st.expanded = new HashSet<>();
              st.expanded.add(app.s.tree.root.id);
              st.sel = CallTree.expandHotPath(app.s.tree.root, st.expanded, 99).name;
              app.render();
            });
    hot.setToolTipText("Expand the most expensive path");
    Btn all =
        btn(
            "unfold_more",
            null,
            () -> {
              CallTree.walk(app.s.tree.root, n -> st.expanded.add(n.id));
              app.render();
            });
    all.setToolTipText("Expand all");
    all.getAccessibleContext().setAccessibleName("Expand all");
    Btn none =
        btn(
            "unfold_less",
            null,
            () -> {
              st.expanded = new HashSet<>();
              st.expanded.add(app.s.tree.root.id);
              app.render();
            });
    none.setToolTipText("Collapse all");
    none.getAccessibleContext().setAccessibleName("Collapse all");
    return new Box(Box.ROW).pad(10, 10, 4, 10).gap(8).with(hot, all, none);
  }

  private static JComponent tree(App app) {
    Session s = app.s;
    App.State st = app.state;
    String q = st.q.toLowerCase(Locale.ROOT);
    Set<Integer> visible = null;
    if (!q.isEmpty()) {
      Set<Integer> v = new HashSet<>();
      CallTree.walk(
          s.tree.root,
          n -> {
            if (n.name.toLowerCase(Locale.ROOT).contains(q)) {
              for (CallTree.Node p = n; p != null; p = p.parent) {
                v.add(p.id);
              }
            }
          });
      if (v.isEmpty()) {
        return empty("No methods match \"" + st.q + "\"");
      }
      visible = v;
    }
    Function<CallTree.Node, Comparable<?>> val =
        switch (st.tsortKey) {
          case "name" -> n -> n.name;
          case "self" -> n -> n.self;
          case "calls" -> n -> n.calls;
          default -> n -> n.total;
        };
    List<CallTree.Node> out = new ArrayList<>();
    collect(s.tree.root, visible, st.expanded, sorter(val, st.tsortDir), out);
    TreeRows rows = new TreeRows(app, out, visible != null);
    return new Box(Box.COL).with(rows.head(), grow(scroll(rows)));
  }

  private static void collect(
      CallTree.Node n,
      Set<Integer> visible,
      Set<Integer> expanded,
      Comparator<CallTree.Node> sort,
      List<CallTree.Node> out) {
    if (visible != null && !visible.contains(n.id)) {
      return;
    }
    out.add(n);
    if (!n.children.isEmpty() && (expanded.contains(n.id) || visible != null)) {
      List<CallTree.Node> ch = new ArrayList<>(n.children);
      ch.sort(sort);
      for (CallTree.Node c : ch) {
        collect(c, visible, expanded, sort, out);
      }
    }
  }

  /**
   * the tree rows (.trow: 30px; the name column, then Total / Self / Calls fitted to their content)
   */
  static final class TreeRows extends JComponent implements Scrollable {
    static final Font NUM = Theme.num(Theme.sans(13, 400)),
        PCT = Theme.num(Theme.sans(12, 400)),
        HEAD = Theme.sans(12, 400);
    static final String[][] COLS = {
      {"name", "Method"}, {"total", "Total"}, {"self", "Self"}, {"calls", "Calls"}
    };
    final App app;
    final List<CallTree.Node> nodes;

    /** searching: every visible node is open */
    final boolean all;

    int hover = -1;

    TreeRows(App app, List<CallTree.Node> nodes, boolean all) {
      this.app = app;
      this.nodes = nodes;
      this.all = all;
      setFocusable(true);
      setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      MouseAdapter m =
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              int r = rowAt(e.getY());
              if (r < 0 || e.getButton() != MouseEvent.BUTTON1) {
                return;
              }
              CallTree.Node n = nodes.get(r);
              double tx = n.depth * 18 + 6;
              if (e.getX() >= tx && e.getX() < tx + 22) {
                if (!app.state.expanded.remove(n.id)) {
                  app.state.expanded.add(n.id);
                }
              } else {
                app.state.sel = n.parent != null ? n.name : null;
              }
              app.render();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
              int r = rowAt(e.getY());
              if (r != hover) {
                hover = r;
                repaint();
              }
            }

            @Override
            public void mouseExited(MouseEvent e) {
              hover = -1;
              repaint();
            }
          };
      addMouseListener(m);
      addMouseMotionListener(m);
    }

    int rowAt(int y) {
      int r = (y - 6) / 30;
      return y >= 6 && r < nodes.size() ? r : -1;
    }

    static String[] cells(CallTree.Node n) {
      return new String[] {
        Fmt.fmtMs(n.total),
        n.parent != null ? Fmt.fmtMs(n.self) : "",
        n.parent != null ? Fmt.calls(n.calls) : ""
      };
    }

    /**
     * widths of Total / Self / Calls: the widest content (header button included) + 6, at least 40
     */
    double[] colw() {
      double[] w = new double[3];
      for (int k = 0; k < 3; k++) {
        boolean on = COLS[k + 1][0].equals(app.state.tsortKey);
        w[k] = Math.max(40, Math.ceil(width(HEAD, COLS[k + 1][1]) + (on ? 18 : 0) + 6));
      }
      for (CallTree.Node n : nodes) {
        String[] c = cells(n);
        for (int k = 0; k < 3; k++) {
          if (!c[k].isEmpty()) {
            w[k] = Math.max(w[k], Math.ceil(width(NUM, c[k]) + 6));
          }
        }
      }
      return w;
    }

    /** .thead: padding 6px 10px 6px 14px, 12px/18px, sort buttons */
    JComponent head() {
      JComponent h =
          new JComponent() {
            @Override
            protected void paintComponent(Graphics g0) {
              Graphics2D g = Theme.hints((Graphics2D) g0.create());
              double[] w = colw();
              g.setColor(Theme.line());
              g.fillRect(0, getHeight() - 1, getWidth(), 1);
              double x = getWidth() - 10 - w[0] - w[1] - w[2];
              for (int k = 0; k < 4; k++) {
                boolean on = COLS[k][0].equals(app.state.tsortKey);
                double tw = width(HEAD, COLS[k][1]) + (on ? 18 : 0), bx = 14;
                if (k > 0) {
                  x += w[k - 1];
                  bx = x - tw;
                }
                Color c = on ? Theme.fg() : Theme.muted();
                double t = text(g, COLS[k][1], HEAD, c, bx, 6, 18);
                if (on) {
                  Graphics2D ic = (Graphics2D) g.create();
                  if (app.state.tsortDir.equals("asc")) {
                    ic.rotate(Math.PI, bx + t + 10, 15);
                  }
                  Icons.paint(ic, "expand_more", false, bx + t + 2, 7, 16, c);
                  ic.dispose();
                }
              }
              g.dispose();
            }
          };
      h.setPreferredSize(new Dimension(0, 31));
      h.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      h.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              double[] w = colw();
              double x = h.getWidth() - 10 - w[0] - w[1] - w[2];
              String k =
                  e.getX() < x
                      ? "name"
                      : e.getX() < x + w[0]
                          ? "total"
                          : e.getX() < x + w[0] + w[1] ? "self" : "calls";
              App.State st = app.state;
              st.tsortDir =
                  k.equals(st.tsortKey)
                      ? (st.tsortDir.equals("asc") ? "desc" : "asc")
                      : k.equals("name") ? "asc" : "desc";
              st.tsortKey = k;
              app.render();
            }
          });
      return h;
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(560, 12 + nodes.size() * 30);
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = Theme.hints((Graphics2D) g0.create());
      double rootTotal = app.s.tree.root.total;
      double[] w = colw();
      int width = getWidth();
      double nameW = width - 10 - w[0] - w[1] - w[2];
      Rectangle vis = getVisibleRect();
      for (int r = 0; r < nodes.size(); r++) {
        CallTree.Node n = nodes.get(r);
        int y = 6 + r * 30;
        if (y > vis.y + vis.height || y + 30 < vis.y) {
          continue;
        }
        boolean sel = n.name.equals(app.state.sel);
        if (sel || r == hover) {
          g.setColor(sel ? Theme.accentSoft() : Theme.sunken());
          g.fillRect(0, y, width, 30);
        }
        Graphics2D c = (Graphics2D) g.create();
        c.clip(new Rectangle2D.Double(0, y, nameW, 30));
        double x = n.depth * 18 + 6;
        if (!n.children.isEmpty()) {
          boolean open = app.state.expanded.contains(n.id) || all;
          Graphics2D ic = (Graphics2D) c.create();
          if (!open) {
            ic.rotate(-Math.PI / 2, x + 16, y + 15);
          }
          Icons.paint(ic, "expand_more", false, x + 6, y + 5, 20, Theme.muted());
          ic.dispose();
        }
        x += 26;
        Table.meter(c, x, y + 11, 64, 8, n.total / rootTotal, n.self / rootTotal);
        x += 76;
        text(c, Fmt.fixed(n.total / rootTotal * 100, 1) + "%", PCT, Theme.muted(), x, y + 5, 20);
        x += 56;
        if (n.parent != null) {
          method(c, n.name, app.state.q, x, y + 7, nameW - x, Theme.fg());
        } else {
          text(c, n.name, Theme.sans(13, 700), Theme.fg(), x, y + 5, 20);
        }
        c.dispose();
        String[] cells = cells(n);
        double cx = nameW;
        for (int k = 0; k < 3; k++) {
          cx += w[k];
          if (!cells[k].isEmpty()) {
            text(g, cells[k], NUM, Theme.fg(), cx - width(NUM, cells[k]), y + 5, 20);
          }
        }
      }
      g.dispose();
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
      return 30;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
      return Math.max(30, r.height - 30);
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

  // ---------- details of the selected method ----------

  private record Part(String label, String name, double ms, Supplier<Color> color) {}

  private static Box detail(App app) {
    App.State st = app.state;
    CallTree.MethodInfo m = st.sel != null ? app.s.tree.methodInfo(st.sel) : null;
    Box d = new Box(Box.COL).pad(0, 0, 12, 0);
    if (m == null) {
      Box e = new Box(Box.COL).pad(18, 14, 18, 14);
      e.add(txt("Select a method", Theme.sans(13, 700), Theme::muted, 20));
      e.add(
          txt(
                  "to see where its time goes and where it is called from.",
                  Theme.sans(13, 400),
                  Theme::muted,
                  20)
              .wrap());
      return d.add(e);
    }
    // the prototype shows the package without its last part
    int i = Fmt.packageEnd(m.name());
    String pkg = i > 0 ? m.name().substring(0, i - 1) : "";
    Box head = new Box(Box.COL).pad(14, 14, 4, 14);
    head.add(txt(Fmt.shortName(m.name()), Theme.sans(13, 700), Theme::fg, 20).wrap());
    head.add(
        txt(
                pkg.contains(".") ? pkg.substring(0, pkg.lastIndexOf('.')) : "",
                Theme.sans(12, 400),
                Theme::muted,
                17)
            .wrap());
    d.add(head);
    d.add(
        stats(
            new String[][] {
              {"Self", Fmt.fmtMs(m.self())},
              {"Total", Fmt.fmtMs(m.total())},
              {"Calls", Fmt.calls(m.calls())},
              {"Per call", Fmt.perCall(m.total(), m.calls())}
            }));
    d.add(h3("Time breakdown"));
    List<Supplier<Color>> colors =
        List.of(Theme::barSelf, Theme::accent, Theme::io, Theme::waitC, Theme::muted);
    List<Part> parts = new ArrayList<>();
    parts.add(new Part("Self", null, m.self(), colors.get(0)));
    for (int k = 0; k < Math.min(3, m.callees().size()); k++) {
      CallTree.Entry c = m.callees().get(k);
      parts.add(new Part(Fmt.shortName(c.name()), c.name(), c.ms(), colors.get(k + 1)));
    }
    double rest = m.total() - parts.stream().mapToDouble(Part::ms).sum();
    if (rest > m.total() * 0.005) {
      parts.add(
          new Part((m.callees().size() - 3) + " others", null, rest, colors.get(parts.size())));
    }
    if (m.total() > 0) {
      d.add(
          new Box(Box.COL)
              .pad(0, 14, 8, 14)
              .add(
                  canvas(
                      0,
                      10,
                      (g, c) -> {
                        RoundRectangle2D.Double track =
                            new RoundRectangle2D.Double(0, 0, c.getWidth(), 10, 6, 6);
                        g.setColor(Theme.sunken());
                        g.fill(track);
                        g.clip(track);
                        double x = 0;
                        for (Part p : parts) {
                          double w = Math.round(p.ms / m.total() * 10000) / 10000.0 * c.getWidth();
                          g.setColor(p.color.get());
                          g.fill(new Rectangle2D.Double(x, 0, w, 10));
                          x += w;
                        }
                      })));
      Box list = new Box(Box.COL).pad(0, 8, 0, 8).gap(1);
      for (Part p : parts) {
        list.add(listRow(app, p.color, p.label, p.ms, m.total(), p.name, -1));
      }
      d.add(list);
    } else {
      d.add(
          new Box(Box.COL)
              .pad(0, 14, 0, 14)
              .add(txt("No samples", Theme.sans(13, 400), Theme::muted, 20)));
    }
    d.add(h3("Callers"));
    Box callers = new Box(Box.COL).pad(0, 8, 0, 8).gap(1);
    double maxCaller = Math.max(1, m.callers().stream().mapToDouble(c -> c.ms()).max().orElse(1));
    for (CallTree.Entry c : m.callers().subList(0, Math.min(5, m.callers().size()))) {
      boolean root = c.name().startsWith("(");
      callers.add(
          listRow(
              app,
              null,
              root ? c.name() : Fmt.shortName(c.name()),
              c.ms(),
              m.total(),
              root ? null : c.name(),
              c.ms() / maxCaller));
    }
    if (m.callers().size() > 5) {
      callers.add(
          new Box(Box.COL)
              .pad(5, 6, 5, 6)
              .add(txt((m.callers().size() - 5) + " more", Theme.sans(12, 400), Theme::muted, 18)));
    }
    d.add(callers);
    Btn show =
        btn(
            "account_tree",
            "Show in call tree",
            () -> {
              CallTree.Node n = firstNode(app.s.tree.root, m.name());
              for (CallTree.Node p = n != null ? n.parent : null; p != null; p = p.parent) {
                st.expanded.add(p.id);
              }
              st.cpuTab = "tree";
              app.render();
            });
    d.add(new Box(Box.ROW).pad(12, 14, 0, 14).add(show));
    return d;
  }

  private static CallTree.Node firstNode(CallTree.Node n, String name) {
    if (n.parent != null && n.name.equals(name)) {
      return n;
    }
    for (CallTree.Node c : n.children) {
      CallTree.Node f = firstNode(c, name);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  /** .side h3: 12px muted, margin 14px 14px 6px */
  static Box h3(String t) {
    return new Box(Box.COL).pad(14, 14, 6, 14).add(txt(t, Theme.sans(12, 500), Theme::muted, 18));
  }

  /**
   * .d-stats: 2 columns of label | value with 1px lines between, radius 8; label and value share a
   * baseline
   */
  private static Box stats(String[][] items) {
    Font lf = Theme.sans(12, 400), vf = Theme.num(Theme.sans(14, 700));
    int rows = (items.length + 1) / 2;
    JComponent grid =
        canvas(
            0,
            2 + 31 * rows - 1,
            (g, c) -> {
              int w = c.getWidth(), h = c.getHeight();
              g.setColor(Theme.line());
              g.fill(new RoundRectangle2D.Double(0, 0, w, h, 16, 16));
              g.clip(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 14, 14));
              double cw = (w - 3) / 2.0;
              for (int k = 0; k < items.length; k++) {
                double x = 1 + (k % 2) * (cw + 1);
                int y = 1 + (k / 2) * 31;
                g.setColor(Theme.surface());
                g.fill(new Rectangle2D.Double(x, y, cw, 30));
                double base = y + 5 + baseline(vf, 20);
                text(g, items[k][0], lf, Theme.muted(), x + 10, base - baseline(lf, 17), 17);
                text(
                    g,
                    items[k][1],
                    vf,
                    Theme.fg(),
                    x + cw - 10 - width(vf, items[k][1]),
                    y + 5,
                    20);
              }
            });
    return new Box(Box.COL).pad(10, 14, 0, 14).add(grid);
  }

  /** a .d-list row: [dot] label (ellipsis) | time | share (42px); callers add a 4px bar below */
  private static Btn listRow(
      App app, Supplier<Color> dot, String label, double ms, double base, String link, double bar) {
    Font f = Theme.sans(13, 400), nf = Theme.num(f);
    String time = Fmt.fmtMs(ms), share = Fmt.fixed(ms / base * 100, 0) + " %";
    Btn r =
        new Btn(
            link == null
                ? null
                : () -> {
                  app.state.sel = link;
                  app.render();
                },
            label);
    r.pad(5, 6, 5, 6).radius(6);
    if (link != null) {
      r.hoverBg = Theme::sunken;
      r.setToolTipText(link);
    } else {
      r.setCursor(java.awt.Cursor.getDefaultCursor());
      r.setFocusable(false);
    }
    r.add(
        grow(
            canvas(
                0,
                bar >= 0 ? 25 : 18,
                (g, c) -> {
                  double w = c.getWidth(), x = 0;
                  if (dot != null) {
                    g.setColor(dot.get());
                    g.fill(new RoundRectangle2D.Double(0, 5, 8, 8, 4, 4));
                    x = 16;
                  }
                  double tx = w - 42 - 8 - width(nf, time);
                  text(g, ellipsize(f, label, tx - 8 - x), f, Theme.fg(), x, 0, 18);
                  text(g, time, nf, Theme.fg(), tx, 0, 18);
                  text(g, share, nf, Theme.muted(), w - width(nf, share), 0, 18);
                  if (bar >= 0) {
                    RoundRectangle2D.Double track = new RoundRectangle2D.Double(0, 21, w, 4, 4, 4);
                    g.setColor(Theme.sunken());
                    g.fill(track);
                    g.clip(track);
                    g.setColor(Theme.alpha(Theme.accent(), 70));
                    g.fill(new Rectangle2D.Double(0, 21, w * Table.pct(bar), 4));
                  }
                })));
    return r;
  }
}
