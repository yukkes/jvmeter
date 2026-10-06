package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Start Center's attach notes match the prototype's: targets-golden.json holds attachNotes() of
 * demo/prototype.html (written by running it in Chromium).
 */
class TargetsTest {

  static Map<String, Object> golden() throws IOException {
    try (InputStream in = TargetsTest.class.getResourceAsStream("/targets-golden.json")) {
      return Jsonb.builder()
          .build()
          .type(Object.class)
          .map()
          .fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** the prototype's sample values, for commands that print the place they run in */
  static Snapshot.Via sample(String kind) {
    Snapshot.Via w = Targets.initial(kind);
    if (kind.equals("ssh")) {
      w.host = "app@prod-api-1";
      w.runAs = "";
    } else if (kind.equals("kubectl")) {
      w.context = "prod-tokyo";
      w.namespace = "orders";
      w.pod = "orders-api-7f9c6d-x2x4q";
      w.container = "app";
    }
    return w;
  }

  /** the prototype's sample JVMs (TARGETS in demo/prototype.html) */
  static Targets.Jvm jvm(String kind, long pid) {
    return List.of(
            new Targets.Jvm(
                34567,
                "com.example.orders.OrderServiceApp",
                "OpenJDK 21.0.8",
                "-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
                    + " -javaagent:jvmeter-agent.jar=port=7091",
                7091),
            new Targets.Jvm(41877, "app.Mix", "OpenJDK 25", "-Xmx512m", 0),
            new Targets.Jvm(
                40212,
                "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                "OpenJDK 17.0.12",
                "-Xmx2g -Dfile.encoding=UTF-8",
                0),
            new Targets.Jvm(
                2211,
                "com.example.orders.OrderServiceApp",
                "OpenJDK 21.0.8",
                "-Xms1g -Xmx1g " + Targets.PREPARE,
                0),
            new Targets.Jvm(
                1,
                "com.example.orders.OrderServiceApp",
                "OpenJDK 21.0.8",
                "-XX:MaxRAMPercentage=75",
                0))
        .stream()
        .filter(j -> j.pid == pid)
        .findFirst()
        .orElseThrow();
  }

  static final List<String> PODS =
      List.of("orders-api-7f9c6d-x2x4q", "orders-api-7f9c6d-k8m1z", "orders-worker-5b8d4-q7tw2");

  @Test
  void attachNotesMatchThePrototype() throws IOException {
    Map<String, Object> g = golden();
    // what attaching means for each JVM: the pause always; the PREPARE line when the JVM's options
    // leave out exact times or a silent attach
    assertEquals(g.get("notesUnprepared21"), Targets.attachNotes(jvm("kubectl", 1)));
    assertEquals(g.get("notesPrepared21"), Targets.attachNotes(jvm("ssh", 2211)));
    assertEquals(g.get("notesJdk17"), Targets.attachNotes(jvm("local", 40212)));
    assertEquals(g.get("notesRunning"), Targets.attachNotes(jvm("local", 34567)));
  }

  @Test
  void checksAndLabels() {
    Snapshot.Via ssh = sample("ssh"), k8s = sample("kubectl");
    assertNull(Targets.check(ssh));
    ssh.host = " ";
    assertEquals("Enter a host.", Targets.check(ssh));
    k8s.container = "";
    assertEquals("Enter a namespace and a container.", Targets.check(k8s));
    assertEquals("prod-tokyo/orders/orders-api-7f9c6d-x2x4q", Targets.label(sample("kubectl")));
    assertEquals(
        List.of("This computer", "app@prod-api-1"),
        List.of(Targets.label(sample("local")), Targets.label(sample("ssh"))));
    // empty at first: filled by the user (ssh) or by what kubectl reads (loadKube)
    assertEquals("Enter a host.", Targets.check(Targets.initial("ssh")));
    assertEquals("Enter a namespace and a container.", Targets.check(Targets.initial("kubectl")));
  }

  @Test
  void podOfTheSameDeploymentAndSshConfigHosts() {
    assertEquals("orders-api-7f9c6d-x2x4q", Targets.samePod(PODS, "orders-api-7f9c6d-x2x4q"));
    assertEquals(
        "orders-api-7f9c6d-x2x4q",
        Targets.samePod(PODS, "orders-api-5d4c3b-zzzzz")); // after a rollout
    assertEquals("orders-worker-5b8d4-q7tw2", Targets.samePod(PODS, "orders-worker-1a2b3-xxxxx"));
    assertEquals(
        "orders-api-7f9c6d-x2x4q",
        Targets.samePod(PODS, "billing-6c7d8-aaaaa")); // gone: the first pod
    assertEquals(
        List.of("prod-api-1", "prod-api-2", "bastion"),
        Targets.sshHosts(
            List.of(
                "Host prod-api-1 prod-api-2",
                "  HostName 10.0.0.5",
                "host bastion",
                "Host *.internal !x",
                "Host *")));
  }
}
