package jvmeter.agent;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import jvmeter.core.Live;
import jvmeter.core.Session;
import jvmeter.core.Snapshot;
import jvmeter.core.SnapshotWriter;
import jvmeter.core.Targets;

/**
 * jvmeter agent: call counts are exact (instrumentation), time comes from JFR sampling.
 *
 * <p>java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \ -javaagent:jvmeter-agent.jar -cp
 * jvmeter-demo.jar app.Mix
 *
 * <p>Agent options: include=com.example,app (packages whose calls are counted; default: the main
 * class's package). System properties: jvmeter.include (the same), jvmeter.period (sampling
 * interval in ms, default 1), jvmeter.out (snapshot written at exit as gzip-compressed JSON,
 * default jvmeter-PID.json.gz)
 */
public final class Agent {

  private static CountTransformer counts;
  private static Instrumentation inst;
  private static Server server;

  /** the recording in progress, or null */
  static volatile Recording rec;

  /**
   * loaded into a running JVM (agentmain) rather than with -javaagent: the snapshot says so
   * (target.agent)
   */
  private static boolean attached;

  /**
   * -XX:+DebugNonSafepoints: without it, time in inlined methods is attributed to their callers
   * (null: not HotSpot, unknown)
   */
  private static Boolean debugNonSafepoints;

  /**
   * -javaagent: records from the start and writes the snapshot at exit; a GUI can connect meanwhile
   */
  @SuppressWarnings("unused") // called by the JVM (Premain-Class)
  public static void premain(String args, Instrumentation inst) {
    try {
      install(args, inst);
      rec = new Recording(period(), histogramEvery(), counts.include(), null);
      System.err.println(
          "[jvmeter] agent installed: counts = instrumentation ("
              + counts.include()
              + "), time = JFR sampling every "
              + period().toMillis()
              + " ms, port "
              + server.port);
    } catch (Exception e) {
      System.err.println("[jvmeter] agent install failed: " + e);
    }
  }

  /**
   * loaded into a running JVM (jvmeter-agent.jar attach): waits for the GUI to start a recording;
   * loading it again changes nothing
   */
  @SuppressWarnings("unused") // called by the JVM (Agent-Class)
  public static synchronized void agentmain(String args, Instrumentation inst) {
    if (server != null) {
      return;
    }
    attached = true;
    try {
      install(args, inst);
    } catch (Exception e) {
      System.err.println("[jvmeter] agent install failed: " + e);
    }
  }

  static Live.Message hello() {
    Live.Message m = new Live.Message("hello");
    m.target = target();
    m.heapMaxMB = (double) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
    m.xmsMB =
        (double)
            (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit() / (1024 * 1024));
    m.collector = Recording.collector();
    return m;
  }

  /** a command from the GUI (Live.Command) */
  static synchronized void command(Live.Command c) {
    switch (c.cmd) {
      case "start" -> {
        stopRecording(false);
        if (c.include != null && !c.include.equals(counts.include())) {
          counts.include(inst, c.include);
        }
        rec = new Recording(period(), histogramEvery(), counts.include(), server::send);
      }
      case "stop" -> stopRecording(true);
      case "gc" -> {
        Recording r = rec;
        Instant[] w = r != null ? r.jfr.runGc() : null;
        System.gc();
        if (r != null) {
          r.jfr.runGcDone(w);
          r.histogram();
        }
      }
      case "histogram" -> {
        if (rec != null) {
          rec.histogram();
        }
      }
      default -> server.send(Server.error("Unknown command: " + c.cmd));
    }
  }

  /** final: send the last data before stopping */
  private static void stopRecording(boolean last) {
    Recording r = rec;
    rec = null;
    if (r != null) {
      if (last) {
        r.histogram();
        r.finish();
      }
      r.stop();
    }
  }

  /**
   * the GUI went away: recording stops and the counters are taken out again (an agent cannot be
   * unloaded)
   */
  static synchronized void disconnected() {
    stopRecording(false);
    counts.include(inst, "");
  }

  static Duration period() {
    return Duration.ofMillis(Long.getLong("jvmeter.period", 1));
  }

  /**
   * jvmeter.histogram: seconds between class histograms while recording (default 30; 0: at the end
   * only)
   */
  static int histogramEvery() {
    return Integer.getInteger("jvmeter.histogram", 30);
  }

  static Snapshot.Target target() {
    Snapshot.Target t = new Snapshot.Target();
    t.name = mainClass(System.getProperty("sun.java.command", "?").split(" ")[0]);
    t.pid = ProcessHandle.current().pid();
    t.jvm =
        Targets.jvmLabel(System.getProperty("java.vm.name"), System.getProperty("java.version"));
    t.agent = attached ? "attach" : "startup";
    t.debugNonSafepoints = debugNonSafepoints;
    t.include = counts.include();
    return t;
  }

