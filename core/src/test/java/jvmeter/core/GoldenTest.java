package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compares the Java port with the HTML prototype (demo/prototype.html) on the bundled sample.
 * golden.json was produced by running the prototype's JavaScript in Node with TZ=Asia/Tokyo.
 */
class GoldenTest {

  static Map<String, Object> g;
  static Session s;

  @BeforeAll
  static void load() throws IOException {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    try (InputStream in = GoldenTest.class.getResourceAsStream("/golden.json")) {
      g =
          Jsonb.builder()
              .build()
              .type(Object.class)
              .map()
              .fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    s = new Session(Snapshot.loadSample());
  }

  static double d(Object o) {
    return ((Number) o).doubleValue();
  }

  static List<Object> list(String k) {
    return arr(g.get(k));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object o) {
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  static List<Object> arr(Object o) {
    return (List<Object>) o;
  }

  @Test
  void callTree() {
    assertEquals(d(g.get("rootTotal")), s.tree.root.total);
    List<CallTree.HotSpot> hot = new ArrayList<>(s.tree.hot);
    hot.sort((a, b) -> Double.compare(b.self, a.self));
    List<Object> exp = list("hot");
    assertEquals(exp.size(), hot.size());
    for (int i = 0; i < hot.size(); i++) {
      Map<String, Object> e = obj(exp.get(i));
      assertEquals(e.get("name"), hot.get(i).name);
      assertEquals(d(e.get("self")), hot.get(i).self);
      assertEquals(d(e.get("calls")), hot.get(i).calls);
      assertEquals(d(e.get("total")), hot.get(i).total);
    }
    CallTree.MethodInfo m = s.tree.methodInfo("com.example.orders.PriceCalculator.calculate");
    Map<String, Object> em = obj(g.get("methodCalc"));
    assertEquals(d(em.get("total")), m.total());
    assertEquals(d(em.get("calls")), m.calls());
    assertEquals(arr(em.get("callers")).size(), m.callers().size());
    assertEquals(obj(arr(em.get("callees")).get(0)).get("name"), m.callees().get(0).name());
  }

  @Test
  void telemetryAndThreads() {
    assertSeries(list("telCpu"), s.tel.cpu);
    assertSeries(list("telThreads"), s.tel.threads);
    assertSeries(list("telHeap"), s.tel.heap);
    List<Object> segs = list("segs");
    for (int i = 0; i < segs.size(); i++) {
      List<Object> ts = arr(segs.get(i));
      assertEquals(ts.size(), s.threads.get(i).segs.size(), "thread " + i);
      for (int k = 0; k < ts.size(); k++) {
        List<Object> sg = arr(ts.get(k));
        Seg mine = s.threads.get(i).segs.get(k);
        assertEquals(d(sg.get(0)), mine.start);
        assertEquals(d(sg.get(1)), mine.end);
        assertEquals(sg.get(2), mine.state);
      }
    }
    assertEquals(d(g.get("chistLen")), s.chist.size());
    Map<String, Object> c0 = obj(g.get("chist0"));
    assertEquals(
        d(obj(c0.get("count")).get("byte[]")), (double) s.chist.get(0).count().get("byte[]"));
    assertEquals(
        d(obj(c0.get("bytes")).get("com.example.orders.Order")),
        (double) s.chist.get(0).bytes().get("com.example.orders.Order"));
  }

  static void assertSeries(List<Object> exp, List<Double> act) {
    assertEquals(exp.size(), act.size());
    for (int i = 0; i < exp.size(); i++) {
      assertEquals(d(exp.get(i)), act.get(i), 1e-9, "index " + i);
    }
  }

  @Test
  void gcEventsAndAnalysis() {
    List<Object> ev = list("events");
    assertEquals(ev.size(), s.gc.events.size());
    for (int i = 0; i < ev.size(); i++) {
      Map<String, Object> e = obj(ev.get(i));
      GcEvent m = s.gc.events.get(i);
      assertEquals(d(e.get("t")), m.t);
      assertEquals(e.get("name"), m.name);
      assertEquals(e.get("cause"), m.cause);
      assertEquals(d(e.get("pauseMs")), m.pauseMs);
      assertEquals(d(e.get("beforeMB")), m.beforeMB);
      assertEquals(d(e.get("afterMB")), m.afterMB);
      assertEquals(d(e.get("oldAfterMB")), m.oldAfterMB);
      assertEquals(d(e.get("metaspaceMB")), m.metaspaceMB);
    }
    assertEquals(g.get("gcLog"), GcAnalysis.gcLog(s.gc));
    GcAnalysis a = s.analyzeGc();
    Map<String, Object> ea = obj(g.get("analysis"));
    assertEquals(d(ea.get("throughput")), a.throughput, 1e-9);
    assertEquals(d(ea.get("avg")), a.avg, 1e-9);
    assertEquals(d(ea.get("p95")), a.p95);
    assertEquals(d(ea.get("allocRate")), a.allocRate, 1e-9);
    assertEquals(d(ea.get("promoRate")), a.promoRate, 1e-9);
    assertEquals(d(ea.get("slopePerMin")), a.slopePerMin, 1e-9);
    assertEquals(d(ea.get("reg0")), a.regAt(0), 1e-9);
    List<Object> hist = arr(ea.get("hist"));
    for (int i = 0; i < hist.size(); i++) {
      assertEquals(d(hist.get(i)), a.hist.get(i).count());
    }
    List<Object> probs = arr(ea.get("problems"));
    assertEquals(probs.size(), a.problems.size());
    for (int i = 0; i < probs.size(); i++) {
      Map<String, Object> p = obj(probs.get(i));
      assertEquals(p.get("title"), a.problems.get(i).title);
      assertEquals(p.get("detail"), a.problems.get(i).detail);
      assertEquals(p.get("icon"), a.problems.get(i).icon);
    }
    List<Object> heapAt = list("heapAt");
    double[] ts = {0, 5.5, 30, 54.5, 60, 119, 120};
    for (int i = 0; i < ts.length; i++) {
      assertEquals(d(heapAt.get(i)), s.heapAt(ts[i]), 1e-9);
    }
  }

  @Test
  void formatting() {
    Map<String, Object> f = obj(g.get("fmt"));
    assertEquals(
        arr(f.get("tod")),
        List.of(Fmt.tod(s.startMs, 0), Fmt.tod(s.startMs, 59.6), Fmt.tod(s.startMs, 120)));
    List<Object> ticks = arr(f.get("ticks"));
    List<Double> mine = Fmt.todTicks(s.startMs, 1, 120, 20);
    assertEquals(ticks.size(), mine.size());
    assertEquals(
        arr(f.get("pause")),
        List.of(Fmt.fmtPause(0.5), Fmt.fmtPause(12.345), Fmt.fmtPause(197.4), Fmt.fmtPause(1500)));
    assertEquals(
        arr(f.get("avg")),
        List.of(Fmt.fmtAvg(0.00123), Fmt.fmtAvg(0.0456), Fmt.fmtAvg(1.27), Fmt.fmtAvg(15300)));
    assertEquals(arr(f.get("bytes")), List.of(Fmt.fmtBytes(1000), Fmt.fmtBytes(39845000)));
    assertEquals(arr(f.get("ms")), List.of(Fmt.fmtMs(450), Fmt.fmtMs(7550), Fmt.fmtMs(15300)));
  }
}
