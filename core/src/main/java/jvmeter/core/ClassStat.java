package jvmeter.core;

import io.avaje.jsonb.Json;

/** Instance count and size of one class. */
@Json
public final class ClassStat {
  public String name;
  public long count;
  public long bytes;

  public ClassStat(String name, long count, long bytes) {
    this.name = name;
    this.count = count;
    this.bytes = bytes;
  }
}
