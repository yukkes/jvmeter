package jvmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import jvmeter.core.Connector;
import jvmeter.core.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Headless smoke tests of the interactions: every view renders, and clicks change the state like in
 * the prototype.
 */
class AppTest {

  /** the first exception no one caught, on the EDT or another thread: fails the next onEdt */
  static final AtomicReference<Throwable> uncaught = new AtomicReference<>();

  static {
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.compareAndSet(null, e));
  }

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  static void onEdt(Runnable r) throws Exception {
    SwingUtilities.invokeAndWait(r);
    Throwable e = uncaught.getAndSet(null);
    if (e != null) {
      throw new AssertionError("uncaught exception", e);
    }
  }

  /** lays the app out at 1280x800 and 1920x1080 and paints it, so layout and painting code runs */
  static void paint(App app) {
    for (int[] size : new int[][] {{1920, 1080}, {1280, 800}}) {
      app.setSize(size[0], size[1]);
      Shot.layout(app);
      Shot.layout(app);
      BufferedImage img = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
      Graphics2D g = img.createGraphics();
      app.paint(g);
      g.dispose();
    }
  }

  static <T> List<T> find(Container c, Class<T> type) {
    List<T> out = new ArrayList<>();
    for (Component ch : c.getComponents()) {
      if (type.isInstance(ch)) {
        out.add(type.cast(ch));
      }
      if (ch instanceof Container k) {
        out.addAll(find(k, type));
      }
    }
    return out;
  }

  static App app() {
    App.applyLaf();
    return new App(Snapshot.loadSample());
  }

  /**
   * kubectl, as the fake shell below answers it: one context, two namespaces, two pods, one JVM;
   * attaching fails
   */
  static Connector.Result kubectl(List<String> argv, byte[] stdin) {
    String all = String.join(" ", argv);
    String out =
        all.contains("get-contexts")
            ? "prod-tokyo\nstaging\n"
            : all.contains("current-context")
                ? "prod-tokyo\n"
                : all.contains("get namespaces")
                    ? "namespace/default\nnamespace/orders\n"
                    : all.contains("get pods")
                        ? "pod/orders-api-7f9c6d-x2x4q\npod/orders-api-7f9c6d-k8m1z\n"
                        : all.contains("jsonpath")
                            ? "app"
                            : all.contains("sha256sum") || all.contains("cat >")
                                ? ""
                                : all.contains(" list") && all.startsWith("kubectl")
                                    ? "[{\"pid\":1,\"name\":\"com.example.orders.OrderServiceApp\",\"jvm\":\"OpenJDK"
                                        + " 21.0.8\",\"args\":\"-XX:MaxRAMPercentage=75\",\"port\":0}]"
                                    : all.contains(" list") ? "[]" : null;
    return out != null
        ? new Connector.Result(0, out, "")
        : new Connector.Result(
            1, "{\"error\":\"AttachNotSupportedException: no jdk.attach in this runtime\"}", "");
  }

  static void await(BooleanSupplier ok) throws Exception {
    for (int i = 0; i < 100; i++) {
      boolean[] r = new boolean[1];
      onEdt(() -> r[0] = ok.getAsBoolean());
      if (r[0]) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("timed out");
  }

  /**
   * a method or thread name from a snapshot or a profiled JVM that starts with "<html>" is shown as
   * text: rendered as HTML, its img would make the GUI fetch any URL (or a file:// share, leaking
   * the Windows login's NTLM hash)
   */
  @Test
  void tooltipsNeverRenderHtml() throws Exception {
    onEdt(
        () -> {
          for (javax.swing.JComponent c :
              List.of(new Table(), new Ui.Btn(() -> {}, "x"), new Ui.Box(Ui.Box.ROW))) {
            javax.swing.JToolTip tip = c.createToolTip();
            tip.setTipText("<html><img src='http://example.invalid/x.png'>");
            assertNull(
                tip.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey),
                c.getClass().getSimpleName());
          }
        });
  }

  /**
   * like the prototype: no JVM at first; the Start Center lists, attaches, and next time opens
   * where it left off
   */
  @Test
  void startCenterResumesWhereItLeftOff() throws Exception {
    App[] a = new App[1];
    StartCenter[] sc = new StartCenter[1];
    java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(App.class);
    String saved = prefs.get("last", null);
    try {
      onEdt(
          () -> {
            App.applyLaf();
            a[0] = new App(null);
            a[0].shell = AppTest::kubectl;
            paint(a[0]); // the empty page: rail and recording disabled
            assertNull(a[0].s);
            prefs.remove("last");
            sc[0] = new StartCenter(a[0], () -> {});
            assertEquals("local", sc[0].kind); // first start: Local, listing at once
            assertTrue(sc[0].busy);
            sc[0].section("kubectl");
            assertFalse(sc[0].busy); // never used: nothing is listed until List JVMs
          });
      await(() -> sc[0].contexts != null);
      onEdt(
          () -> {
            // empty until kubectl reads them: the first context, the default namespace, the first
            // pod
            assertEquals("prod-tokyo", sc[0].w().context);
            assertEquals("default", sc[0].w().namespace);
            assertEquals("orders-api-7f9c6d-x2x4q", sc[0].w().pod);
            assertEquals("app", sc[0].w().container);
            sc[0].w().namespace = "orders";
            sc[0].listJvms(null);
          });
      await(() -> sc[0].listed != null);
      onEdt(
          () -> {
            assertEquals(1, sc[0].sel.pid);
            assertEquals(
                "com.example.orders",
                sc[0].include); // calls counted in the main class's package by default
            sc[0].renderParts();
            sc[0].setSize(920, 560);
            Shot.layout(sc[0]);
            sc[0].paint(new BufferedImage(920, 560, BufferedImage.TYPE_INT_RGB).createGraphics());
            sc[0].connect();
          });
      await(() -> !sc[0].attaching);
      onEdt(
          () -> {
            // the agent's error is shown above the list, and the place is remembered anyway
            assertEquals(
                "Could not attach: AttachNotSupportedException: no jdk.attach in this runtime",
                sc[0].err);
            assertNull(a[0].s);
            sc[0] = new StartCenter(a[0], () -> {});
            assertEquals("kubectl", sc[0].kind); // opens where it left off and lists there
          });
      await(() -> sc[0].listed != null);
      onEdt(
          () -> {
            assertEquals(
                "com.example.orders.OrderServiceApp",
                sc[0].sel.name); // the same main class, selected
            assertEquals("orders", sc[0].w().namespace);
            assertNull(sc[0].hint);
            // after a rollout the pod is gone and the class is not running: a pod of the same
            // Deployment, and the reason
            sc[0].last.kubectl.pod = "orders-api-5d4c3b-zzzzz";
            sc[0].last.name.put("kubectl", "com.example.Gone");
            prefs.put("last", StartCenter.LAST.toJson(sc[0].last));
            sc[0] = new StartCenter(a[0], () -> {});
          });
      await(() -> sc[0].listed != null);
      onEdt(
          () -> {
            assertEquals("orders-api-7f9c6d-x2x4q", sc[0].w().pod);
            assertEquals("No JVM runs Gone here now: choose another one.", sc[0].hint);
          });
    } finally {
      if (saved != null) {
        prefs.put("last", saved);
      } else {
        prefs.remove("last");
      }
    }
  }

  @Test
  void gcMainColumnKeepsItsScrollWhenRebuilt() throws Exception {
    onEdt(
        () -> {
          Theme.dark = false;
          App a = app();
          a.state.view = "memory";
          a.state.memTab = "gc";
          a.render();
          paint(a);
          javax.swing.JScrollPane main = find(a, javax.swing.JScrollPane.class).get(1);
          main.getViewport().setViewPosition(new java.awt.Point(0, 120));
          a.render(); // what every tick of a recording does
          assertEquals(
              120, find(a, javax.swing.JScrollPane.class).get(1).getViewport().getViewPosition().y);
          a.state.memTab = "heap";
          a.render();
          a.state.memTab = "gc";
          a.render(); // another tab in between: starts at the top again
          assertEquals(
              0, find(a, javax.swing.JScrollPane.class).get(1).getViewport().getViewPosition().y);
        });
  }

  @Test
  void everyViewRendersInBothThemes() throws Exception {
    onEdt(
        () -> {
          for (boolean dark : new boolean[] {false, true}) {
            Theme.dark = dark;
            App a = app();
            for (String[] v :
                new String[][] {
                  {"overview", "hot", "heap"},
                  {"cpu", "hot", "heap"},
                  {"cpu", "tree", "heap"},
                  {"memory", "hot", "heap"},
                  {"memory", "hot", "gc"},
                  {"threads", "hot", "heap"}
                }) {
              a.state.view = v[0];
              a.state.cpuTab = v[1];
              a.state.memTab = v[2];
              a.render();
              paint(a);
            }
          }
          Theme.dark = false;
        });
  }

  @Test
  void hotSpotsSortSelectAndSearch() throws Exception {
    onEdt(
        () -> {
          App a = app();
          a.go("cpu");
          paint(a);
          Table t = find(a, Table.class).get(0);
          int all = t.rows.size();
          t.onSort.accept("calls");
          assertEquals("calls", a.state.sortKey);
          assertEquals("desc", a.state.sortDir);
          t = find(a, Table.class).get(0);
          t.onSort.accept("calls");
          assertEquals("asc", a.state.sortDir);
          t = find(a, Table.class).get(0);
          t.onClick.accept(0);
          assertNotNull(a.state.sel);
          paint(a);
        });
  }

  @Test
  void waitsSelectTheThreadThatWaited() throws Exception {
    onEdt(
        () -> {
          App a = app();
          a.go("threads");
          paint(a);
          Table t = find(a, Table.class).get(0);
          assertEquals(3, t.rows.size());
          t.onClick.accept(2); // OrderCache.put, the lock exec-2 waited for
          assertEquals("http-nio-8080-exec-2", a.s.threads.get(a.state.thread).name);
          assertEquals(2, find(a, Table.class).get(0).selected);
        });
  }

  @Test
  void searchFiltersRows() throws Exception {
    App[] a = new App[1];
    onEdt(
        () -> {
          a[0] = app();
          a[0].go("cpu");
          a[0].setQuery("BigDecimal");
        });
    onEdt(
        () -> {
          Table t = find(a[0], Table.class).get(0);
          assertEquals(2, t.rows.size());
          assertTrue(t.tips.stream().allMatch(n -> n.contains("BigDecimal")));
          a[0].setQuery("no-such-method");
        });
    onEdt(() -> assertTrue(find(a[0], Table.class).isEmpty()));
  }

  @Test
  void callTreeToggles() throws Exception {
    onEdt(
        () -> {
          App a = app();
          a.state.view = "cpu";
          a.state.cpuTab = "tree";
          a.render();
          int before = find(a, CpuView.TreeRows.class).get(0).nodes.size();
          jvmeter.core.CallTree.walk(a.s.tree.root, n -> a.state.expanded.add(n.id));
          a.render();
          int expanded = find(a, CpuView.TreeRows.class).get(0).nodes.size();
          assertTrue(expanded > before);
          paint(a);
        });
  }

  @Test
  void pickBeforeAndAfter() throws Exception {
    onEdt(
        () -> {
          App a = app();
          a.state.view = "memory";
          a.state.memTab = "heap";
          a.state.picking = "before";
          a.render();
          assertNotNull(a.pickTarget);
          paint(a);
          AxisChart c = find(a, AxisChart.class).get(0);
          c.onPick.accept(a.s.elapsed - 30);
          assertNull(a.state.picking);
          assertNotNull(a.state.snapBefore);
          assertEquals("diff", a.state.memSort);
          Table t = find(a, Table.class).get(0);
          assertEquals(5, t.cols.size());
          a.state.picking = "after";
          a.render();
          c = find(a, AxisChart.class).get(0);
          assertEquals(c.pickMin, a.state.snapBefore.t());
          c.onPick.accept(a.s.elapsed);
          assertNotNull(a.state.snapAfter);
          paint(a);
        });
  }

  @Test
  void themeToggle() throws Exception {
    onEdt(
        () -> {
          App a = app();
          boolean dark = Theme.dark;
          a.toggleTheme();
          assertFalse(dark == Theme.dark);
          a.toggleTheme();
          paint(a);
        });
  }
}
