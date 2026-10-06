package jvmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import jvmeter.core.Snapshot;
import jvmeter.core.Targets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A real JVM, end to end: the Start Center lists it, attaches the agent, and the recording comes in
 * live. Needs the packaged agent (agent/target/jvmeter-agent.jar, as in ./mvnw package); skipped
 * without it.
 */
class LiveTest {

  /** the JVM to profile: calls a method in a loop, holds a lock now and then, and allocates */
  public static final class Busy {
    static final Object LOCK = new Object();
    static long sink;

    static long work(int i) {
      long s = 0;
      for (int k = 0; k < 300; k++) {
        s += k ^ i;
      }
      return s;
    }

    public static void main(String[] a) throws Exception {
      // holds the lock for 80 ms of every 100 ms
      Thread holder =
          new Thread(
              () -> {
                while (true) {
                  synchronized (LOCK) {
                    long e = System.nanoTime() + 80_000_000;
                    while (System.nanoTime() < e) {
                      sink++;
                    }
                  }
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              },
              "holder");
      holder.setDaemon(true);
      holder.start();
      long end = System.currentTimeMillis() + 120_000;
      while (System.currentTimeMillis() < end) {
        for (int i = 0; i < 2000; i++) {
          sink += work(i);
        }
        synchronized (LOCK) {
          sink += new byte[50_000].length;
        }
      }
    }
  }

  static Process child;

  @BeforeAll
  static void start() throws Exception {
    System.setProperty("java.awt.headless", "true");
    assumeTrue(Files.exists(App.agentJar()), "the packaged agent (./mvnw package)");
    child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+EnableDynamicAgentLoading",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints",
                "-cp",
                System.getProperty("java.class.path"),
                Busy.class.getName())
            .inheritIO()
            .start();
    Thread.sleep(1500);
  }

  @AfterAll
  static void stop() {
    if (child != null) {
      child.destroyForcibly();
    }
  }

