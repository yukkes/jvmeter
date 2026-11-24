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
    String head80 = json.lines().limit(80).collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(head80.contains("\"findings\""));
    assertTrue(head80.contains("GC:"), head80);
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

  /** a recording is plain JSON: readable as it is, about and summary first */
  @Test
  void plainFileRoundTrip(@TempDir Path dir) throws IOException {
    Session s = new Session(Snapshot.loadSample());
    Path f = dir.resolve("s.json");
    SnapshotWriter.write(s, f);
    assertTrue(Files.readString(f).startsWith("{\n  \"format\""));
    assertEquals(s.gc.events.size(), new Session(Snapshot.read(f)).gc.events.size());
  }

  /**
   * a recording whose class names fall at every offset reads back: avaje-jsonb 3.16 misreads some
   * of them when it parses from a stream (a GUI recording that could not be opened again)
   */
  @Test
  void readsBackAtEveryOffset(@TempDir Path dir) throws IOException {
    Session s = new Session(Snapshot.loadSample());
    java.util.Map<String, Long> count = new java.util.HashMap<>();
    for (int i = 0; i < 400; i++) {
      count.put("java.time.format.DateTimeFormatterBuilder$Printer" + i + "[]", (long) i);
    }
    s.chist.add(new Session.ClassSnap(s.chist.get(s.chist.size() - 1).t() + 1, count, count));
    Path f = dir.resolve("s.json");
    for (int pad = 0; pad < 4096; pad += 5) {
      s.snap.target.name = "x".repeat(pad);
      SnapshotWriter.write(s, f);
      assertEquals(pad, Snapshot.read(f).target.name.length(), "offset " + pad);
      assertEquals(pad, Recordings.head(f).target().name.length(), "offset " + pad);
    }
  }

  @Test
  void readsPlainJsonAndRejectsBrokenFiles(@TempDir Path dir) throws IOException {
    Session s = new Session(Snapshot.loadSample());
    Path plain = dir.resolve("s.json");
    Files.writeString(plain, SnapshotWriter.write(s));
    assertEquals(s.tree.root.total, new Session(Snapshot.read(plain)).tree.root.total);
    // a missing file: IOException
    assertThrows(IOException.class, () -> Snapshot.read(dir.resolve("missing.json")));
    // not a snapshot: IllegalArgumentException
    for (String bad : new String[] {"", "garbage", "{}"}) {
      Path f = Files.writeString(dir.resolve("bad.json"), bad);
      assertThrows(IllegalArgumentException.class, () -> Snapshot.read(f), bad);
    }
  }

  @Test
  void aboutReflectsConfiguredSamplingPeriod() {
    Snapshot snap = Snapshot.loadSample();
    snap.cpu.periodMs = 5L;
    String json = SnapshotWriter.write(new Session(snap));
    assertTrue(json.contains("taken every 5 ms"));
  }

  @Test
  void rejectsMalformedNestedStructuresAndClampsGcMetrics() {
    String base =
        "{\"target\":{\"name\":\"a\"},\"cpu\":{\"tree\":{\"name\":\"All\"}},\"memory\":{\"classes\":[]}";
    assertThrows(
        IllegalArgumentException.class,
        () -> Snapshot.parse(base + ",\"threads\":[{\"name\":\"t\",\"segs\":[[1]]}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Snapshot.parse(
                base
                    + ",\"telemetry\":{\"t\":[1,2],\"n\":[1],\"cpu\":[0],\"heap\":[0],\"threads\":[0]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Snapshot.parse(base + ",\"gc\":{\"events\":[{\"t\":1}]}}"));

    GcData g = new GcData("G1", 0, null);
    GcEvent e = new GcEvent();
    e.t = 1;
    e.name = "Young";
    e.cause = "G1 Evacuation Pause";
    e.pauseMs = 5000;
    e.beforeMB = 10;
    e.afterMB = 5;
    e.oldAfterMB = 20.0;
    g.events.add(e);
    GcAnalysis a = GcAnalysis.of(g, 1);
    assertEquals(0.0, a.throughput);
    assertEquals(0.0, a.peakYoung);
    assertTrue(a.problems.stream().noneMatch(p -> p.title.equals(GcAnalysis.HEAP_GROWS)));

    Session s = new Session(Snapshot.loadSample());
    Session.ClassSnap before =
        new Session.ClassSnap(
            0,
            java.util.Map.of("app.Disappeared", 50L),
            java.util.Map.of("app.Disappeared", 4000L));
    assertTrue(
        s.abRows(before, null).stream()
            .anyMatch(r -> r.c().name.equals("app.Disappeared") && r.diff() == -50));
  }
}
