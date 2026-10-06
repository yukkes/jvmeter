package jvmeter.core;

import io.avaje.jsonb.Jsonb;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs jvmeter-agent.jar where a JVM runs (docs/targets.md): lists the JVMs, attaches, and connects
 * to the agent. Commands are argument lists, run by a {@link Shell} (the real one starts processes;
 * tests use a fake one).
 */
public final class Connector implements Closeable {

  public interface Shell {
    /** runs argv to its end; stdin: bytes to feed it, or null */
    Result run(List<String> argv, byte[] stdin) throws IOException;

    /** starts a command that keeps running (a port forward) */
    default Process start(List<String> argv) throws IOException {
      return new ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }
  }

  public record Result(int code, String out, String err) {}

  /** where the agent listens, and the token it expects first */
  public record Endpoint(int port, String token) {}

  /** starts processes; a command that runs longer than a minute is stopped */
  public static final Shell SYSTEM =
      (argv, stdin) -> {
        Process p = new ProcessBuilder(argv).start();
        CompletableFuture<String> out = read(p.getInputStream()), err = read(p.getErrorStream());
        try (var in = p.getOutputStream()) {
          if (stdin != null) {
            in.write(stdin);
          }
        }
        try {
          if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(-1, "", "No answer in 60 s: " + String.join(" ", argv));
          }
          return new Result(p.exitValue(), out.join(), err.join());
        } catch (InterruptedException e) {
          p.destroyForcibly();
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      };

