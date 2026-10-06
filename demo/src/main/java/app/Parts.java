package app;

import java.util.HashMap;
import java.util.Map;

/**
 * The parts of {@link Load}, each timed alone (the best of 15 runs, in ms), for demo/sampling.sh:
 * which of them a profiler slows down by being loaded, before it records anything. "pollute" first
 * uses HashMap with keys of six classes, as a profiler's own startup code does.
 */
public class Parts {
  static long sink;
  static final Object LOCK = new Object();

  static double time(Runnable r) {
    double best = Double.MAX_VALUE;
    for (int k = 0; k < 15; k++) {
      long t0 = System.nanoTime();
      r.run();
      best = Math.min(best, (System.nanoTime() - t0) / 1e6);
    }
    return best;
  }

  public static void main(String[] a) {
    int n = 2_000_000;
    if (a.length > 0 && a[0].equals("pollute")) {
      Object[] keys = {"s", 1L, Parts.class, new Object(), Thread.State.NEW, 2.0};
      for (int i = 0; i < 200_000; i++) {
        Map<Object, Object> m = new HashMap<>();
        for (Object k : keys) {
          m.put(k, k);
        }
        sink += m.size();
      }
    }
    Runnable concat =
        () -> {
          for (int i = 0; i < n; i++) {
            sink += ("item-" + i).length();
          }
        };
    Runnable map =
        () -> {
          for (int i = 0; i < n / 8; i++) {
            Map<Integer, Integer> m = new HashMap<>();
            for (int k = 0; k < 8; k++) {
              m.put(k + i, k);
            }
            sink += m.size();
          }
        };
    Runnable lock =
        () -> {
          for (int i = 0; i < n; i++) {
            synchronized (LOCK) {
              sink++;
            }
          }
        };
    Runnable toString =
        () -> {
          for (int i = 0; i < n; i++) {
            sink += Integer.toString(i).hashCode();
          }
        };
    for (int w = 0; w < 3; w++) {
      time(concat);
      time(map);
      time(lock);
      time(toString);
    }
    System.out.printf("> %-24s %8.1f ms%n", "string concatenation", time(concat));
    System.out.printf("> %-24s %8.1f ms%n", "HashMap", time(map));
    System.out.printf("> %-24s %8.1f ms%n", "synchronized", time(lock));
    System.out.printf("> %-24s %8.1f ms%n", "Integer.toString", time(toString));
  }
}