  static void await(String what, BooleanSupplier ok) throws Exception {
    for (int i = 0; i < 300; i++) {
      boolean[] r = new boolean[1];
      AppTest.onEdt(() -> r[0] = ok.getAsBoolean());
      if (r[0]) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  @Test
  void listAttachRecordLive() throws Exception {
    record(null);
  }

  /**
   * the same over ssh to this computer: JVMETER_SSH names a host (in ~/.ssh/config) that reaches it
   * with a key
   */
  @Test
  void overSsh() throws Exception {
    String host = System.getenv("JVMETER_SSH");
    assumeTrue(host != null, "JVMETER_SSH=a host that is this computer");
    record(host);
  }

  /**
   * a JVM in a pod: JVMETER_KUBE=context/namespace, whose first running pod runs a JVM (with sh and
   * jdk.attach)
   */
  @Test
  void inKubernetes() throws Exception {
    String kube = System.getenv("JVMETER_KUBE");
    assumeTrue(kube != null && kube.contains("/"), "JVMETER_KUBE=context/namespace");
    App[] a = new App[1];
    StartCenter[] sc = new StartCenter[1];
    java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(App.class);
    String saved = prefs.get("last", null);
    try {
      AppTest.onEdt(
          () -> {
            App.applyLaf();
            a[0] = new App(null);
            prefs.remove("last");
            sc[0] = new StartCenter(a[0], () -> {});
            sc[0].w("kubectl").context = kube.split("/")[0];
            sc[0].w("kubectl").namespace = kube.split("/")[1];
            sc[0].section("kubectl");
          });
      await("the pods", () -> sc[0].pods != null || sc[0].err != null);
      AppTest.onEdt(
          () -> {
            assertEquals(null, sc[0].err);
            assertFalse(sc[0].pods.isEmpty());
            sc[0].listJvms(null);
          });
      await("the list", () -> sc[0].listed != null || sc[0].err != null);
      AppTest.onEdt(
          () -> {
            assertEquals(null, sc[0].err);
            assertNotNull(sc[0].sel);
            sc[0].connect();
          });
      await(
          "the session",
          () -> {
            assertEquals(null, sc[0].err);
            return a[0].s != null && a[0].live != null;
          });
      AppTest.onEdt(() -> assertEquals(sc[0].w().pod, a[0].s.snap.target.via.pod));
      await("the call tree", () -> !a[0].s.tree.hot.isEmpty() && !a[0].s.threads.isEmpty());
      AppTest.onEdt(
          () -> {
            AppTest.paint(a[0]);
            a[0].live.send(new jvmeter.core.Live.Command("gc", null));
          });
      await("a class histogram", () -> !a[0].s.classes.isEmpty());
      AppTest.onEdt(() -> a[0].disconnect());
    } finally {
      if (saved != null) {
        prefs.put("last", saved);
      } else {
        prefs.remove("last");
      }
    }
  }

  void record(String sshHost) throws Exception {
    App[] a = new App[1];
    StartCenter[] sc = new StartCenter[1];
    java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(App.class);
    String saved = prefs.get("last", null);
    try {
      AppTest.onEdt(
          () -> {
            App.applyLaf();
            a[0] = new App(null);
            prefs.remove("last");
            sc[0] = new StartCenter(a[0], () -> {});
            if (sshHost != null) {
              sc[0].section("ssh");
              sc[0].w().host = sshHost;
              sc[0].listJvms(null);
            }
          });
      await("the list", () -> sc[0].listed != null || sc[0].err != null);
      Targets.Jvm[] j = new Targets.Jvm[1];
      AppTest.onEdt(
          () -> {
            assertEquals(null, sc[0].err);
            j[0] = sc[0].listed.stream().filter(x -> x.pid == child.pid()).findFirst().orElse(null);
            assertNotNull(j[0], "the child JVM in " + sc[0].listed.size() + " JVMs");
            assertEquals(Busy.class.getName(), j[0].name);
            assertTrue(j[0].args.contains("-XX:+DebugNonSafepoints"));
            sc[0].select(j[0]);
            sc[0].connect();
            assertTrue(sc[0].attaching);
          });
      await(
          "the session",
          () -> {
            assertEquals(null, sc[0].err); // attaching or connecting failed
            return a[0].s != null && a[0].live != null;
          });
      AppTest.onEdt(
          () -> {
            assertEquals("attach", a[0].s.snap.target.agent);
            assertEquals(child.pid(), a[0].s.snap.target.pid);
            assertEquals(
                sshHost, a[0].s.snap.target.via != null ? a[0].s.snap.target.via.host : null);
            assertTrue(a[0].state.recording);
          });
      await(
          "the call tree",
          () ->
              a[0].s.tree.hot.stream().anyMatch(h -> h.name.endsWith("Busy.work") && h.calls > 0));
      await(
          "main waiting for the holder's lock",
          () -> a[0].s.threads.stream().anyMatch(t -> t.name.equals("main") && t.sum("block") > 0));
      AppTest.onEdt(
          () -> {
            assertTrue(a[0].s.elapsed >= 5);
            assertFalse(a[0].s.tel.isEmpty());
            assertTrue(a[0].s.threads.stream().anyMatch(t -> t.name.equals("holder")));
            AppTest.paint(a[0]);
            a[0].state.view = "memory";
            a[0].render();
            Ui.Btn gc =
                AppTest.find(a[0], Ui.Btn.class).stream()
                    .filter(b -> "Run GC".equals(b.getAccessibleContext().getAccessibleName()))
                    .findFirst()
                    .get();
            gc.action.run();
          });
      await(
          "Run GC's GC, marked as such, and a class histogram",
          () ->
              !a[0].s.classes.isEmpty()
                  && a[0].s.gc.events.stream()
                      .anyMatch(e -> e.cause.equals("System.gc()") && e.isManual()));
      AppTest.onEdt(
          () -> {
            AppTest.paint(a[0]);
            assertTrue(a[0].s.classes.stream().anyMatch(c -> c.name.equals("byte[]")));
            a[0].toggleRecording(); // stop: the last data follows
            assertFalse(a[0].state.recording);
            // what the GUI would save: the live session as a snapshot
            Snapshot back = Snapshot.parse(jvmeter.core.SnapshotWriter.write(a[0].s));
            assertEquals(child.pid(), back.target.pid);
            assertNotNull(back.telemetry);
            a[0].disconnect();
          });
    } finally {
      if (saved != null) {
        prefs.put("last", saved);
      } else {
        prefs.remove("last");
      }
    }
  }
}
