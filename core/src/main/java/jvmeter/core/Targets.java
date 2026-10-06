package jvmeter.core;

import io.avaje.jsonb.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a JVM runs (this computer, an SSH host, a Kubernetes pod) and the commands jvmeter runs
 * there (docs/targets.md). Port of the Start Center of demo/prototype.html (whose JVMs are sample
 * data); Connector runs the commands.
 */
public final class Targets {

  public static final String JAR = "jvmeter-agent.jar";

  /**
   * JVM options that let jvmeter attach later, silently and with exact times for inlined methods
   * (no agent is loaded)
   */
  public static final String PREPARE =
      "-XX:+EnableDynamicAgentLoading -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints";

  /**
   * a JVM as `jvmeter-agent.jar list` reports it (args: its JVM options, as jps -v shows them);
   * port > 0 when its agent is already running
   */
  @Json
  public static final class Jvm {
    public long pid;
    public String name, jvm, args;
    public int port;

    /** the running agent's token (list prints it: only the JVM's user can read it) */
    public String token;

    public Jvm(long pid, String name, String jvm, String args, int port) {
      this.pid = pid;
      this.name = name;
      this.jvm = jvm;
      this.args = args;
      this.port = port;
    }

    public boolean debugNonSafepoints() {
      return args.contains("-XX:+DebugNonSafepoints");
    }

    /**
     * JDK 21+ warns when an agent is loaded into a running JVM, and a later release refuses it,
     * unless this option was given (JEP 451)
     */
    public boolean warnsOnAttach() {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(jvm);
      return m.find()
          && Integer.parseInt(m.group(1)) >= 21
          && !args.contains("-XX:+EnableDynamicAgentLoading");
    }
  }

  /**
   * "OpenJDK 21.0.8": the VM's family and the Java version (java.vm.name says "OpenJDK 64-Bit
   * Server VM")
   */
  public static String jvmLabel(String vmName, String version) {
    String n = vmName == null ? "" : vmName;
    return (n.startsWith("OpenJDK")
            ? "OpenJDK"
            : n.contains("OpenJ9") ? "OpenJ9" : n.contains("GraalVM") ? "GraalVM" : "JDK")
        + " "
        + (version == null ? "?" : version);
  }

  /** the place a Start Center section starts with: empty, to be filled by the user or kubectl */
  public static Snapshot.Via initial(String kind) {
    return new Snapshot.Via(kind);
  }

  /** what is missing before listing, or null */
  public static String check(Snapshot.Via w) {
    if (w.kind.equals("ssh") && (w.host == null || w.host.isBlank())) {
      return "Enter a host.";
    }
    if (w.kind.equals("kubectl")
        && (w.namespace == null
            || w.namespace.isBlank()
            || w.container == null
            || w.container.isBlank())) {
      return "Enter a namespace and a container.";
    }
    return null;
  }

  /** the place in one line: context/namespace/pod, the SSH host, or "This computer" */
  public static String label(Snapshot.Via v) {
    return v.kind.equals("kubectl")
        ? v.context + "/" + v.namespace + "/" + v.pod
        : v.kind.equals("ssh") ? String.valueOf(v.host) : "This computer";
  }

  /**
   * sh: true when dir is a real directory of the user running it, not a link or one another user
   * made (in a shared /tmp, that user could swap the jar in it)
   */
  static String own(String dir) {
    return "[ ! -L " + dir + " ] && [ -O " + dir + " ]";
  }

  /**
   * a pod name changes with every rollout: the pod if it still exists, else one of the same
   * Deployment (name minus -hash-suffix)
   */
  public static String samePod(List<String> pods, String pod) {
    if (pods.contains(pod)) {
      return pod;
    }
    String owner = pod == null ? "" : pod.replaceAll("-[a-z0-9]+-[a-z0-9]+$", "");
    return pods.stream()
        .filter(p -> p.replaceAll("-[a-z0-9]+-[a-z0-9]+$", "").equals(owner))
        .findFirst()
        .orElse(pods.get(0));
  }

  /**
   * the hosts named in an ssh config (Host lines without patterns), to suggest in the Host field
   */
  public static List<String> sshHosts(List<String> configLines) {
    List<String> hosts = new ArrayList<>();
    for (String line : configLines) {
      String[] w = line.trim().split("\\s+");
      if (w.length > 1 && w[0].equalsIgnoreCase("Host")) {
        for (int i = 1; i < w.length; i++) {
          if (!w[i].matches(".*[*?!].*") && !hosts.contains(w[i])) {
            hosts.add(w[i]);
          }
        }
      }
    }
    return hosts;
  }

  /**
   * the packages whose calls are counted by default: the main class's ("com.example.orders.App" ->
   * "com.example.orders")
   */
  public static String include(String mainClass) {
    return mainClass.contains(".") ? mainClass.substring(0, mainClass.lastIndexOf('.')) : mainClass;
  }

  /**
   * what attaching to j means: the pause, and when its JVM options leave something out, the one
   * option set that fixes it (exact inlined times and a silent attach, JDK 21+); empty when j's
   * agent runs
   */
  public static List<String> attachNotes(Jvm j) {
    List<String> n = new ArrayList<>();
    if (j == null || j.port > 0) {
      return n;
    }
    n.add("Attaching pauses the JVM briefly to instrument the loaded classes.");
    if (!j.debugNonSafepoints() || j.warnsOnAttach()) {
      n.add("Start the JVM with " + PREPARE + " for a silent attach with exact times.");
    }
    return n;
  }

  private Targets() {}
}
