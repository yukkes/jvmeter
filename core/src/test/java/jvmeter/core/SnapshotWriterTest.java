package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotWriterTest {

  @Test
  void writtenSnapshotLoadsBackWithTheSameData() {
    Session s = new Session(Snapshot.loadSample());
    String json = SnapshotWriter.write(s);
    assertTrue(json.startsWith("{\n  \"format\": \"jvmeter-snapshot/1\""), json.substring(0, 60));
    assertTrue(json.contains("\"findings\""));
    Session back = new Session(Snapshot.parse(json));
    assertEquals(s.tree.root.total, back.tree.root.total);
    assertEquals(s.gc.events.size(), back.gc.events.size());
    assertEquals(s.analyzeGc().throughput, back.analyzeGc().throughput, 1e-9);
    assertEquals(s.threads.get(1).sum("block"), back.threads.get(1).sum("block"));
    assertEquals(s.classes.get(0).count, back.classes.get(0).count);
    // telemetry and class history are written, so a recording keeps them (the sample's are
    // simulated)
    assertEquals(s.tel.t, back.tel.t);
    assertEquals(s.tel.heap, back.tel.heap);
    assertEquals(s.chist.size(), back.chist.size());
    assertEquals(s.chist.get(5).count(), back.chist.get(5).count());
  }

  /**
   * what the agent adds: seconds per thread state (short blocks count), and MaxMetaspaceSize for
   * the Metaspace rule
   */
  @Test
  void agentThreadTotalsAndMetaspaceLimit() {
    Snapshot snap = Snapshot.loadSample();
    snap.threads.get(0).sec = java.util.Map.of("block", 0.4, "run", 99.6);
    snap.gc.generations.metaspaceMaxMB = 96.0;
    Session s = new Session(Snapshot.parse(SnapshotWriter.write(new Session(snap))));
    assertEquals(0.4, s.threads.get(0).sum("block"));
    assertTrue(
        s.analyzeGc().problems.stream().anyMatch(p -> p.title.equals("Metaspace is nearly full")));
    snap.gc.generations.metaspaceMaxMB = null; // no limit (the default): committed is no limit
    assertTrue(
        new Session(snap)
            .analyzeGc().problems.stream()
                .noneMatch(p -> p.title.equals("Metaspace is nearly full")));
  }

  @Test
  void gzipFileRoundTrip(@TempDir Path dir) throws IOException {
    Session s = new Session(Snapshot.loadSample());
    Path f = dir.resolve("s.json.gz");
    SnapshotWriter.write(s, f);
    assertEquals(0x1f, Files.readAllBytes(f)[0] & 0xff);
    assertEquals(s.gc.events.size(), new Session(Snapshot.read(f)).gc.events.size());
  }

  @Test
  void readsPlainJsonAndRejectsBrokenFiles(@TempDir Path dir) throws IOException {
    Session s = new Session(Snapshot.loadSample());
    Path plain = dir.resolve("s.json");
    Files.writeString(plain, SnapshotWriter.write(s));
    assertEquals(s.tree.root.total, new Session(Snapshot.read(plain)).tree.root.total);
    // a cut .gz cannot be read: IOException, as from a missing file
    Path gz = dir.resolve("s.json.gz");
    SnapshotWriter.write(s, gz);
    byte[] b = Files.readAllBytes(gz);
    Path cut = Files.write(dir.resolve("cut.json.gz"), java.util.Arrays.copyOf(b, b.length / 2));
    assertThrows(IOException.class, () -> Snapshot.read(cut));
    assertThrows(IOException.class, () -> Snapshot.read(dir.resolve("missing.json.gz")));
    // not a snapshot: IllegalArgumentException
    for (String bad : new String[] {"", "garbage", "{}"}) {
      Path f = Files.writeString(dir.resolve("bad.json"), bad);
      assertThrows(IllegalArgumentException.class, () -> Snapshot.read(f), bad);
    }
  }
}
