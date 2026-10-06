package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "Where to look" and the GC rules against the HTML prototype: findings-golden.json is written by
 * gui/compare/findings-golden.js from demo/prototype.html, on the sample and on made-up snapshots
 * that set off every rule.
 */
class FindingsTest {

  @Test
  @SuppressWarnings("unchecked")
  void matchesThePrototype() throws IOException {
    Map<String, Object> g;
    try (InputStream in = FindingsTest.class.getResourceAsStream("/findings-golden.json")) {
      g =
          Jsonb.builder()
              .build()
              .type(Object.class)
              .map()
              .fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    var jsonb = Jsonb.builder().build();
    assertFindings((List<Object>) g.get("sample"), new Session(Snapshot.loadSample()));
    for (Object o : (List<Object>) g.get("cases")) {
      Map<String, Object> c = (Map<String, Object>) o;
      Session s =
          new Session(
              Snapshot.parse(
                  jsonb.type(Object.class).map().toJson((Map<String, Object>) c.get("snapshot"))));
      assertFindings((List<Object>) c.get("findings"), s);
      List<String> exp = new ArrayList<>(), act = new ArrayList<>();
      for (Object p : (List<Object>) c.get("problems")) {
        Map<String, Object> m = (Map<String, Object>) p;
        exp.add(m.get("sev") + " | " + m.get("title") + " | " + m.get("detail"));
      }
      for (GcAnalysis.Problem p : s.analyzeGc().problems) {
        act.add(p.sev + " | " + p.title + " | " + p.detail);
      }
      assertEquals(exp, act);
    }
  }

  @SuppressWarnings("unchecked")
  static void assertFindings(List<Object> golden, Session s) {
    List<String> exp = new ArrayList<>(), act = new ArrayList<>();
    for (Object o : golden) {
      Map<String, Object> f = (Map<String, Object>) o;
      StringBuilder line = new StringBuilder();
      for (Object p : (List<Object>) f.get("line")) {
        line.append('[')
            .append(((List<Object>) p).get(0))
            .append(']')
            .append(((List<Object>) p).get(1));
      }
      exp.add(
          f.get("sev")
              + " "
              + f.get("icon")
              + " | "
              + line
              + " | "
              + f.get("small")
              + " | "
              + f.get("link"));
    }
    for (Findings.Finding f : Findings.of(s, null, null)) {
      StringBuilder line = new StringBuilder();
      f.line.forEach(p -> line.append('[').append(p.kind()).append(']').append(p.text()));
      act.add(f.sev + " " + f.icon + " | " + line + " | " + f.small + " | " + f.link);
    }
    assertEquals(exp, act);
  }
}
