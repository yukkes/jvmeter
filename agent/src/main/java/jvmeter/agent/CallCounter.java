package jvmeter.agent;

/**
 * Per-method call counts, counted exactly by instrumentation.
 *
 * <p>Instrumented code only calls {@link #hit(int)} on method entry. It reads no clock (time comes
 * from {@link JfrRecorder}). Atomic increments cost ~7 ns each, so each thread adds to its own slot
 * with a plain increment and the slots are summed on read. Slots are 64 bytes apart to avoid false
 * sharing. The slot is picked by the low 6 bits of the thread ID, so two threads whose IDs differ
 * by a multiple of 64 can, rarely, lose a count when they call the same method at the same time.
 *
 * <p>A method called again and again (a loop, a recursion) increments the same address each time,
 * and each increment waits for the one before it to be stored: about 1 ns a call, more than a tiny
 * method itself takes. {@link #hit(int, int)} spreads the calls over the 8 longs of the thread's
 * slot by the low bits of an argument, so consecutive calls rarely wait for each other (fib: 3x
 * less overhead).
 */
public final class CallCounter {

  static final int MAX_METHODS = 4096;
  static final int SLOTS = 64;
  static final int PAD = 8; // 8 longs = 64 bytes
  private static final long[] COUNTS = new long[MAX_METHODS * SLOTS * PAD];

  private CallCounter() {}

  /** Called from instrumented code. The JIT inlines it into a single array increment. */
  @SuppressWarnings("unused") // the instrumentation inserts the calls
  public static void hit(int methodId) {
    COUNTS[(methodId * SLOTS + (int) (Thread.currentThread().getId() & (SLOTS - 1))) * PAD]++;
  }

  /** Called from instrumented code with the method's first integer argument. */
  @SuppressWarnings("unused") // the instrumentation inserts the calls
  public static void hit(int methodId, int arg) {
    COUNTS[
        (methodId * SLOTS + (int) (Thread.currentThread().getId() & (SLOTS - 1))) * PAD
            + (arg & (PAD - 1))]++;
  }

  public static long count(int methodId) {
    long n = 0;
    for (int k = 0; k < SLOTS * PAD; k++) {
      n += COUNTS[methodId * SLOTS * PAD + k];
    }
    return n;
  }
}
