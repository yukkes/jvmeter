package jvmeter.core;

/** A period in one thread state: "run" | "wait" | "block" | "io". */
public final class Seg {
  public final double start;
  public double end;
  public final String state;

  public Seg(double start, double end, String state) {
    this.start = start;
    this.end = end;
    this.state = state;
  }
}
