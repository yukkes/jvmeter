package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What runs over ssh, with a fake shell: the jar copied only when it differs, Run as with sudo -n,
 * the agent's errors.
 */
class ConnectorTest {

  @Test
  void sshCopiesTheJarOnceThenListsAndAttaches(@TempDir Path dir) throws Exception {
    Path jar = Files.write(dir.resolve(Targets.JAR), new byte[] {1, 2, 3});
    String sum =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
    List<List<String>> ran = new ArrayList<>();
    boolean[] there = {false};
    Connector.Shell fake =
        (argv, stdin) -> {
          ran.add(argv);
          String cmd = argv.get(argv.size() - 1);
          if (cmd.contains("sha256sum")) {
            return new Connector.Result(0, there[0] ? sum + "  jar\n" : "", "");
          }
          if (cmd.contains("cat >")) {
            there[0] = stdin != null && stdin.length == 3;
            return new Connector.Result(0, "", "");
          }
          if (cmd.contains(" list")) {
            return new Connector.Result(
                0,
                "[{\"pid\":42,\"name\":\"com.example.App\",\"jvm\":\"OpenJDK"
                    + " 21.0.8\",\"args\":\"-Xmx1g\",\"port\":0}]",
                "");
          }
          if (cmd.contains(" attach ")) {
            return new Connector.Result(0, "{\"port\":38211,\"token\":\"t0k\"}", "");
          }
          return new Connector.Result(1, "", "unexpected");
        };
    Snapshot.Via w = new Snapshot.Via("ssh");
    w.host = "app@prod-api-1";
    w.runAs = "batch";
    Connector c = new Connector(w, jar, fake);
    Targets.Jvm j = c.list().get(0);
    assertEquals(42, j.pid);
    assertEquals(
        List.of(
            "ssh",
            "-o",
            "BatchMode=yes",
            "app@prod-api-1",
            "--",
            "sudo -n -u batch sh -c 'umask 077 && mkdir -p ~batch/.jvmeter && [ ! -L"
                + " ~batch/.jvmeter ] && [ -O ~batch/.jvmeter ] && cat >"
                + " ~batch/.jvmeter/jvmeter-agent.jar.tmp && mv -f"
                + " ~batch/.jvmeter/jvmeter-agent.jar.tmp ~batch/.jvmeter/jvmeter-agent.jar'"),
        ran.get(1));
    Connector.Endpoint e = c.attach(j, "com.example");
    assertEquals(new Connector.Endpoint(38211, "t0k"), e);
    assertEquals(
        "sudo -n -u batch sh -c '[ -O /proc/42 ] || { echo \"{\\\"error\\\": \\\"The JVM runs as"
            + " $(stat -c %U /proc/42), not as $(id -un): set Run as to its user.\\\"}\"; exit 1; };"
            + " /proc/42/exe -jar ~batch/.jvmeter/jvmeter-agent.jar attach 42"
            + " '\\''include=com.example'\\'''",
        ran.get(ran.size() - 1).get(5));
    // a new Connector finds the same jar there and does not copy it again
    ran.clear();
    new Connector(w, jar, fake).list();
    assertTrue(ran.stream().noneMatch(a -> a.get(a.size() - 1).contains("cat >")));
  }

  /**
   * whatever holds the port without knowing the token (a local user who bound it before ssh did)
   * gets a nonce, never the token or a proof it could reuse
   */
  @Test
  void theTokenGoesOnlyToTheAgent() throws Exception {
    String token = Live.nonce();
    try (java.net.ServerSocket squatter = loopback()) {
      var got = new java.util.concurrent.CompletableFuture<String>();
      Thread t =
          new Thread(
              () -> {
                try (java.net.Socket s = squatter.accept()) {
                  String nonce = Live.line(s, System.nanoTime() + 5_000_000_000L);
                  s.getOutputStream().write(("not-a-proof " + nonce + "\n").getBytes());
                  s.setSoTimeout(1000);
                  got.complete(nonce + new String(s.getInputStream().readAllBytes()));
                } catch (IOException e) {
                  got.complete("");
                }
              });
      t.start();
      IOException e =
          assertThrows(
              IOException.class,
              () -> new AgentClient(new int[] {squatter.getLocalPort()}, token, m -> {}, w -> {}));
      assertTrue(e.getMessage().contains("the token was not sent"), e.getMessage());
      String seen = got.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(32, seen.length(), seen); // only the client's nonce
      assertTrue(!seen.contains(token));
    }
  }

  /** ::1 if it is there: some systems (WSL) accept no IPv4 on a Java socket bound to 127.0.0.1 */
  private static java.net.ServerSocket loopback() throws IOException {
    try {
      return new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1"));
    } catch (IOException noIpv6) {
      return new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
    }
  }

  @Test
  void errorsSayWhy() {
    Snapshot.Via w = new Snapshot.Via("local");
    Connector c =
        new Connector(
            w,
            Path.of(Targets.JAR),
            (argv, stdin) ->
                new Connector.Result(
                    1, "{\"error\":\"AttachNotSupportedException: no jdk.attach\"}", ""));
    IOException e = assertThrows(IOException.class, c::list);
    assertEquals("AttachNotSupportedException: no jdk.attach", e.getMessage());
    Connector s =
        new Connector(
            w,
            Path.of(Targets.JAR),
            (argv, stdin) -> new Connector.Result(255, "", "Host key verification failed.\n"));
    assertEquals(
        "Host key verification failed.", assertThrows(IOException.class, s::list).getMessage());
  }
}
