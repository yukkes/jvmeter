package app;

import java.util.Arrays;

/**
 * A call structure whose true self time is known (depth 4, fan-out 3). Used to check time accuracy.
 * fibRecursive(d): does work(300K) itself, then calls fibRecursive(d-1) 3 times and add twice add:
 * work((40..160)K) (length varies per call, 100K on average) From the amount of work, the true self
 * split is fibRecursive 60 % / add 40 % (the work each one does counts toward its own self).
 * -Dmix.scale=K scales the amount of work.
 */
public class Mix {
  static long seed = 42;
  static final int K = Integer.getInteger("mix.scale", 1); // work multiplier

  static long work(int n) {
    long x = seed;
    for (int i = 0; i < n; i++) x = x * 6364136223846793005L + 1442695040888963407L;
    seed = x;
    return x;
  }

  public static long fibRecursive(int d) {
    long s = work(300 * K);
    if (d > 0) for (int i = 0; i < 3; i++) s += fibRecursive(d - 1);
    s += add(s);
    s += add(s);
    return s;
  }

  public static long add(long a) {
    return a ^ work((40 + (int) ((a >>> 33) % 121)) * K);
  }

  public static void main(String[] a) {
    int r = a.length > 0 ? Integer.parseInt(a[0]) : 15;
    long sink = 0;
    for (int i = 0; i < 2000 / K; i++) sink += fibRecursive(4);
    double[] t = new double[r];
    for (int k = 0; k < r; k++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < 4000 / K; i++) sink += fibRecursive(4);
      t[k] = (System.nanoTime() - t0) / 1e6;
    }
    Arrays.sort(t);
    System.out.printf("MIX rounds=%d median=%.1f ms (sink=%d)%n", r, t[r / 2], sink & 1);
  }
}
