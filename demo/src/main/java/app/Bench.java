package app;

import java.util.Arrays;

/**
 * Benchmark harness: runs fibRecursive(n) R times and prints the median/min. -Dbench.sleep=ms
 * sleeps between rounds.
 */
public class Bench {
  static final long SLEEP = Long.getLong("bench.sleep", 0);

  public static void main(String[] a) throws Exception {
    int n = a.length > 0 ? Integer.parseInt(a[0]) : 35;
    int r = a.length > 1 ? Integer.parseInt(a[1]) : 15;
    for (int i = 0; i < 5; i++) Fib.fibRecursive(30);
    double[] t = new double[r];
    long sink = 0;
    for (int i = 0; i < r; i++) {
      long t0 = System.nanoTime();
      sink += Fib.fibRecursive(n);
      t[i] = (System.nanoTime() - t0) / 1e6;
      if (SLEEP > 0) Thread.sleep(SLEEP);
    }
    Arrays.sort(t);
    System.out.printf(
        "BENCH n=%d rounds=%d sleep=%d median=%.1f ms min=%.1f ms (sink=%d)%n",
        n, r, SLEEP, t[r / 2], t[0], sink);
  }
}