  private static CompletableFuture<String> read(InputStream in) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            return "";
          }
        });
  }

  private static final Jsonb JSONB = Jsonb.builder().build();

  final Snapshot.Via w;
  private final Path jar;
  private final Shell sh;

  /** jar: the agent jar on this computer */
  public Connector(Snapshot.Via w, Path jar, Shell sh) {
    this.w = w;
    this.jar = jar;
    this.sh = sh;
  }

  /** the java of this GUI: lists JVMs here (any recent JDK can) */
  static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private Process forwarding;
  private boolean copied;

  /**
   * the command line there: this computer runs argv itself; over ssh / kubectl exec it runs in sh
   * -c
   */
  List<String> there(String cmd, boolean stdin) {
    List<String> a = new ArrayList<>();
    if (w.kind.equals("ssh")) {
      // BatchMode: no terminal, so a password prompt fails at once; sudo -n likewise
      a.addAll(List.of("ssh", "-o", "BatchMode=yes", w.host, "--"));
      a.add((blank(w.runAs) ? "" : "sudo -n -u " + w.runAs + " ") + "sh -c " + quote(cmd));
    } else {
      a.addAll(kubectl(w.context, w.namespace, "exec"));
      if (stdin) {
        a.add("-i");
      }
      a.addAll(List.of(w.pod, "-c", w.container, "--", "sh", "-c", cmd));
    }
    return a;
  }

  /** kubectl --context context -n namespace args */
  static List<String> kubectl(String context, String namespace, String... args) {
    List<String> a = new ArrayList<>(List.of("kubectl", "--context", context, "-n", namespace));
    a.addAll(List.of(args));
    return a;
  }

  public static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** 'it''s' for sh */
  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  /** where the jar goes there: a directory only the JVM's user can write (docs/targets.md) */
  String remoteJar() {
    return w.kind.equals("kubectl")
        ? "/tmp/jvmeter/" + Targets.JAR
        : "~" + (blank(w.runAs) ? "" : w.runAs) + "/.jvmeter/" + Targets.JAR;
  }

  /** copies the agent jar there, unless the same one (by SHA-256) is there already */
  void copyJar() throws IOException {
    if (copied) {
      return;
    }
    String j = remoteJar(), dir = j.substring(0, j.lastIndexOf('/'));
    byte[] bytes = java.nio.file.Files.readAllBytes(jar);
    String sum;
    try {
      sum =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    // a directory someone else made (in /tmp) could have its jar swapped: refuse it
    Result has =
        sh.run(
            there(
                "if [ -e "
                    + dir
                    + " ] || [ -L "
                    + dir
                    + " ]; then "
                    + Targets.own(dir)
                    + " || { echo '{\"error\": \""
                    + dir
                    + " is a link or belongs to another user: remove it\"}'; exit 1; }; fi; sha256sum "
                    + j
                    + " 2>/dev/null || true",
                false),
            null);
    if (has.code() != 0 || !has.out().startsWith(sum)) {
      ok(has); // ssh / kubectl itself failed: its message says why (host key, auth, RBAC)
      ok(
          sh.run(
              there(
                  "umask 077 && mkdir -p "
                      + dir
                      + " && "
                      + Targets.own(dir)
                      + " && cat > "
                      + j
                      + ".tmp && mv -f "
                      + j
                      + ".tmp "
                      + j,
                  true),
              bytes));
    }
    copied = true;
  }

  /** the JVMs there, with the port and token of an agent that already runs */
  public List<Targets.Jvm> list() throws IOException {
    String out;
    if (w.kind.equals("local")) {
      // this process is hidden too: the GUI would be offered to attach to itself
      out =
          ok(
              sh.run(
                  List.of(
                      java(),
                      "-jar",
                      jar.toString(),
                      "list",
                      String.valueOf(ProcessHandle.current().pid())),
                  null));
    } else {
      copyJar();
      out = ok(sh.run(there("java -jar " + remoteJar() + " list", false), null));
    }
    try {
      List<Targets.Jvm> l = JSONB.type(Targets.Jvm.class).list().fromJson(out);
      return l != null ? l : new ArrayList<>();
    } catch (RuntimeException e) {
      throw new IOException("Unexpected answer from jvmeter-agent.jar list: " + out.strip());
    }
  }

  /**
   * loads the agent into j with the target's own java (the Attach API of the same version), or
   * finds the one running. Only a JVM of the user that runs the command: another user's java would
   * run with this user's rights (root's, say), and that user could have put anything there.
   */
  public Endpoint attach(Targets.Jvm j, String include) throws IOException {
    if (j.port > 0 && j.token != null) {
      return new Endpoint(j.port, j.token);
    }
    String inc = blank(include) ? null : "include=" + include;
    List<String> argv;
    if (w.kind.equals("local")) {
      var info = ProcessHandle.of(j.pid).map(ProcessHandle::info);
      String me = System.getProperty("user.name"),
          user = info.flatMap(ProcessHandle.Info::user).orElse(me);
      if (!user.equals(me) && !user.endsWith("\\" + me)) { // Windows: DOMAIN backslash user
        throw new IOException(
            "The JVM runs as " + user + ", not as " + me + ": start jvmeter as " + user + ".");
      }
      String java = info.flatMap(ProcessHandle.Info::command).orElse(java());
      argv =
          new ArrayList<>(List.of(java, "-jar", jar.toString(), "attach", String.valueOf(j.pid)));
      if (inc != null) {
        argv.add(inc);
      }
    } else {
      copyJar();
      argv =
          there(
              "[ -O /proc/"
                  + j.pid
                  + " ] || { echo \"{\\\"error\\\": \\\"The JVM runs as $(stat -c %U /proc/"
                  + j.pid
                  + "), not as $(id -un): set Run as to its user.\\\"}\"; exit 1; }; "
                  + "/proc/"
                  + j.pid
                  + "/exe -jar "
                  + remoteJar()
                  + " attach "
                  + j.pid
                  + (inc != null ? " " + quote(inc) : ""),
              false);
    }
    String out = ok(sh.run(argv, null));
    try {
      Map<String, Object> m = JSONB.type(Object.class).map().fromJson(out);
      return new Endpoint(((Number) m.get("port")).intValue(), (String) m.get("token"));
    } catch (RuntimeException e) {
      throw new IOException("Unexpected answer from jvmeter-agent.jar attach: " + out.strip());
    }
  }

  /**
   * the output of a command that succeeded; otherwise the error it printed ({"error": ...} from the
   * agent jar, or stderr)
   */
  static String ok(Result r) throws IOException {
    if (r.code() == 0) {
      return r.out();
    }
    String msg = r.err().strip();
    try {
      Map<String, Object> m = JSONB.type(Object.class).map().fromJson(r.out());
      if (m != null && m.get("error") != null) {
        msg = String.valueOf(m.get("error"));
      }
    } catch (RuntimeException notJson) {
      // stderr says it
    }
    throw new IOException(msg.isEmpty() ? "Exit code " + r.code() : msg);
  }

  /**
   * The local ports that may reach the agent's port there, to try in turn: the same port here;
   * otherwise ssh -L or kubectl port-forward from free local ports, running until {@link #close}.
   * Waits until they accept connections. ssh forwards twice, to 127.0.0.1 and to ::1: the agent
   * listens on both, but some systems accept only one of them, and sshd resolves "localhost" to one
   * address. The local ports are bound on 127.0.0.1 only, so ssh / kubectl fail when another
   * process took the port first, instead of falling back to ::1 and leaving 127.0.0.1 to it.
   */
  public int[] forward(int port) throws IOException {
    if (w.kind.equals("local")) {
      return new int[] {port};
    }
    int local = free(), local6 = free();
    List<String> argv =
        w.kind.equals("ssh")
            ? List.of(
                "ssh",
                "-N",
                "-o",
                "BatchMode=yes",
                "-o",
                "ExitOnForwardFailure=yes",
                "-L",
                "127.0.0.1:" + local + ":127.0.0.1:" + port,
                "-L",
                "127.0.0.1:" + local6 + ":[::1]:" + port,
                w.host)
            : kubectl(
                w.context,
                w.namespace,
                "port-forward",
                "--address",
                "127.0.0.1",
                "pod/" + w.pod,
                local + ":" + port);
    int[] ports = w.kind.equals("ssh") ? new int[] {local, local6} : new int[] {local};
    forwarding = sh.start(argv);
    for (int i = 0; i < 100; i++) {
      if (!forwarding.isAlive()) {
        String err =
            new String(forwarding.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        throw new IOException(
            "The port forward ended: " + (err.isEmpty() ? String.join(" ", argv) : err));
      }
      try (java.net.Socket s = new java.net.Socket()) {
        s.connect(
            new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), local), 200);
        return ports;
      } catch (IOException notYet) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
    }
    close();
    throw new IOException("The port forward did not start in 10 s: " + String.join(" ", argv));
  }

  // ---------- what kubectl knows, for the Start Center's fields ----------

  /** the kubeconfig's contexts, the current one first */
  public static List<String> contexts(Shell sh) throws IOException {
    List<String> l =
        new ArrayList<>(
            lines(
                ok(sh.run(List.of("kubectl", "config", "get-contexts", "-o", "name"), null)), ""));
    Result cur = sh.run(List.of("kubectl", "config", "current-context"), null);
    if (cur.code() == 0 && l.remove(cur.out().strip())) {
      l.add(0, cur.out().strip());
    }
    return l;
  }

  public static List<String> namespaces(Shell sh, String context) throws IOException {
    return lines(
        ok(
            sh.run(
                List.of("kubectl", "--context", context, "get", "namespaces", "-o", "name"), null)),
        "namespace/");
  }

  /** the running pods */
  public static List<String> pods(Shell sh, String context, String namespace) throws IOException {
    return lines(
        ok(
            sh.run(
                kubectl(
                    context,
                    namespace,
                    "get",
                    "pods",
                    "--field-selector=status.phase=Running",
                    "-o",
                    "name"),
                null)),
        "pod/");
  }

  public static List<String> containers(Shell sh, String context, String namespace, String pod)
      throws IOException {
    String out =
        ok(
            sh.run(
                kubectl(
                    context,
                    namespace,
                    "get",
                    "pod",
                    pod,
                    "-o",
                    "jsonpath={.spec.containers[*].name}"),
                null));
    return lines(out.replace(' ', '\n'), "");
  }

  private static List<String> lines(String out, String prefix) {
    List<String> l = new ArrayList<>();
    for (String line : out.split("\n")) {
      String t = line.strip();
      if (!t.isEmpty()) {
        l.add(t.startsWith(prefix) ? t.substring(prefix.length()) : t);
      }
    }
    return l;
  }

  private static int free() throws IOException {
    try (java.net.ServerSocket s =
        new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  /**
   * The agent jar bundled with the GUI, as a file a JVM can load: ~/.jvmeter/jvmeter-agent.jar,
   * rewritten when it differs. The directory is this user's only: a jar that someone else could
   * replace would run inside the profiled JVM.
   */
  public static Path localJar(byte[] jar) throws IOException {
    Path dir = Path.of(System.getProperty("user.home"), ".jvmeter");
    try {
      Files.createDirectories(
          dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (UnsupportedOperationException windows) {
      Files.createDirectories(dir);
    }
    Path f = dir.resolve(Targets.JAR);
    if (!Files.exists(f) || !Arrays.equals(Files.readAllBytes(f), jar)) {
      Path tmp = Files.createTempFile(dir, "agent-", ".jar");
      Files.write(tmp, jar);
      Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    return f;
  }

  @Override
  public void close() {
    if (forwarding != null) {
      forwarding.destroy();
      forwarding = null;
    }
  }
}