  /**
   * the counters on the boot class path, the transformer, and the classes to count (args
   * "include=com.example,app")
   */
  private static void install(String args, Instrumentation inst) throws IOException {
    Agent.inst = inst;
    inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar().toFile()));
    counts = new CountTransformer();
    inst.addTransformer(counts, true);
    String include =
        options(args)
            .getOrDefault("include", System.getProperty("jvmeter.include", defaultInclude()));
    // after an attach, the classes already loaded are retransformed; at startup there are none of
    // the application yet
    counts.include(inst, include);
    server = new Server(Integer.parseInt(options(args).getOrDefault("port", "0")));
    Runtime.getRuntime().addShutdownHook(new Thread(Agent::atExit, "jvmeter-exit"));
    debugNonSafepoints = debugNonSafepoints();
    if (Boolean.FALSE.equals(debugNonSafepoints)) {
      System.err.println(
          "[jvmeter] WARNING: -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints is not set. "
              + "Time spent in inlined methods will be attributed to their callers");
    }
  }

  /**
   * Instrumented code calls CallCounter, so it must be on the boot class path, where every class
   * loader finds it. It is copied from this jar into a new temporary jar (readable by this user
   * only), so the agent is a single file. The classes are read as resources: loading them here
   * would load them from the wrong class loader.
   */
  private static Path bootJar() throws IOException {
    Path jar = Files.createTempFile("jvmeter-rt", ".jar");
    jar.toFile().deleteOnExit();
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String c :
          List.of("jvmeter/agent/CallCounter.class", "jvmeter/agent/MethodRegistry.class")) {
        out.putNextEntry(new JarEntry(c));
        try (InputStream in =
            Objects.requireNonNull(Agent.class.getClassLoader().getResourceAsStream(c), c)) {
          in.transferTo(out);
        }
      }
    }
    return jar;
  }

  /**
   * "port=7091,include=com.example,app" -> {port=7091, include=com.example,app}: an item without
   * "=" continues the previous value
   */
  static Map<String, String> options(String args) {
    Map<String, String> o = new HashMap<>();
    String key = null;
    for (String a : (args == null ? "" : args).split(",")) {
      int eq = a.indexOf('=');
      if (eq > 0) {
        key = a.substring(0, eq).trim();
        o.put(key, a.substring(eq + 1).trim());
      } else if (key != null) {
        o.merge(key, "," + a.trim(), String::concat);
      }
    }
    return o;
  }

  /** the main class's package: "com.example.orders.App" -> "com.example.orders" */
  static String defaultInclude() {
    String main = mainClass(System.getProperty("sun.java.command", "").split(" ")[0]);
    return main.endsWith(".jar")
        ? ""
        : main.contains(".") ? main.substring(0, main.lastIndexOf('.')) : main;
  }

  /**
   * the main class, also for -jar (the jar's Start-Class, as in Spring Boot, or Main-Class); the
   * jar when it cannot be read
   */
  static String mainClass(String command) {
    if (!command.endsWith(".jar")) {
      return command;
    }
    try (JarFile j = new JarFile(command)) {
      java.util.jar.Attributes a = j.getManifest().getMainAttributes();
      String c =
          a.getValue("Start-Class") != null ? a.getValue("Start-Class") : a.getValue("Main-Class");
      return c != null ? c : command;
    } catch (IOException | RuntimeException e) {
      return command;
    }
  }

  private static Boolean debugNonSafepoints() {
    HotSpotDiagnosticMXBean hs;
    try {
      hs = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    } catch (Throwable notHotSpot) {
      return null;
    }
    if (hs == null) {
      return null;
    }
    try {
      return Boolean.parseBoolean(hs.getVMOption("DebugNonSafepoints").getValue());
    } catch (IllegalArgumentException locked) {
      // a diagnostic flag "does not exist" until -XX:+UnlockDiagnosticVMOptions, so it is off
      return false;
    }
  }

  /** writes the snapshot of a recording started with -javaagent */
  private static void atExit() {
    Recording r = rec;
    if (r == null || r.sink != null) {
      return; // a recording the GUI started: the GUI has its data
    }
    r.histogram();
    r.stop();
    Snapshot snap = r.snapshot(target());
    Path out =
        Path.of(System.getProperty("jvmeter.out", "jvmeter-" + snap.target.pid + ".json.gz"));
    try {
      SnapshotWriter.write(new Session(snap), out);
      System.err.println("[jvmeter] snapshot written to " + out.toAbsolutePath());
    } catch (IOException | RuntimeException e) {
      System.err.println("[jvmeter] could not write the snapshot: " + e);
    }
  }
}
