package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jvmeter.core.GcEvent;
import org.junit.jupiter.api.Test;

class JfrRecorderTest {

  /** a System.gc() GC is Run GC's only while Run GC runs; the application's own are not */
  @Test
  void runGcMarksOnlyItsOwnGcs() {
    JfrRecorder r = new JfrRecorder(Duration.ofMillis(1), "app");
    Instant before = Instant.now().minusSeconds(1);
    Instant[] w = r.runGc();
    assertTrue(r.byRunGc(Instant.now()), "while it runs");
    r.runGcDone(w);
    assertTrue(r.byRunGc(w[1]), "as it ends");
    assertFalse(r.byRunGc(before), "before");
    assertFalse(r.byRunGc(w[1].plusSeconds(1)), "after");
  }

  /** gcEvents returns fresh rounded copies and tracks sent gcIds without losing same-ms events */
  @Test
  void gcEventsAreNonDestructiveAndDeduplicatedById() {
    JfrRecorder r = new JfrRecorder(Duration.ofMillis(1), "app");
    r.start();
    try {
      System.gc();
      Set<Long> sent = new HashSet<>();
      List<GcEvent> first = r.gcEvents(sent);
      List<GcEvent> again = r.gcEvents(sent);
      assertTrue(again.isEmpty());
      // full snapshot returns the same count on repeated calls
      assertEquals(r.gcEvents().size(), r.gcEvents().size());
      assertTrue(first.size() <= r.gcEvents().size());
    } finally {
      r.stop();
    }
  }
}
