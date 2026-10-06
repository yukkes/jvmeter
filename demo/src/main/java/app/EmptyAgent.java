package app;

import java.lang.instrument.Instrumentation;

/**
 * A Java agent that does nothing, for demo/sampling.sh: what -javaagent costs before any profiler
 * code runs.
 */
public class EmptyAgent {
  @SuppressWarnings("unused") // called by the JVM (Premain-Class)
  public static void premain(String args, Instrumentation inst) {}
}
