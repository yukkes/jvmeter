package app;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The median round of a workload in each state of a profiler, all in one JVM, for
 * demo/profilers.sh:
 *
 * <pre>
 * Rounds fib|map|load N none             no profiler
 * Rounds fib|map|load N jvmeter JAR     before attach, attached (counting), recording, disconnected
 * Rounds fib|map|load N jprofiler        JProfiler loaded with -agentpath: recording off, on, off again
 * Rounds fib|map|load N jfr              an empty JFR recording started and stopped after warming up
 * </pre>
 *
 * fib is fib(35) (huge numbers of tiny calls), map what one thread of {@link Load} does (HashMap
 * and strings, the code a profiler's own startup slows down most), load one round of {@link Load}
 * (4 threads that allocate and share a lock). CPU is the process's CPU time per round, the
 * profiler's threads included: work they do on other cores does not show in the round's time.
 */
public class Rounds {
  /** the CPU time of the whole process: the profiler's own threads included */
  static final com.sun.management.OperatingSystemMXBean CPU =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

  static String workload;
  static long sink;

  static double round() throws InterruptedException {
    long t0 = System.nanoTime();
    switch (workload) {
      case "fib" -> Fib.fibRecursive(35);
      case "map" -> {
        long s = 0;
        for (int i = 0; i < 300_000; i++) {
          s += Load.price(Load.order(i));
        }
        sink += s; // so the JIT cannot drop the work
      }
      default -> Load.round(4, 500_000);
    }
    return (System.nanoTime() - t0) / 1e6;
  }

  /**
   * the median of n rounds, after the JIT has settled: a change of state (code instrumented or
   * restored) throws compiled code away, so the rounds of the first 0.5 s are left out. The first
   * of them shows what recompiling costs.
   */
  static void phase(String name, int n) throws InterruptedException {
    double first = round();
    long settled = System.nanoTime() + 500_000_000L;
    while (System.nanoTime() < settled) {
      round();
    }
    double[] t = new double[n];
    long cpu0 = CPU.getProcessCpuTime();
    for (int i = 0; i < n; i++) {
      t[i] = round();
    }
    double cpu = (CPU.getProcessCpuTime() - cpu0) / 1e6 / n;
    Arrays.sort(t);
    System.out.printf(
        "> %-24s %8.1f ms   first round %8.1f ms   CPU %8.1f ms%n", name, t[n / 2], first, cpu);
  }

  public static void main(String[] a) throws Exception {
    workload = a[0];
    int n = Integer.parseInt(a[1]);
    switch (a[2]) {
      case "none" -> phase("no profiler", n);
      case "jfr" -> {
        phase("before JFR", n);
        jdk.jfr.Recording r = new jdk.jfr.Recording();
        r.start();
        phase("empty JFR recording", n);
        r.stop();
        r.close();
        phase("after JFR stopped", n);
      }
      case "jvmeter" -> jvmeter(n, a[3]);
      case "jprofiler" -> jprofiler(n);
      default -> throw new IllegalArgumentException(a[2]);
    }
    System.exit(0);
  }

  /** attaches the agent to this JVM the way the GUI does, records, and disconnects */
  static void jvmeter(int n, String agentJar) throws Exception {
    phase("before attach", n);
    long pid = ProcessHandle.current().pid();
    Process p =
        new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-jar",
                agentJar,
                "attach",
                "" + pid,
                "include=app")
            .redirectErrorStream(true)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    Matcher port = Pattern.compile("\"port\":(\\d+)").matcher(out);
    Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(out);
    if (!port.find() || !token.find()) {
      throw new IllegalStateException(out);
    }
    phase("attached, not recording", n);
    try (Socket s = connect(Integer.parseInt(port.group(1)))) {
      Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
      w.write(token.group(1) + "\n{\"cmd\":\"start\",\"include\":\"app\"}\n");
      w.flush();
      Thread drain =
          new Thread(
              () -> {
                try {
                  s.getInputStream().transferTo(OutputStream.nullOutputStream());
                } catch (IOException closed) {
                  // the end of the recording
                }
              });
      drain.setDaemon(true);
      drain.start();
      phase("recording", n);
    }
    phase("disconnected", n);
  }

  /** the agent listens on both loopback addresses; some systems accept only one of them */
  static Socket connect(int port) throws IOException {
    IOException last = null;
    for (String host : new String[] {"127.0.0.1", "::1"}) {
      Socket s = new Socket();
      try {
        s.connect(new InetSocketAddress(host, port), 3000);
        return s;
      } catch (IOException e) {
        s.close();
        last = e;
      }
    }
    throw last;
  }

  /** CPU recording on and off through JProfiler's RemoteController MBean */
  static void jprofiler(int n) throws Exception {
    MBeanServer mb = ManagementFactory.getPlatformMBeanServer();
    ObjectName rc = new ObjectName("com.jprofiler.api.agent.mbean:type=RemoteController");
    phase("recording off", n);
    mb.invoke(rc, "startCPURecording", new Object[] {true}, new String[] {"boolean"});
    phase("recording on", n);
    mb.invoke(rc, "stopCPURecording", new Object[0], new String[0]);
    phase("recording off again", n);
  }
}
