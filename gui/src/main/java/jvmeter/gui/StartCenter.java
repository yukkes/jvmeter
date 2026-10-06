package jvmeter.gui;

import static jvmeter.gui.Ui.*;

import io.avaje.jsonb.Json;
import io.avaje.jsonb.Jsonb;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import jvmeter.core.Connector;
import jvmeter.core.Snapshot;
import jvmeter.core.Targets;

/**
 * The Start Center of demo/prototype.html: where to start (local, SSH, Kubernetes, a snapshot, the
 * sample) on the left, the JVMs there and the commands jvmeter runs on the right (docs/targets.md),
 * run with Connector.
 */
final class StartCenter extends JComponent {

  static final io.avaje.jsonb.JsonType<Last> LAST = Jsonb.builder().build().type(Last.class);

  static final String[][] KINDS = {
    {"local", "computer", "Local"},
    {"ssh", "terminal", "SSH"},
    {"kubectl", "deployed_code", "Kubernetes"}
  };
  static final String NOTE_LOCAL =
      "JVMs on this computer that run as your user. jvmeter loads the agent with the Attach API, "
          + "or connects to an agent that is already running.";
  static final String NOTE_SSH =
      "Uses your own ssh, so keys, ssh-agent and ~/.ssh/config (ProxyJump too) apply; it cannot"
          + " answer password prompts. The host needs java, and attaching works only as the JVM's"
          + " user: set Run as to use sudo -u.";
  static final String INCLUDE_NOTE =
      "Packages, comma-separated. CPU time is sampled in every method; calls are counted in these.";
  static final String NOTE_KUBECTL =
      "Uses your own kubectl and kubeconfig; RBAC must allow pods/exec and pods/portforward. The"
          + " container needs sh (to receive the agent jar) and the jdk.attach module; otherwise"
          + " start the JVM with the agent.";

  /**
   * the last connection (kept in the preferences): the Start Center opens where it left off and
   * suggests the SSH hosts used before
   */
  @Json
  static final class Last {
    String kind;
    Snapshot.Via ssh, kubectl;

    /** the main class connected to last, per kind */
    java.util.Map<String, String> name = new java.util.HashMap<>();

    List<String> hosts = new ArrayList<>();

    /** the packages counted per main class */
    java.util.Map<String, String> include = new java.util.HashMap<>();
  }

  private final App app;
  private final Runnable close;
  String kind = "local";

  /** what was typed per kind (kept while switching sections) */
  final java.util.Map<String, Snapshot.Via> where = new java.util.HashMap<>();

  List<Targets.Jvm> listed;
  Targets.Jvm sel;
  boolean busy;
  String err;

  /** why the main class used last time is not selected (no JVM or several run it) */
  String hint;

  final Last last;

  private final Box nav = new Box(Box.COL).pad(10, 8, 10, 8).gap(2);
  private final Box body = new Box(Box.COL).pad(12, 16, 4, 16).gap(12);

  /** its height follows the width it gets (the attach notes wrap), not the width it would like */
  private final Box foot =
      new Box(Box.ROW) {
        @Override
        public Dimension getPreferredSize() {
          int w =
              getParent() != null && getParent().getWidth() > 0
                  ? getParent().getWidth()
                  : super.getPreferredSize().width;
          return new Dimension(w, heightFor(w));
        }
      }.pad(12, 16, 16, 16).gap(10);

  /** the parts that change while the fields keep focus */
  private final Box jvms = new Box(Box.COL), inc = new Box(Box.COL).gap(6);

  /** how the packages whose calls are counted are chosen */
  static final String INC_AUTO = "auto", INC_CUSTOM = "custom", INC_ALL = "all";

  /** auto: the main class's package (or what was used for it last time); all: every method */
  String incMode = INC_AUTO;

  /** the packages whose calls are counted in the chosen JVM ("" : none, "*" : all) */
  String include = "";

  private final JTextField incField = new JTextField();

