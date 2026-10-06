package jvmeter.core;

import java.util.ArrayList;
import java.util.List;

/** GC data of a session: the snapshot's events, and a recording's as they come. */
public final class GcData {
  public final String collector;
  public final double heapMaxMB;

  /** null when the snapshot has no generation sizes */
  public Snapshot.Generations gen;

  public final List<GcEvent> events = new ArrayList<>();

  GcData(String collector, double heapMaxMB, Snapshot.Generations gen) {
    this.collector = collector;
    this.heapMaxMB = heapMaxMB;
    this.gen = gen;
  }
}
