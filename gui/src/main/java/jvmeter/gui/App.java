package jvmeter.gui;

import static jvmeter.gui.Ui.*;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import jvmeter.core.AgentClient;
import jvmeter.core.CallTree;
import jvmeter.core.Connector;
import jvmeter.core.Fmt;
import jvmeter.core.Live;
import jvmeter.core.Session;
import jvmeter.core.Snapshot;
import jvmeter.core.SnapshotWriter;
import jvmeter.core.Targets;

/** The window content: top bar, left rail, and the current view. Mirrors demo/prototype.html. */
final class App extends JComponent {

  /** UI state, the same fields as the prototype's `state` */
  static final class State {
    String view = "overview";
    boolean recording;
    String cpuTab = "hot",
        sortKey = "self",
        sortDir = "desc",
        tsortKey = "total",
        tsortDir = "desc";
    String sel;
    Set<Integer> expanded = new HashSet<>();
    String q = "";
    Session.ClassSnap snapBefore, snapAfter;
    String picking;
    Double hoverT;
    int thread = 1;

    /**
     * Memory opens on GC analysis: whether GC is a problem comes first, Heap & classes is where a
     * leak is hunted down
     */
    String memSort = "bytes", memTab = "gc", copied;
  }

  final State state = new State();

  /**
   * the open session; null until a JVM, a snapshot or the sample is chosen (the window then shows
   * the empty page)
   */
  Session s;

  private final Box top, rail;
  private final JScrollPane scroll;
  private final Page page = new Page();

  /** while picking a reference time: the panel that stays bright above the dimmed window */
  JComponent pickTarget;

  /** catches the mouse while picking: events over the target go to it, a click elsewhere cancels */
  private final JComponent pickGlass = new JComponent() {};

  /** the search field outlives render() so typing keeps focus */
  private final javax.swing.JTextField query =
      new javax.swing.JTextField() {
        @Override
        protected void paintComponent(Graphics g0) {
          super.paintComponent(g0);
          if (getText().isEmpty()) {
            // ::placeholder of the browser
            Ui.text(
                Theme.hints((java.awt.Graphics2D) g0),
                "Search methods",
                getFont(),
                new java.awt.Color(0x757575),
                0,
                0,
                20);
          }
        }
      };

  final Preferences prefs = Preferences.userNodeForPackage(App.class);

  /** runs jvmeter-agent.jar list / attach (tests replace it) */
  Connector.Shell shell = Connector.SYSTEM;

  /**
   * the connection to a JVM's agent, and its hello (a new recording starts from it); null for a
   * snapshot or the sample
   */
  AgentClient live;

  private Live.Message hello;
  private Snapshot.Via liveVia;