  StartCenter(App app, Runnable close) {
    this.app = app;
    this.close = close;
    last = readLast();
    where.put("ssh", last.ssh != null ? last.ssh.copy() : blankSsh());
    where.put("kubectl", last.kubectl != null ? last.kubectl.copy() : Targets.initial("kubectl"));
    setLayout(new BorderLayout());
    Box head = new Box(Box.ROW).pad(12, 12, 12, 16).gap(10);
    head.ruleBottom = true;
    // no close button: the dialog's title bar, Esc and Cancel close it
    head.with(txt("Start Center", Theme.sans(16, 700), Theme::fg, 24));
    nav.ruleRight = true;
    nav.setPreferredSize(new Dimension(180, 0));
    JPanel main = new JPanel(new BorderLayout());
    main.setOpaque(false);
    main.add(scroll(body), BorderLayout.CENTER);
    main.add(foot, BorderLayout.SOUTH);
    add(head, BorderLayout.NORTH);
    add(nav, BorderLayout.WEST);
    add(main, BorderLayout.CENTER);
    setPreferredSize(new Dimension(920, 560));
    // the packages typed for Custom; the other modes recompute on selection
    incField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                changedUpdate(e);
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                changedUpdate(e);
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                if (incMode.equals(INC_CUSTOM)) {
                  include = incField.getText().trim();
                }
              }
            });
    Snapshot.Via via = app.s != null ? app.s.snap.target.via : null;
    section(
        app.s != null
            ? (via != null ? via.kind : "local")
            : last.kind != null ? last.kind : "local");
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(Theme.surface());
    g.fillRect(0, 0, getWidth(), getHeight());
  }

  /** no host until one is typed (the prototype's sample host is not a real one) */
  static Snapshot.Via blankSsh() {
    Snapshot.Via v = new Snapshot.Via("ssh");
    v.host = "";
    v.runAs = "";
    return v;
  }

  Snapshot.Via w() {
    return w(kind);
  }

  /** what was typed for a kind of place */
  Snapshot.Via w(String k) {
    return where.computeIfAbsent(k, Targets::initial);
  }

  // ---------- the last connection ----------

  Last readLast() {
    try {
      Last l = LAST.fromJson(app.prefs.get("last", "{}"));
      if (l != null && l.include == null) {
        l.include = new java.util.HashMap<>();
      }
      return l != null && l.name != null && l.hosts != null ? l : new Last();
    } catch (RuntimeException e) {
      return new Last(); // unreadable preferences: start over
    }
  }

  private void saveLast(String name) {
    last.kind = kind;
    last.name.put(kind, name);
    last.include.put(name, include);
    if (kind.equals("ssh")) {
      last.ssh = w().copy();
      last.hosts.remove(last.ssh.host);
      last.hosts.add(0, last.ssh.host);
      last.hosts = new ArrayList<>(last.hosts.subList(0, Math.min(8, last.hosts.size())));
    } else if (kind.equals("kubectl")) {
      last.kubectl = w().copy();
    }
    app.prefs.put("last", LAST.toJson(last));
  }

  /** the Host field's suggestions: hosts used before, and the Host entries of ~/.ssh/config */
  List<String> hosts() {
    java.util.LinkedHashSet<String> h = new java.util.LinkedHashSet<>(last.hosts);
    try {
      h.addAll(
          Targets.sshHosts(
              java.nio.file.Files.readAllLines(
                  java.nio.file.Path.of(System.getProperty("user.home"), ".ssh", "config"))));
    } catch (java.io.IOException | RuntimeException e) {
      // no or unreadable ssh config: no suggestions from it
    }
    return new ArrayList<>(h);
  }

  // ---------- actions ----------

  /**
   * opens a section; it lists its JVMs at once when it is Local or was used before (one place: the
   * one last used there)
   */
  void section(String k) {
    kind = k;
    listed = null;
    sel = null;
    err = null;
    hint = null;
    busy = false;
    inc.setVisible(false);
    renderAll();
    String prefer = last.name.get(k);
    Runnable list =
        () -> {
          if (k.equals("local") || prefer != null) {
            listJvms(prefer);
          }
        };
    if (k.equals("kubectl")) {
      loadKube(list);
    } else {
      list.run();
    }
  }

  /** what kubectl reports for the fields; null until read */
  List<String> contexts, namespaces, pods;

  boolean kubeBusy;

  /**
   * Reads the contexts, the namespaces of the chosen one, its running pods and the pod's
   * containers, keeping what was chosen where it still exists (a pod replaced by a rollout: one of
   * the same Deployment), then runs then.
   */
  void loadKube(Runnable then) {
    Snapshot.Via v = w().copy();
    kubeBusy = true;
    err = null;
    renderParts();
    Connector.Shell sh = app.shell;
    Thread t =
        new Thread(
            () -> {
              try {
                List<String> cs = Connector.contexts(sh);
                String ctx =
                    cs.contains(v.context) || cs.isEmpty()
                        ? v.context == null ? "" : v.context
                        : cs.get(0);
                List<String> ns;
                try {
                  ns = Connector.namespaces(sh, ctx);
                } catch (java.io.IOException forbidden) {
                  ns =
                      v.namespace == null
                          ? List.of()
                          : List.of(v.namespace); // RBAC may allow pods but not listing namespaces
                }
                String n =
                    ns.contains(v.namespace) || ns.isEmpty()
                        ? v.namespace == null ? "" : v.namespace
                        : ns.contains("default") ? "default" : ns.get(0);
                List<String> ps = Connector.pods(sh, ctx, n);
                String pod = ps.isEmpty() ? "" : Targets.samePod(ps, v.pod);
                List<String> cn = pod.isEmpty() ? List.of() : Connector.containers(sh, ctx, n, pod);
                String c =
                    cn.contains(v.container) || cn.isEmpty()
                        ? v.container == null ? "" : v.container
                        : cn.get(0);
                List<String> nsF = ns;
                javax.swing.SwingUtilities.invokeLater(
                    () -> {
                      if (!kind.equals("kubectl")) {
                        return;
                      }
                      kubeBusy = false;
                      contexts = cs;
                      namespaces = nsF;
                      pods = ps;
                      Snapshot.Via x = w();
                      x.context = ctx;
                      x.namespace = n;
                      x.pod = pod;
                      x.container = c;
                      renderAll();
                      if (then != null) {
                        then.run();
                      }
                    });
              } catch (java.io.IOException | RuntimeException e) {
                javax.swing.SwingUtilities.invokeLater(
                    () -> {
                      kubeBusy = false;
                      err = "kubectl: " + e.getMessage();
                      renderAll();
                    });
              }
            },
            "jvmeter-kubectl");
    t.setDaemon(true);
    t.start();
  }

  /**
   * prefer: the main class used last time here; it is selected again, or the list says why it
   * cannot be
   */
  void listJvms(String prefer) {
    err = Targets.check(w());
    listed = null;
    sel = null;
    hint = null;
    busy = err == null;
    renderParts();
    if (!busy) {
      return;
    }
    String k = kind;
    Snapshot.Via where = w().copy();
    java.util.function.Consumer<Runnable> back =
        r ->
            javax.swing.SwingUtilities.invokeLater(
                () -> {
                  if (kind.equals(k) && busy && where.equals(w())) { // still the place asked about
                    busy = false;
                    r.run();
                    renderParts();
                  }
                });
    Thread t =
        new Thread(
            () -> {
              try (Connector c = connector(where)) {
                List<Targets.Jvm> l = c.list();
                back.accept(() -> listed(l, prefer));
              } catch (java.io.IOException | RuntimeException e) {
                back.accept(() -> err = "Could not list the JVMs: " + e.getMessage());
              }
            },
            "jvmeter-list");
    t.setDaemon(true);
    t.start();
  }

  Connector connector(Snapshot.Via where) {
    return new Connector(where, App.agentJar(), app.shell);
  }

  /**
   * the listed JVMs: the main class used last time is selected again, or the hint says why it
   * cannot be
   */
  private void listed(List<Targets.Jvm> l, String prefer) {
    listed = l;
    List<Targets.Jvm> same = l.stream().filter(j -> j.name.equals(prefer)).toList();
    sel = same.isEmpty() ? (l.isEmpty() ? null : l.get(0)) : same.get(0);
    chosen();
    if (prefer != null && same.size() != 1) {
      String c = prefer.substring(prefer.lastIndexOf('.') + 1);
      hint =
          same.isEmpty()
              ? "No JVM runs " + c + " here now: choose another one."
              : same.size() + " JVMs run " + c + " here: choose one.";
    }
  }

  void select(Targets.Jvm j) {
    sel = j;
    chosen();
    renderParts();
  }

  /**
   * a JVM was chosen: count calls in what was used for its main class last time, or in its own
   * package ("*" chooses the All preset)
   */
  private void chosen() {
    if (sel != null) {
      String saved = last.include.getOrDefault(sel.name, Targets.include(sel.name));
      if (saved.equals("*")) {
        incMode = INC_ALL;
        include = "*";
      } else {
        incMode = INC_AUTO;
        include = saved;
      }
      incField.setText(saved.equals("*") ? "" : saved);
    }
  }

  void connect() {
    if (sel == null || attaching) {
      return;
    }
    saveLast(sel.name);
    attaching = true;
    err = null;
    renderParts();
    Snapshot.Via via = kind.equals("local") ? null : w().copy();
    if (via != null && Connector.blank(via.runAs)) {
      via.runAs = null;
    }
    app.connect(
        connector(w().copy()),
        sel,
        via,
        include.isBlank() ? null : include,
        why -> {
          attaching = false;
          err = (sel.port > 0 ? "Could not connect: " : "Could not attach: ") + why;
          renderParts();
        });
  }

  /** while attaching and connecting (the window closes when the JVM answers) */
  boolean attaching;

  void focusCurrent() {
    for (java.awt.Component c : nav.getComponents()) {
      if (c instanceof Btn b && Boolean.TRUE.equals(b.getClientProperty("current"))) {
        b.requestFocusInWindow();
      }
    }
  }

  // ---------- rendering ----------

  /** the whole section: only when it opens (typing must not lose focus) */
  void renderAll() {
    nav.removeAll();
    for (String[] k : KINDS) {
      nav.add(navBtn(k[1], k[2], k[0].equals(kind), () -> section(k[0])));
    }
    nav.add(
        new Box(Box.COL)
            .pad(6, 4, 6, 4)
            .add(
                canvas(
                    1,
                    1,
                    (g, c) -> {
                      g.setColor(Theme.line());
                      g.fillRect(0, 0, c.getWidth(), 1);
                    })));
    nav.add(navBtn("folder_open", "Open snapshot…", false, app::openSnapshot));
    nav.add(
        navBtn(
            "science",
            "Sample data",
            false,
            () -> {
              close.run();
              app.open(Snapshot.loadSample());
            }));

    body.removeAll();
    body.add(
        note(kind.equals("local") ? NOTE_LOCAL : kind.equals("ssh") ? NOTE_SSH : NOTE_KUBECTL));
    Box fields = new Box(Box.ROW).gap(10);
    fields.center = false;
    Snapshot.Via v = w();
    if (kind.equals("ssh")) {
      fields.add(field("Host", combo(hosts(), v.host, true, t -> v.host = t), 200));
      fields.add(
          field("Run as (optional)", text(v.runAs, "the JVM's user", t -> v.runAs = t), 110));
    } else if (kind.equals("kubectl")) {
      // a change reads what depends on it again (the namespaces of a context, the pods of a
      // namespace, the pod's containers)
      fields.add(
          field(
              "Context",
              combo(
                  known(contexts, v.context),
                  v.context,
                  false,
                  t -> {
                    v.context = t;
                    loadKube(null);
                  }),
              110));
      fields.add(
          field(
              "Namespace",
              combo(
                  known(namespaces, v.namespace),
                  v.namespace,
                  false,
                  t -> {
                    v.namespace = t;
                    loadKube(null);
                  }),
              110));
      fields.add(
          field(
              "Pod",
              combo(
                  known(pods, v.pod),
                  v.pod,
                  false,
                  t -> {
                    v.pod = t;
                    v.container = "";
                    loadKube(null);
                  }),
              200));
      fields.add(
          field(
              "Container",
              text(v.container == null ? "" : v.container, "", t -> v.container = t),
              110));
    }
    Btn listB = btn("search", kind.equals("local") ? "Refresh" : "List JVMs", () -> listJvms(null));
    fields.add(kind.equals("local") ? listB : new Box(Box.COL).pad(18, 0, 0, 0).add(listB));
    incField.setFont(Theme.sans(13, 400));
    incField.putClientProperty("FlatLaf.style", "arc: 8");
    incField.putClientProperty("JTextField.placeholderText", "packages, e.g. com.example");
    incField.getAccessibleContext().setAccessibleName("Packages to count calls in");
    inc.center = false;
    body.with(fields, jvms, inc);
    renderParts();
  }

  /** the preset radios, and the packages field only for Custom */
  void renderInc() {
    inc.removeAll();
    inc.add(
        new Box(Box.ROW)
            .gap(18)
            .with(
                txt("Count calls in", Theme.sans(12, 400), Theme::muted, 20),
                incPreset(INC_AUTO),
                incPreset(INC_CUSTOM),
                incPreset(INC_ALL)));
    if (incMode.equals(INC_CUSTOM)) {
      incField.setPreferredSize(new Dimension(280, 32));
      inc.add(
          new Box(Box.ROW).gap(10).with(incField, grow(new Box(Box.COL).add(note(INCLUDE_NOTE)))));
    }
    inc.setVisible(sel != null);
    inc.revalidate();
    inc.repaint();
  }

  /** a preset radio: auto and all decide the packages; custom shows the field to type them */
  private Btn incPreset(String mode) {
    boolean on = incMode.equals(mode);
    String label =
        switch (mode) {
          case INC_ALL -> "All methods";
          case INC_CUSTOM -> "Custom packages";
          default -> "Application packages";
        };
    Btn r = new Btn(() -> incSelect(mode), label);
    r.pad(4, 0, 4, 0).gap(6);
    r.with(radio(on), txt(label, Theme.sans(13, 400), Theme::fg, 20));
    return r;
  }

  /** a radio button's circle, filled when on */
  private static JComponent radio(boolean on) {
    return canvas(
        16,
        16,
        (g, c) -> {
          g.setColor(on ? Theme.accent() : Theme.muted());
          g.setStroke(new java.awt.BasicStroke(1.2f));
          g.draw(new java.awt.geom.Ellipse2D.Double(1.5, 1.5, 13, 13));
          if (on) {
            g.fill(new java.awt.geom.Ellipse2D.Double(5.5, 5.5, 5, 5));
          }
        });
  }

  void incSelect(String mode) {
    incMode = mode;
    include =
        switch (mode) {
          case INC_ALL -> "*";
          case INC_CUSTOM -> incField.getText().trim();
          default -> sel != null ? Targets.include(sel.name) : "";
        };
    renderInc();
  }

  /** the JVM list, the commands and the buttons: they change with the place and the chosen JVM */
  void renderParts() {
    foot.removeAll();
    jvms.removeAll();
    Box lb = listBox();
    if (kubeBusy) {
      lb.add(empty("Reading contexts, namespaces and pods with kubectl…", Theme::muted));
    } else if (busy) {
      lb.add(
          empty(
              "Listing JVMs"
                  + (kind.equals("local")
                      ? ""
                      : " on " + (kind.equals("ssh") ? w().host : Targets.label(w())))
                  + "…",
              Theme::muted));
    } else if (err != null && listed == null) {
      lb.add(empty(err, Theme::bad));
    } else if (listed == null) {
      lb.add(empty("Enter where the JVM runs, then choose List JVMs.", Theme::muted));
    } else {
      String note =
          err != null
              ? err
              : hint != null ? hint : listed.isEmpty() ? "No JVMs run here as your user." : null;
      if (note != null) {
        Box hb =
            new Box(Box.ROW)
                .pad(8, 14, 8, 14)
                .bg(() -> Theme.alpha(err != null ? Theme.bad() : Theme.waitC(), 14));
        hb.ruleBottom = true;
        hb.getAccessibleContext().setAccessibleName(note);
        lb.add(hb.add(options(txt(note, Theme.sans(13, 400), Theme::fg, 20).wrap())));
      }
      Box h = new Box(Box.ROW).pad(8, 10, 8, 10).gap(0);
      h.ruleBottom = true;
      h.with(
          cell(null, 28),
          cell(txt("PID", Theme.sans(12, 500), Theme::muted, 16).align("right"), 50),
          cell(null, 10),
          grow(cell(txt("Main class", Theme.sans(12, 500), Theme::muted, 16), 0)),
          cell(txt("JVM", Theme.sans(12, 500), Theme::muted, 16), 120),
          cell(txt("Agent", Theme.sans(12, 500), Theme::muted, 16), 160));
      lb.add(h);
      for (int i = 0; i < listed.size(); i++) {
        lb.add(jvmRow(listed.get(i), i < listed.size() - 1));
      }
    }
    jvms.add(lb);

    renderInc();

    // what attaching to this JVM means: only the pause (info) when it was prepared, else what its
    // options leave out (warning); the full text is the tooltip
    List<String> notes = Targets.attachNotes(sel);
    if (!notes.isEmpty()) {
      boolean warn = notes.size() > 1;
      Txt t = txt(notes.get(0), Theme.sans(12, 400), Theme::muted, 18).ellipsis();
      Box note =
          new Box(Box.ROW)
              .gap(6)
              .with(
                  icFill(warn ? "warning" : "info", 16, warn ? Theme::waitC : Theme::accent),
                  grow(t));
      note.setToolTipText(String.join(" ", notes));
      foot.add(grow(note));
    } else {
      foot.add(grow(new Box(Box.ROW)));
    }
    Btn go =
        primary(
            attaching
                ? (sel.port == 0 ? "Attaching…" : "Connecting…")
                : sel != null && sel.port == 0 ? "Attach and connect" : "Connect",
            this::connect);
    go.disabled(sel == null || attaching);
    foot.with(btn(null, "Cancel", close), go);
    revalidate();
    repaint();
  }

  private Btn navBtn(String icon, String label, boolean current, Runnable action) {
    Supplier<Color> col = current ? Theme::accent : Theme::muted;
    Btn b = new Btn(action, label);
    b.pad(8, 10, 8, 10).gap(10).radius(8);
    b.bg = current ? Theme::accentSoft : null;
    b.hoverBg = current ? Theme::accentSoft : Theme::sunken;
    b.putClientProperty("current", current);
    return (Btn) b.with(ic(icon, 20, col), txt(label, Theme.sans(13, 500), col, 20));
  }

  /** the choices kubectl reported, or just the value before it has */
  private static List<String> known(List<String> l, String v) {
    return l != null && !l.isEmpty() ? l : v == null || v.isEmpty() ? List.of() : List.of(v);
  }

  private static Txt options(Txt t) {
    t.options = true;
    return t;
  }

  private static Txt note(String s) {
    return txt(s, Theme.sans(12, 400), Theme::muted, 18).wrap();
  }

  private static Box listBox() {
    Box b = new Box(Box.COL).border(Theme::line).radius(8);
    b.clip = true;
    return b;
  }

  private static Box empty(String s, Supplier<Color> c) {
    return new Box(Box.COL).pad(14, 14, 14, 14).add(txt(s, Theme.sans(13, 400), c, 20).wrap());
  }

  /** a fixed-width table cell (0: the width comes from grow) */
  private static Box cell(JComponent c, int w) {
    Box b = new Box(Box.ROW);
    if (c != null) {
      b.add(grow(c));
    }
    b.setPreferredSize(new Dimension(w, 22));
    return b;
  }

  private Btn jvmRow(Targets.Jvm j, boolean rule) {
    boolean on = j == sel;
    Btn r = new Btn(() -> select(j), j.name + ", pid " + j.pid);
    r.pad(4, 10, 4, 10);
    r.ruleBottom = rule;
    r.bg = on ? Theme::accentSoft : null;
    r.hoverBg = on ? Theme::accentSoft : Theme::sunken;
    Font f = Theme.sans(13, 400);
    Box agent =
        j.port > 0
            ? App.badge("Running · port " + j.port, () -> Theme.alpha(Theme.good(), 16), Theme::fg)
            : new Box(Box.ROW).add(txt("Not loaded", Theme.sans(12, 400), Theme::muted, 18));
    Txt name = txt(j.name, f, Theme::fg, 16).ellipsis();
    r.setToolTipText(j.name);
    r.with(
        cell(radio(on), 28),
        cell(txt(String.valueOf(j.pid), Theme.num(f), Theme::fg, 16).align("right"), 50),
        cell(null, 10),
        grow(cell(name, 0)),
        cell(txt(j.jvm, f, Theme::fg, 16), 120),
        cell(agent, 160));
    r.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              connect();
            }
          }
        });
    return r;
  }

  private static Box field(String label, JComponent input, int w) {
    input.setPreferredSize(new Dimension(w, 32));
    Box b = new Box(Box.COL).gap(4).with(txt(label, Theme.sans(12, 400), Theme::muted, 16), input);
    b.stretch = false;
    return b;
  }

  private JTextField text(
      String value, String placeholder, java.util.function.Consumer<String> set) {
    JTextField f = new JTextField(value);
    f.putClientProperty("JTextField.placeholderText", placeholder);
    style(f);
    listen(f, set);
    return f;
  }

  private JComboBox<String> combo(
      List<String> items, String value, boolean editable, java.util.function.Consumer<String> set) {
    JComboBox<String> c = new JComboBox<>(items.toArray(new String[0]));
    c.setEditable(editable);
    if (value != null) {
      c.setSelectedItem(value);
    } else {
      c.setSelectedIndex(-1);
    }
    style(c);
    if (editable) {
      JTextComponent ed = (JTextComponent) c.getEditor().getEditorComponent();
      ed.putClientProperty("JTextField.placeholderText", "user@host or a Host in ~/.ssh/config");
      listen(ed, set);
    } else {
      c.addActionListener(e -> changed(set, (String) c.getSelectedItem()));
    }
    return c;
  }

  private static void style(JComponent c) {
    c.setFont(Theme.sans(13, 400));
    c.putClientProperty("FlatLaf.style", "arc: 8");
  }

  private void listen(JTextComponent f, java.util.function.Consumer<String> set) {
    f.getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                changed(set, f.getText());
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                changed(set, f.getText());
              }

              @Override
              public void changedUpdate(DocumentEvent e) {}
            });
  }

  /** typing changes where the JVM runs: the listed JVMs belong to the old place */
  private void changed(java.util.function.Consumer<String> set, String v) {
    set.accept(v);
    listed = null;
    sel = null;
    err = null;
    hint = null;
    renderParts();
  }
}
