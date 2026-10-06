package app;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Benchmark of a busy service: 4 threads allocate (strings, maps, arrays) and now and then take a
 * shared lock. Runs R rounds of a fixed amount of work and prints the median like Bench, for
 * demo/overhead.sh.
 */
public class Load {
  static final Object LOCK = new Object();
  static long shared;

  static Map<String, Integer> order(int i) {
    Map<String, Integer> m = new HashMap<>();
    for (int k = 0; k < 8; k++) {
      m.put("item-" + (i + k), k * i);
    }
    return m;
  }

  static long price(Map<String, Integer> m) {
    long s = 0;
    for (int v : m.values()) {
      s += v;
    }
    return s + new int[16].length;
  }

  static void work(int n) {
    long s = 0;
    for (int i = 0; i < n; i++) {
      s += price(order(i));
      if (i % 200 == 0) {
        synchronized (LOCK) {
          shared += s;
        }
      }
    }
  }

  public static void main(String[] a) throws Exception {
    int r = a.length > 0 ? Integer.parseInt(a[0]) : 30, threads = 4, n = 500_000;
    for (int i = 0; i < 5; i++) {
      round(threads, n);
    }
    double[] t = new double[r];
    for (int i = 0; i < r; i++) {
      t[i] = round(threads, n);
    }
    Arrays.sort(t);
    System.out.printf(
        "BENCH load threads=%d rounds=%d median=%.1f ms min=%.1f ms (shared=%d)%n",
        threads, r, t[r / 2], t[0], shared);
  }

  static double round(int threads, int n) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(threads);
    long t0 = System.nanoTime();
    for (int k = 0; k < threads; k++) {
      new Thread(
              () -> {
                work(n);
                done.countDown();
              },
              "load-" + k)
          .start();
    }
    done.await();
    return (System.nanoTime() - t0) / 1e6;
  }
}