  App(Snapshot snapshot) {
    setLayout(null);
    top = new Box(Box.ROW).pad(8, 16, 8, 16).gap(6).bg(Theme::surface);
    top.ruleBottom = true;
    rail = new Box(Box.COL).pad(10, 7, 10, 8).gap(4).bg(Theme::surface);
    rail.ruleRight = true;
    rail.center = false;
    scroll = scroll(page);
    pickGlass.setVisible(false);
    java.awt.event.MouseAdapter ma =
        new java.awt.event.MouseAdapter() {
          void forward(java.awt.event.MouseEvent e) {
            java.awt.Point p = SwingUtilities.convertPoint(pickGlass, e.getPoint(), pickTarget);
            Component d =
                pickTarget != null && pickTarget.contains(p)
                    ? SwingUtilities.getDeepestComponentAt(pickTarget, p.x, p.y)
                    : null;
            pickGlass.setCursor(d != null ? d.getCursor() : java.awt.Cursor.getDefaultCursor());
            if (d != null) {
              d.dispatchEvent(SwingUtilities.convertMouseEvent(pickGlass, e, d));
            } else if (e.getID() == java.awt.event.MouseEvent.MOUSE_CLICKED) {
              stopPicking();
            }
          }

          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            forward(e);
          }

          @Override
          public void mouseMoved(java.awt.event.MouseEvent e) {
            forward(e);
          }
        };
    pickGlass.addMouseListener(ma);
    pickGlass.addMouseMotionListener(ma);
    pickGlass.setToolTipText("Click to cancel");
    add(pickGlass);
    add(top);
    add(rail);
    add(scroll);
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
    getActionMap()
        .put(
            "esc",
            new javax.swing.AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state.picking != null) {
                  stopPicking();
                }
              }
            });
    query.setBorder(javax.swing.BorderFactory.createEmptyBorder());
    query.setOpaque(false);
    query.setFont(Theme.sans(13, 400));
    query.setPreferredSize(new Dimension(180, 20));
    query.getAccessibleContext().setAccessibleName("Search methods");
    query
        .getDocument()
        .addDocumentListener(
            new javax.swing.event.DocumentListener() {
              void changed() {
                state.q = query.getText();
                SwingUtilities.invokeLater(
                    () -> {
                      render();
                      query.requestFocusInWindow();
                    });
              }

              @Override
              public void insertUpdate(javax.swing.event.DocumentEvent e) {
                changed();
              }

              @Override
              public void removeUpdate(javax.swing.event.DocumentEvent e) {
                changed();
              }

              @Override
              public void changedUpdate(javax.swing.event.DocumentEvent e) {}
            });
    if (snapshot != null) {
      load(snapshot);
    } else {
      render();
    }
  }

  /** .search: icon and input in a bordered box */
  Box search() {
    query.setForeground(Theme.fg());
    query.setCaretColor(Theme.fg());
    Box b =
        new Box(Box.ROW).pad(5, 8, 5, 8).gap(6).bg(Theme::surface).border(Theme::line).radius(8);
    return b.with(ic("search", 18, Theme::muted), query);
  }

  // ---------- data ----------

  void load(Snapshot snapshot) {
    disconnect();
    show(new Session(snapshot));
  }

  /** a session in the views, from its start */
  private void show(Session session) {
    s = session;
    state.sel = null;
    state.snapBefore = state.snapAfter = null;
    state.expanded = new HashSet<>();
    state.thread = 0;
    state.expanded.add(s.tree.root.id);
    CallTree.expandHotPath(s.tree.root, state.expanded, 3);
    state.picking = null;
    state.recording = false;
    render();
  }

  void openSnapshot() {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle("Open a snapshot");
    fc.setFileFilter(
        new FileNameExtensionFilter("jvmeter snapshot (.json.gz, .json)", "gz", "json"));
    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    try {
      open(Snapshot.read(fc.getSelectedFile().toPath()));
    } catch (IOException | IllegalArgumentException e) {
      JOptionPane.showMessageDialog(
          this,
          "Could not open the snapshot: " + e.getMessage(),
          "jvmeter",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  /** the session as a snapshot file: gzip-compressed JSON, about and summary first */
  void saveSnapshot() {
    if (s == null) {
      return;
    }
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle("Save the snapshot");
    fc.setSelectedFile(new File("jvmeter-" + s.snap.target.pid + ".json.gz"));
    if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path f = fc.getSelectedFile().toPath();
    if (!f.getFileName().toString().endsWith(".gz")) {
      f = f.resolveSibling(f.getFileName() + ".json.gz");
    }
    if (Files.exists(f)
        && JOptionPane.showConfirmDialog(
                this,
                f.getFileName() + " exists. Replace it?",
                "jvmeter",
                JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION) {
      return;
    }
    try {
      SnapshotWriter.write(s, f);
    } catch (IOException | RuntimeException e) {
      JOptionPane.showMessageDialog(
          this,
          "Could not save the snapshot: " + e.getMessage(),
          "jvmeter",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  /** starts a session on the Overview, closing the Start Center if it is open */
  void open(Snapshot snapshot) {
    load(snapshot);
    onOverview();
  }

  private void onOverview() {
    state.view = "overview";
    render();
    if (startDialog != null) {
      startDialog.dispose();
    }
  }

  // ---------- a live JVM (docs/targets.md) ----------

  /**
   * the agent jar the GUI runs and loads: bundled in its jar (copied to ~/.jvmeter),
   * -Djvmeter.agentJar, or the build's
   */
  static Path agentJar() {
    String p = System.getProperty("jvmeter.agentJar");
    if (p != null) {
      return Path.of(p);
    }
    try (InputStream in = App.class.getResourceAsStream("jvmeter-agent.jar")) {
      if (in != null) {
        return Connector.localJar(in.readAllBytes());
      }
    } catch (IOException e) {
      // the build's jar below
    }
    Path dev = Path.of("agent", "target", Targets.JAR);
    return Files.exists(dev)
        ? dev.toAbsolutePath()
        : Path.of("..", "agent", "target", Targets.JAR).toAbsolutePath();
  }

  /**
   * Attaches to j (or finds its agent running) and connects; the recording starts at once. Runs in
   * the background: the session opens when the agent says hello, and failed gets why it could not
   * connect.
   */
  void connect(
      Connector c,
      Targets.Jvm j,
      Snapshot.Via via,
      String include,
      java.util.function.Consumer<String> failed) {
    Object conn = new Object();
    connecting = conn;
    Thread t =
        new Thread(
            () -> {
              try {
                Connector.Endpoint e = c.attach(j, include);
                AgentClient[] client = new AgentClient[1];
                // the hello can come before the constructor returns: messages carry the connection
                // they belong to.
                // It stays open for the session; closed() closes it
                //noinspection resource
                client[0] =
                    new AgentClient(
                        c.forward(e.port()),
                        e.token(),
                        m ->
                            SwingUtilities.invokeLater(
                                () -> message(conn, client[0], via, include, failed, m)),
                        why ->
                            SwingUtilities.invokeLater(
                                () -> closed(conn, client[0], c, failed, why)));
              } catch (IOException | RuntimeException e) {
                c.close();
                SwingUtilities.invokeLater(
                    () -> {
                      if (connecting == conn) {
                        failed.accept(e.getMessage());
                      }
                    });
              }
            },
            "jvmeter-connect");
    t.setDaemon(true);
    t.start();
  }

  private String liveInclude;

  /** the connection being made (a later one replaces it) */
  private Object connecting;

  /** a message from the agent (on the EDT) */
  void message(
      Object conn,
      AgentClient from,
      Snapshot.Via via,
      String include,
      java.util.function.Consumer<String> failed,
      Live.Message m) {
    if (m.type.equals("error") && conn == connecting) {
      connecting = null;
      failed.accept(m.message); // e.g. another jvmeter is connected
      return;
    }
    if (m.type.equals("hello") && conn == connecting) {
      connecting = null;
      disconnect();
      live = from;
      liveVia = via;
      liveInclude = include;
      hello = m;
      startLive();
      onOverview();
      return;
    }
    if (from != live) {
      from.close(); // an earlier connection, or one given up
      return;
    }
    switch (m.type) {
      case "error" -> error(m.message);
      case "end" -> {
        state.recording = false;
        render();
      }
      default -> {
        if (s != null) {
          Set<String> open = s.tree.paths(state.expanded);
          s.apply(m);
          if (m.type.equals("cpu")) {
            state.expanded = s.tree.ids(open);
            state.expanded.add(s.tree.root.id);
          }
          render();
        }
      }
    }
  }

  /** a new recording: an empty session, and the agent starts recording */
  private void startLive() {
    Session x = Session.live(hello);
    x.snap.target.via = liveVia;
    if (liveInclude != null) {
      x.snap.target.include = liveInclude; // the packages the agent counts from now on
    }
    show(x);
    live.send(new Live.Command("start", liveInclude));
    state.recording = true;
    render();
  }

  private void closed(
      Object conn,
      AgentClient from,
      Connector c,
      java.util.function.Consumer<String> failed,
      String why) {
    c.close();
    if (conn == connecting) {
      connecting = null;
      failed.accept(why != null ? why : "The connection was closed.");
    } else if (from == live) {
      live = null;
      state.recording = false;
      render();
      if (why != null) {
        error(why);
      }
    }
  }

  /** ends the connection to a JVM: its agent stops recording and takes the counters out */
  void disconnect() {
    if (live != null) {
      live.close();
      live = null;
    }
  }

  /** message may come from the profiled JVM: shown as it is, never as HTML */
  private void error(String message) {
    if (!java.awt.GraphicsEnvironment.isHeadless()) {
      javax.swing.JLabel text = new javax.swing.JLabel();
      text.putClientProperty("html.disable", Boolean.TRUE);
      text.setText(message);
      JOptionPane.showMessageDialog(this, text, "jvmeter", JOptionPane.ERROR_MESSAGE);
    }
  }

  private javax.swing.JDialog startDialog;

  /** the Start Center (like JProfiler's): shown at startup and from the target in the top bar */
  void openStart() {
    if (java.awt.GraphicsEnvironment.isHeadless() || startDialog != null) {
      return; // the tests drive StartCenter directly
    }
    startDialog =
        new javax.swing.JDialog(
            SwingUtilities.getWindowAncestor(this),
            "Start Center",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL);
    StartCenter sc = new StartCenter(this, startDialog::dispose);
    startDialog.setContentPane(sc);
    startDialog
        .getRootPane()
        .registerKeyboardAction(
            e -> startDialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    startDialog.pack();
    startDialog.setLocationRelativeTo(this);
    startDialog.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowOpened(java.awt.event.WindowEvent e) {
            sc.focusCurrent();
          }
        });
    startDialog.setVisible(true); // modal: returns when closed
    startDialog = null;
  }

  void toggleRecording() {
    if (live != null) {
      // a JVM: Stop ends the recording (its last data follows), Start begins a new one
      if (state.recording) {
        live.send(new Live.Command("stop", null));
        state.recording = false;
        render();
      } else {
        startLive();
      }
      return;
    }
    openStart(); // a snapshot or the sample: recording needs a JVM
  }

  void toggleTheme() {
    Theme.dark = !Theme.dark;
    prefs.put("theme", Theme.dark ? "dark" : "light");
    applyLaf();
    // render() rebuilds the bars and the view, so only what outlives it needs the new look: drop
    // the rest first
    // (updating the UI of every component of a view takes a few hundred ms)
    top.removeAll();
    rail.removeAll();
    page.removeAll();
    SwingUtilities.updateComponentTreeUI(
        SwingUtilities.getWindowAncestor(this) != null
            ? SwingUtilities.getWindowAncestor(this)
            : this);
    SwingUtilities.updateComponentTreeUI(query);
    render();
  }

  static void applyLaf() {
    FlatLaf laf = Theme.dark ? new FlatDarkLaf() : new FlatLightLaf();
    FlatLaf.setup(laf);
    UIManager.put("ScrollBar.thumbArc", 999);
    UIManager.put("ScrollBar.width", 10);
    UIManager.put("ScrollBar.showButtons", false);
    // Linux: FlatLaf's drop shadow puts every tooltip in a window of its own, which WSLg does not
    // show; without it
    // a tooltip that fits is drawn inside the window
    if (System.getProperty("os.name").startsWith("Linux")) {
      UIManager.put("Popup.dropShadowPainted", false);
    }
  }

  // ---------- navigation ----------

  void go(String view) {
    state.view = view;
    render();
  }

  void stopPicking() {
    pickTarget = null;
    state.picking = null;
    state.hoverT = null;
    render();
  }

  // ---------- rendering: rebuild the bars and the current view from the state, like the
  // prototype's render() ----------

  void render() {
    top.removeAll();
    // .brand and .crumb: the brand and the target read as a breadcrumb
    Box brand =
        new Box(Box.ROW)
            .gap(6)
            .with(
                ic("speed", 22, Theme::accent), txt("jvmeter", Theme.sans(14, 700), Theme::fg, 20));
    Box crumb =
        new Box(Box.ROW).pad(0, 2, 0, 2).add(txt("/", Theme.sans(22, 400), Theme::line, 20));
    // the target: its name with pid and JVM below; it opens the Start Center (the chevron). No JVM
    // yet (.no-jvm): it asks to connect
    Snapshot.Target t = s != null ? s.snap.target : null;
    Btn target = new Btn(this::openStart, "Connect to another JVM (local, SSH or Kubernetes)");
    target.pad(3, 6, 3, 8).gap(10).radius(8);
    target.bg = s == null ? Theme::accentSoft : null;
    target.hoverBg = s == null ? Theme::accentSoft : Theme::sunken;
    target.add(
        canvas(
            8,
            8,
            (g, c) -> {
              g.setColor(s != null ? Theme.good() : Theme.muted());
              g.fillOval(0, 0, 8, 8);
            }));
    Box sub =
        new Box(Box.ROW)
            .gap(4)
            .add(
                txt(
                    t != null ? "pid " + t.pid + " · " + t.jvm : "Local, SSH or Kubernetes",
                    Theme.sans(12, 400),
                    Theme::muted,
                    16));
    if (t != null && t.via != null) {
      sub.with(
          txt("·", Theme.sans(12, 400), Theme::muted, 16),
          ic(t.via.kind.equals("ssh") ? "terminal" : "deployed_code", 15, Theme::muted),
          txt(jvmeter.core.Targets.label(t.via), Theme.sans(12, 400), Theme::muted, 16));
    }
    Box tt = new Box(Box.COL);
    tt.stretch = false;
    tt.with(
        txt(
            t != null ? t.name.substring(t.name.lastIndexOf('.') + 1) : "Connect to a JVM",
            Theme.sans(14, 700),
            s != null ? Theme::fg : Theme::accent,
            18),
        sub);
    target.with(
        tt,
        ic(
            "chevron_right",
            18,
            s == null ? Theme::accent : () -> target.hover ? Theme.fg() : Theme.muted()));
    target.setToolTipText("Connect to another JVM (local, SSH or Kubernetes)");
    top.with(brand, crumb, target);
    if (t != null && Boolean.FALSE.equals(t.debugNonSafepoints)) {
      Box b = badge("Inlined → callers", () -> Theme.alpha(Theme.waitC(), 20), Theme::fg);
      b.setToolTipText(
          "-XX:+DebugNonSafepoints was not set when this JVM started, so time in inlined methods is"
              + " shown in their callers. Start it with -XX:+UnlockDiagnosticVMOptions"
              + " -XX:+DebugNonSafepoints (no agent needed) for exact times, also when attaching"
              + " later");
      top.add(b);
    }
    if (s != null && Boolean.TRUE.equals(s.snap.sample)) {
      // #sample-badge: a status, not a control (an M3 tonal container)
      Box b = new Box(Box.ROW).pad(4, 10, 4, 8).gap(6).bg(Theme::accentSoft).radius(8);
      b.with(
          ic("science", 18, Theme::accent),
          txt("Sample data", Theme.sans(13, 500), Theme::accent, 20));
      top.add(b);
    }
    top.add(grow(new Box(Box.ROW)));
    // .recbtn: one button that shows the time; red tint while recording
    boolean recOn = s != null && state.recording;
    String time = s != null ? Fmt.fmtClock(s.elapsed) : "--:--";
    Btn rec =
        ghost(recOn ? "Stop recording (" + time + ")" : "Start recording", this::toggleRecording);
    rec.pad(6, 11, 6, 11).gap(6);
    Txt clock =
        txt(
            time,
            Theme.num(Theme.sans(13, recOn ? 700 : 500)),
            recOn ? Theme::rec : () -> rec.hover ? Theme.fg() : Theme.muted(),
            20);
    if (recOn) {
      rec.bg = () -> Theme.alpha(Theme.rec(), 12);
      rec.hoverBg = rec.bg;
      rec.with(
          canvas(
              8,
              8,
              (g, c) -> {
                g.setColor(Theme.rec());
                g.fillOval(0, 0, 8, 8);
              }),
          clock,
          icFill("stop", 18, Theme::rec));
    } else {
      rec.with(icFill("fiber_manual_record", 18, Theme::rec), clock);
    }
    rec.setToolTipText(recOn ? "Stop recording" : "Start recording");
    rec.disabled(s == null);
    Box vsep =
        new Box(Box.ROW)
            .pad(0, 6, 0, 6)
            .add(
                canvas(
                    1,
                    20,
                    (g, c) -> {
                      g.setColor(Theme.line());
                      g.fillRect(0, 0, 1, 20);
                    }));
    Btn open = ghostIcon("folder_open", "Open snapshot", this::openSnapshot);
    open.setToolTipText("Open a snapshot (.json.gz)");
    Btn save = ghostIcon("download", "Save snapshot", this::saveSnapshot);
    save.setToolTipText("Save the snapshot (.json.gz)");
    save.disabled(s == null);
    String themeTip = Theme.dark ? "Switch to light mode" : "Switch to dark mode";
    Btn theme = ghostIcon(Theme.dark ? "light_mode" : "dark_mode", themeTip, this::toggleTheme);
    theme.setToolTipText(themeTip);
    top.with(rec, vsep, open, save, theme);

    rail.removeAll();
    String[][] views = {
      {"overview", "monitoring", "Overview"},
      {"cpu", "local_fire_department", "CPU"},
      {"memory", "memory", "Memory"},
      {"threads", "view_timeline", "Threads"}
    };
    for (String[] v : views) {
      boolean on = s != null && state.view.equals(v[0]);
      java.util.function.Supplier<java.awt.Color> col = on ? Theme::accent : Theme::muted;
      Btn b = new Btn(() -> go(v[0]), v[2]);
      b.dir = Box.COL;
      b.pad(8, 0, 8, 0).gap(2).radius(10);
      b.bg = on ? Theme::accentSoft : null;
      b.hoverBg = on ? Theme::accentSoft : Theme::sunken;
      b.with(centered(ic(v[1], 24, col)), centered(txt(v[2], Theme.sans(11, 500), col, 16)));
      if (s == null) {
        b.disabled(true);
        b.hoverBg = null;
      }
      rail.add(b);
    }

    // the scroll positions inside the view (CPU table, GC main column) stay while it is rebuilt,
    // e.g. every second while recording
    String key = state.view + ":" + state.cpuTab + ":" + state.memTab;
    List<java.awt.Point> keep = new ArrayList<>();
    if (key.equals(shownKey)) {
      for (JScrollPane sp : scrollPanes(page, new ArrayList<>())) {
        keep.add(sp.getViewport().getViewPosition());
      }
    }
    shownKey = key;
    page.removeAll();
    pickTarget = null;
    page.fill =
        s != null
            && (state.view.equals("cpu")
                || state.view.equals("memory") && state.memTab.equals("gc"));
    Component content =
        s == null
            ? welcome()
            : switch (state.view) {
              case "cpu" -> CpuView.build(this);
              case "memory" -> MemoryView.build(this);
              case "threads" -> ThreadsView.build(this);
              default -> OverviewView.build(this);
            };
    page.add(content);
    pickGlass.setVisible(pickTarget != null);
    scroll.setVerticalScrollBarPolicy(
        page.fill
            ? JScrollPane.VERTICAL_SCROLLBAR_NEVER
            : JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    revalidate();
    repaint();
    if (!keep.isEmpty()) {
      validate();
      List<JScrollPane> sps = scrollPanes(page, new ArrayList<>());
      for (int i = 0; i < Math.min(keep.size(), sps.size()); i++) {
        sps.get(i).validate();
        sps.get(i).getViewport().setViewPosition(keep.get(i));
      }
    }
  }

  private String shownKey;

  /** .badge: a small pill */
  static Box badge(
      String label,
      java.util.function.Supplier<java.awt.Color> bg,
      java.util.function.Supplier<java.awt.Color> fg) {
    return new Box(Box.ROW)
        .pad(2, 8, 2, 8)
        .bg(bg)
        .radius(999)
        .add(txt(label, Theme.sans(12, 400), fg, 18));
  }

  /**
   * .top .btn.ghost: a 32px header button without a frame, muted, hover sunken and fg (the 1px
   * transparent border is in the padding)
   */
  static Btn ghost(String name, Runnable action) {
    Btn b = new Btn(action, name);
    b.radius(8);
    b.hoverBg = Theme::sunken;
    return b;
  }

  /** .top .btn.icon.ghost: a 32px square icon button */
  static Btn ghostIcon(String icon, String name, Runnable action) {
    Btn b = ghost(name, action);
    b.pad(6, 6, 6, 6);
    b.add(ic(icon, 20, () -> b.hover ? Theme.fg() : Theme.muted()));
    return b;
  }

  /**
   * .welcome: no JVM yet (the Start Center was closed without a choice); the panel (max-width 520 +
   * padding) sits in the middle of 60vh
   */
  private Component welcome() {
    Box p = panel(Box.COL).pad(28, 28, 28, 28).gap(10);
    p.add(centered(ic("monitoring", 40, Theme::accent)));
    p.add(txt("No JVM connected", Theme.sans(18, 700), Theme::fg, 28).align("center"));
    p.add(
        txt(
                "Choose a JVM on this computer, on an SSH host or in a Kubernetes pod in the Start"
                    + " Center, or open a snapshot.",
                Theme.sans(12, 400),
                Theme::muted,
                18)
            .wrap()
            .align("center"));
    Btn openB = btn("folder_open", "Open snapshot", this::openSnapshot);
    Btn sample = btn("science", "Sample data", () -> open(Snapshot.loadSample()));
    Box row =
        new Box(Box.ROW)
            .pad(6, 0, 0, 0)
            .gap(8)
            .with(primary("Start Center", this::openStart), openB, sample);
    p.add(centered(row));
    return new Box(Box.COL) {
      {
        add(p);
      }

      @Override
      public void doLayout() {
        int w = Math.min(576, getWidth()), h = prefH(p, w);
        p.setBounds((getWidth() - w) / 2, Math.max(0, (heightFor(getWidth()) - h) / 2), w, h);
      }

      @Override
      int heightFor(int w) {
        return Math.max(
            prefH(p, Math.min(576, w)), (int) Math.round(App.this.getHeight() * 0.6)); // 60vh
      }
    };
  }

  private static List<JScrollPane> scrollPanes(java.awt.Container c, List<JScrollPane> out) {
    for (Component k : c.getComponents()) {
      if (k instanceof JScrollPane sp) {
        out.add(sp);
      }
      if (k instanceof java.awt.Container ck) {
        scrollPanes(ck, out);
      }
    }
    return out;
  }

  /** a component centered horizontally inside a full-width row */
  static Box centered(JComponent c) {
    return new Box(Box.ROW) {
      {
        add(c);
      }

      @Override
      public void doLayout() {
        Dimension p = c.getPreferredSize();
        c.setBounds((getWidth() - p.width) / 2, 0, p.width, getHeight());
      }

      @Override
      public Dimension getPreferredSize() {
        return c.getPreferredSize();
      }

      @Override
      int heightFor(int w) {
        return c.getPreferredSize().height;
      }
    };
  }

  @Override
  public boolean isOptimizedDrawingEnabled() {
    return false; // the picking glass overlaps the other children
  }

  void setQuery(String q) {
    query.setText(q);
  }

  @Override
  protected void paintChildren(Graphics g0) {
    super.paintChildren(g0);
    if (pickTarget == null || !pickTarget.isShowing()) {
      return;
    }
    // .pick-overlay dims the window; .pick-target stays above it with a red ring and a shadow
    java.awt.Graphics2D g = Theme.hints((java.awt.Graphics2D) g0.create());
    g.setColor(new java.awt.Color(15, 20, 28, 115));
    g.fillRect(0, 0, getWidth(), getHeight());
    java.awt.Rectangle r =
        SwingUtilities.convertRectangle(pickTarget.getParent(), pickTarget.getBounds(), this);
    java.awt.Shape clip = scroll.getBounds();
    g.clip(clip);
    for (int i = 16; i > 0; i -= 2) {
      g.setColor(new java.awt.Color(0, 0, 0, 4));
      g.fill(
          new java.awt.geom.RoundRectangle2D.Double(
              r.x - i, r.y + 12 - i, r.width + 2 * i, r.height + 2 * i, 20 + 2 * i, 20 + 2 * i));
    }
    g.setColor(Theme.bad());
    g.fill(
        new java.awt.geom.RoundRectangle2D.Double(
            r.x - 2, r.y - 2, r.width + 4, r.height + 4, 24, 24));
    java.awt.Graphics2D t = (java.awt.Graphics2D) g.create(r.x, r.y, r.width, r.height);
    pickTarget.paint(t);
    t.dispose();
    g.dispose();
  }

  @Override
  public void doLayout() {
    int w = getWidth(), h = getHeight();
    pickGlass.setBounds(0, 0, w, h);
    int th = top.heightFor(w);
    top.setBounds(0, 0, w, th);
    rail.setBounds(0, th, 72, h - th);
    scroll.setBounds(72, th, w - 72, h - th);
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(Theme.bg());
    g.fillRect(0, 0, getWidth(), getHeight());
  }

  /** main: padding 18px 20px 28px; tracks the viewport width; the CPU view also fills its height */
  static final class Page extends Box implements Scrollable {
    /**
     * wide windows: the content stops at 1600px and stays centered (prototype.html: main > * {
     * max-width: 1600px })
     */
    static final int MAX_W = 1600;

    boolean fill;

    Page() {
      super(COL);
      pad(18, 20, 28, 20);
    }

    /** the content width: the space inside the padding, at most MAX_W */
    int contentWidth(int w) {
      java.awt.Insets in = getInsets();
      return Math.min(MAX_W, w - in.left - in.right);
    }

    @Override
    public void doLayout() {
      Component c = getComponentCount() > 0 ? getComponent(0) : null;
      if (c != null) {
        java.awt.Insets in = getInsets();
        int w = contentWidth(getWidth());
        // centered like margin-inline: auto; the browser snaps the fractional left edge to a pixel
        double x = in.left + (getWidth() - in.left - in.right - w) / 2.0;
        int h = fill ? getHeight() - in.top - 20 : prefH(c, w);
        c.setBounds((int) Math.round(x), in.top, w, h);
        setOx(c, x - Math.round(x));
      }
    }

    @Override
    int heightFor(int w) {
      Component c = getComponentCount() > 0 ? getComponent(0) : null;
      java.awt.Insets in = getInsets();
      return c == null ? 0 : prefH(c, contentWidth(w)) + in.top + in.bottom;
    }

    @Override
    public Dimension getPreferredSize() {
      JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
      int w = vp != null ? vp.getWidth() : 1368;
      return new Dimension(w, fill && vp != null ? vp.getHeight() : heightFor(w));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
      return r.height - 24;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return fill;
    }
  }
}
