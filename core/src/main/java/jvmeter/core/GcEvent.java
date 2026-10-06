package jvmeter.core;

import io.avaje.jsonb.Json;

/** One GC: built from JFR jdk.GarbageCollection + jdk.GCHeapSummary. */
@Json
public final class GcEvent {
  /** seconds since the recording started */
  public double t;

  /** "Young" | "Mixed" | "Full" */
  public String name;

  public String cause;
  public double pauseMs;
  public double beforeMB;
  public double afterMB;
  public Double oldAfterMB;
  public Double metaspaceMB;

  /** run by the GUI's Run GC, not by the application */
  public Boolean manual;

  public boolean isManual() {
    return Boolean.TRUE.equals(manual);
  }

  public String causeLabel() {
    return isManual() ? "System.gc() (Run GC)" : cause;
  }
}
