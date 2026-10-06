package jvmeter.gui;

import static jvmeter.gui.OverviewView.gap;
import static jvmeter.gui.Ui.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JComponent;
import jvmeter.core.Findings;
import jvmeter.core.Fmt;
import jvmeter.core.Seg;
import jvmeter.core.Session;
import jvmeter.core.Snapshot;
import jvmeter.core.ThreadTrack;

/**
 * Threads: a timeline of each thread's states, where threads waited, and the selected thread's time
 * by state and stack.
 */
final class ThreadsView {

  private ThreadsView() {}

  static final String[][] STATES = {
    {"run", "Running"}, {"wait", "Waiting"}, {"block", "Blocked (lock)"}, {"io", "Network I/O"}
  };

  static Supplier<Color> color(String state) {
    return switch (state) {
      case "run" -> Theme::run;
      case "wait" -> Theme::waitC;
      case "block" -> Theme::block;
      default -> Theme::io;
    };
  }

  static JComponent build(App app) {
    Session s = app.s;
    App.State st = app.state;
    Box legend = new Box(Box.ROW).gap(16);
    for (String[] k : STATES) {
      legend.add(
          row(
              0,
              canvas(
                  16,
                  18,
                  (g, c) -> {
                    g.setColor(color(k[0]).get());
                    g.fill(new RoundRectangle2D.Double(0, 4, 10, 10, 4, 4));
                  }),
              txt(k[1], Theme.sans(12, 400), Theme::muted, 18)));
    }
    Box root = col(0);
    root.add(OverviewView.head("Threads", legend));
    root.add(OverviewView.gap(14));

    double sec = s.elapsed;
    Box tl = new Box(Box.COL).pad(8, 14, 14, 14);
    Font f12 = Theme.sans(12, 400);
    for (int i = 0; i < s.threads.size(); i++) {
      ThreadTrack th = s.threads.get(i);
      int idx = i;
      Btn name =
          new Btn(
              () -> {
                st.thread = idx;
                app.render();
              },
              th.name);
      name.pad(4, 6, 4, 6).radius(6);
      name.bg = i == st.thread ? Theme::accentSoft : null;
      name.setToolTipText(th.name);
      name.add(grow(txt(th.name, f12, Theme::fg, 16).ellipsis()));
      name.setPreferredSize(new java.awt.Dimension(210, 24));
      JComponent bar =
          canvas(
              0,
              14,
              (g, c) -> {
                RoundRectangle2D.Double track =
                    new RoundRectangle2D.Double(0, 0, c.getWidth(), 14, 6, 6);
                g.setColor(Theme.sunken());
                g.fill(track);
                g.clip(track);
                for (Seg sg : th.segs) {
                  g.setColor(color(sg.state).get());
                  g.fill(
                      new Rectangle2D.Double(
                          sg.start / sec * c.getWidth(),
                          0,
                          (sg.end - sg.start) / sec * c.getWidth(),
                          14));
                }
              });
      bar.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      bar.addMouseListener(
          new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
              st.thread = idx;
              app.render();
            }
          });
      double b = th.sum("block");
      JComponent note =
          b > 0 ? txt(Fmt.num(b) + "s", Theme.num(f12), Theme::bad, 18).align("right") : gap(0);
      tl.add(tlRow(name, bar, note));
    }
    Box axis = new Box(Box.ROW);
    axis.between = true;
    Font af = Theme.sans(11, 400);
    for (int k = 0; k <= 4; k++) {
      Txt t = txt(Fmt.fmtClock(Math.round(sec * k / 4.0)), af, Theme::muted, 16);
      axis.add(t);
    }
    tl.add(tlRow(gap(0), axis, gap(0)));
    Box left = col(14, panel(Box.COL).add(tl), waits(app));

    Box side = panel(Box.COL).pad(0, 0, 12, 0);
    ThreadTrack t =
        st.thread >= 0 && st.thread < s.threads.size() ? s.threads.get(st.thread) : null;
    if (t == null) {
      side.add(CpuView.empty("Select a thread."));
    } else {
      Box h = new Box(Box.ROW).pad(12, 14, 0, 14).gap(6);
      h.add(ic("view_timeline", 20, Theme::fg));
      h.add(grow(txt(t.name, Theme.sans(13, 700), Theme::fg, 20).wrap()));
      side.add(h);
      side.add(CpuView.h3("Time by state"));
      Box kv = new Box(Box.COL).pad(7, 14, 0, 14).gap(4);
      String[][] rows = {
        {"Running", "run"}, {"Waiting", "wait"}, {"Blocked", "block"}, {"Network I/O", "io"}
      };
      double dtw = 0;
      for (String[] r : rows) {
        dtw = Math.max(dtw, width(Theme.sans(13, 400), r[0]));
      }
      for (String[] r : rows) {
        double v = t.sum(r[1]);
        Txt dt = txt(r[0], Theme.sans(13, 400), Theme::muted, 20);
        dt.setPreferredSize(new java.awt.Dimension((int) Math.ceil(dtw), 20));
        Supplier<Color> c = r[1].equals("block") && v > 0 ? Theme::bad : Theme::fg;
        kv.add(
            new Box(Box.ROW)
                .gap(14)
                .with(
                    dt,
                    grow(
                        txt(Fmt.num(v) + " s", Theme.num(Theme.sans(13, 400)), c, 20)
                            .align("right"))));
      }
      side.add(kv);
      side.add(
          new Box(Box.COL)
              .pad(14, 14, 6, 14)
              .add(txt("Current stack", Theme.sans(12, 500), Theme::muted, 18)));
      Box stack = new Box(Box.COL).pad(0, 14, 0, 14).gap(2);
      for (int i = 0; i < t.stack.size(); i++) {
        Txt f = txt((i > 0 ? "at " : "") + t.stack.get(i), f12, Theme::fg, 18).wrap();
        f.anywhere = true;
        stack.add(f);
      }
      side.add(stack);
    }
    root.add(split(left, side, false));
    return root;
  }

  /** where threads waited (snapshot waits): a row selects the thread that waited there most */
  private static Box waits(App app) {
    Session s = app.s;
    List<Snapshot.Wait> waits = new ArrayList<>(s.snap.waits == null ? List.of() : s.snap.waits);
    waits.sort((a, b) -> Double.compare(b.sec, a.sec));
    Box p =
        panel(Box.COL)
            .with(
                OverviewView.h2(
                    "Where threads waited",
                    OverviewView.note("thread samples every 100 ms · " + waits.size())));
    if (waits.isEmpty()) {
      return p.with(
          CpuView.empty(
              s.snap.waits != null
                  ? "No thread waited for a lock or for I/O"
                  : "This snapshot has no waits: newer agents record where threads wait"));
    }
    Table t =
        new Table()
            .col(null, "Where", true)
            .col(null, "Waited for", true)
            .col(null, "Held by", true)
            .col(null, "Threads", false)
            .col(null, "Total", false);
    String selected =
        app.state.thread >= 0 && app.state.thread < s.threads.size()
            ? s.threads.get(app.state.thread).name
            : null;
    for (Snapshot.Wait w : waits) {
      String what =
          w.state.equals("block")
              ? (w.lock != null ? Findings.shortLock(w.lock) : "a lock")
              : "I/O in " + Fmt.shortName(w.top);
      t.row(
          String.join(", ", w.threads),
          Table.method(w.site, null),
          Table.text(what, Table.TD, Theme::fg),
          Table.text(w.owner != null ? w.owner : "—", Table.TD, Theme::fg),
          Table.num(String.valueOf(w.threads.size())),
          Table.num(Findings.sec(w.sec)));
      if (t.selected < 0 && w.threads.get(0).equals(selected)) {
        t.selected = t.rows.size() - 1;
      }
    }
    t.onClick =
        r -> {
          for (int i = 0; i < s.threads.size(); i++) {
            if (s.threads.get(i).name.equals(waits.get(r).threads.get(0))) {
              app.state.thread = i;
            }
          }
          app.render();
        };
    return p.with(t);
  }

  /** .tl-row: name 210px | bar | note 70px, gap 12, min-height 30, centered */
  private static Box tlRow(JComponent name, JComponent bar, JComponent note) {
    Box r = new Box(Box.ROW).gap(12);
    r.minHeight = 30;
    note.setPreferredSize(new java.awt.Dimension(70, 18));
    name.setPreferredSize(new java.awt.Dimension(210, name.getPreferredSize().height));
    return r.with(name, grow(bar), note);
  }
}
