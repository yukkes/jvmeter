package jvmeter.core;

import java.util.List;
import java.util.Map;

/** A thread with its state timeline and current stack. */
public final class ThreadTrack {
  public final String name;
  public final List<Seg> segs;
  public final List<String> stack;

  /**
   * seconds per state from the agent (sampled every 100 ms, so blocks shorter than a second count);
   * null: from the segments
   */
  public final Map<String, Double> sec;

  ThreadTrack(String name, List<Seg> segs, List<String> stack, Map<String, Double> sec) {
    this.name = name;
    this.segs = segs;
    this.stack = stack;
    this.sec = sec;
  }

  /** seconds spent in a state ("run" | "wait" | "block" | "io") */
  public double sum(String state) {
    if (sec != null) {
      return sec.getOrDefault(state, 0.0);
    }
    double s = 0;
    for (Seg g : segs) {
      if (g.state.equals(state)) {
        s += g.end - g.start;
      }
    }
    return s;
  }
}
