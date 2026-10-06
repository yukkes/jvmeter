package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Older data is coarser, the same as in demo/prototype.html: retention-golden.json holds the
 * prototype's compactTel, compactHist and compactSegs on long simulated recordings (run in
 * Chromium), and {@link Sim} simulates the same recordings here.
 */
class RetentionTest {

  /** the prototype's simulated recording: its pseudo-random numbers and telemetry */
  static final class Sim {
    private long s;
    private double lastHeap = 300;
    final Telemetry tel = new Telemetry();

    Sim(long seed) {
      s = seed & 0xFFFFFFFFL;
    }

    double next() {
      s = (s * 1664525L + 1013904223L) & 0xFFFFFFFFL;
      return s / 4294967296.0;
    }

    /** second i, ending at sec */
    void second(int i, double sec) {
      double h = lastHeap + 6 + next() * 6;
      if (h > 760) {
        next();
        h = 340 + (i * 0.6) % 120;
      }
      lastHeap = h;
      tel.add(
          sec,
          Math.min(100, 38 + 14 * Math.sin(i / 9.0) + next() * 10),
          h,
          24 + Math.round(next() * 3));
    }

    /** seconds 1..sec, compacted */
    Telemetry seconds(int sec) {
      for (int i = 0; i < sec; i++) {
        second(i, i + 1);
      }
      tel.compact(sec);
      return tel;
    }

    /** a worker thread's states over sec seconds */
    List<Seg> worker(double sec) {
      List<Seg> segs = new ArrayList<>();
      for (double t = 0; t < sec; ) {
        double d = 1 + Math.floor(next() * 8);
        String state = next() < .45 ? "run" : next() < .6 ? "io" : "wait";
        segs.add(new Seg(t, Math.min(sec, t + d), state));
        t += d;
      }
      return segs;
    }
  }

  static Map<String, Object> g;

  @BeforeAll
  static void load() throws IOException {
    try (InputStream in = RetentionTest.class.getResourceAsStream("/retention-golden.json")) {
      g =
          Jsonb.builder()
              .build()
              .type(Object.class)
              .map()
              .fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  static void same(Object exp, List<? extends Number> act) {
    List<Object> e = GoldenTest.arr(exp);
    assertEquals(e.size(), act.size());
    for (int i = 0; i < e.size(); i++) {
      assertEquals(GoldenTest.d(e.get(i)), act.get(i).doubleValue(), 1e-6, "index " + i);
    }
  }

  @Test
  void telemetryOfEightHours() {
    Telemetry t = new Sim(7).seconds(8 * 3600);
    Map<String, Object> e = GoldenTest.obj(g.get("tel"));
    same(e.get("t"), t.t);
    same(e.get("n"), t.n);
    same(e.get("cpu"), t.cpu);
    same(e.get("heap"), t.heap);
    same(e.get("threads"), t.threads);
  }

  @Test
  void telemetryCompactedEverySecond() {
    Sim sim = new Sim(7);
    Telemetry t = sim.seconds(7200);
    for (int s = 7201; s <= 7800; s++) {
      sim.second(s, s);
      t.compact(s);
    }
    Map<String, Object> e = GoldenTest.obj(g.get("telLive"));
    same(e.get("t"), t.t);
    same(e.get("n"), t.n);
    same(e.get("cpu"), t.cpu);
  }

  @Test
  void classHistoryKeepsTheLastOfEachBucket() {
    List<Session.ClassSnap> h = new ArrayList<>();
    for (int s = 10; s <= 30000; s += 10) {
      h.add(new Session.ClassSnap(s, Map.of("a", (long) s), Map.of("a", 2L * s)));
    }
    Retention.history(h, 30000);
    same(g.get("hist"), h.stream().map(e -> e.t()).toList());
  }

  @Test
  void threadSegmentsMergeInsideBuckets() {
    List<Seg> segs = new Sim(3).worker(30000);
    Retention.segs(segs, 30000);
    List<Object> e = GoldenTest.arr(g.get("segs"));
    assertEquals(e.size(), segs.size());
    for (int i = 0; i < e.size(); i++) {
      List<Object> x = GoldenTest.arr(e.get(i));
      assertEquals(GoldenTest.d(x.get(0)), segs.get(i).start, "seg " + i);
      assertEquals(GoldenTest.d(x.get(1)), segs.get(i).end, "seg " + i);
      assertEquals(x.get(2), segs.get(i).state, "seg " + i);
    }
  }
}
