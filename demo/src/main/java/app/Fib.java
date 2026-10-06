package app;

public class Fib {

  public static void main(String[] args) throws Exception {
    int n = args.length > 0 ? Integer.parseInt(args[0]) : 35;
    // warm-up
    for (int i = 0; i < 3; i++) fibRecursive(28);

    // Repeat and print the wall time of each round, so a long-running target can be observed.
    for (int round = 0; round < 40; round++) {
      long t0 = System.nanoTime();
      fibRecursive(n);
      long t1 = System.nanoTime();
      System.out.printf("round %2d  fib(%d)  %6.1f ms%n", round, n, (t1 - t0) / 1e6);
      Thread.sleep(500);
    }
  }

  public static long fibRecursive(int n) {
    if (n < 2) {
      return n;
    }
    return add(fibRecursive(n - 1), fibRecursive(n - 2));
  }

  private static long add(long a, long b) {
    return a + b;
  }
}
