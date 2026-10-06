package jvmeter.agent;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jvmeter.core.Live;
import jvmeter.core.Retention;
import jvmeter.core.Seg;
import jvmeter.core.Snapshot;

/**
 * Thread states, sampled every 100 ms with ThreadMXBean: run, wait, block (waiting for a lock) or
 * io (RUNNABLE inside a native network or file call, which ThreadMXBean reports as running). Each
 * second becomes the state seen most in it; the time in each state is summed per sample, so short
 * blocks still count. Samples in block or io are also summed per place (Snapshot.Wait): the first
 * frame in the application's code, and the lock. All of it comes from the same dumpAllThreads call,
 * so it adds nothing to the pause it takes.
 */
final class ThreadSampler {

  static final int PER_SEC = 10, MAX_THREADS = 200, DEPTH = 24, MAX_WAITS = 1000;
  static final String[] STATES = {"run", "wait", "block", "io"};

  static final class Track {
    final String name;

    /** whole seconds; older ones coarser (Retention) */
    final List<Seg> segs = new ArrayList<>();

    final double[] sec = new double[4];
    final int[] now = new int[4];
    List<String> stack = List.of();
    double born;
    boolean alive;

    Track(String name) {
      this.name = name;
    }
  }

  /** samples in one state at one place: thread and owner -> samples */
  static final class Place {
    final String state, site, lock, top;
    int n;
    final Map<String, Integer> threads = new HashMap<>(), owners = new HashMap<>();

    Place(String state, String site, String lock, String top) {
      this.state = state;
      this.site = site;
      this.lock = lock;
      this.top = top;
    }
  }

  private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
  private final Map<Long, Track> tracks = new LinkedHashMap<>();
  private final Map<String, Place> waits = new HashMap<>();

  /** the application's code (Findings.own) */
  private final java.util.function.Predicate<String> own;

  ThreadSampler(String include) {
    own = jvmeter.core.Findings.own(include);
  }

  /** one sample of every thread but jvmeter's own */
  synchronized void sample(double t) {
    for (Track k : tracks.values()) {
      k.alive = false;
    }
    for (ThreadInfo ti : mx.dumpAllThreads(false, false, DEPTH)) {
      // jvmeter's own threads, and JFR's, which run because jvmeter records
      if (ti == null
          || ti.getThreadName().startsWith("jvmeter-")
          || ti.getThreadName().startsWith("JFR ")) {
        continue;
      }
      int st = state(ti);
      if (st < 0) {
        continue;
      }
      Track k = tracks.get(ti.getThreadId());
      if (k == null) {
        if (tracks.size() >= MAX_THREADS) {
          continue;
        }
        k =
            new Track(
                ti.getThreadName() + (named(ti.getThreadName()) ? " #" + ti.getThreadId() : ""));
        k.born = Math.floor(t);
        tracks.put(ti.getThreadId(), k);
      }
      k.alive = true;
      k.now[st]++;
      k.sec[st] += 1.0 / PER_SEC;
      List<String> stack = new ArrayList<>();
      for (StackTraceElement f : ti.getStackTrace()) {
        stack.add(f.getClassName() + "." + f.getMethodName());
      }
      k.stack = stack;
      if (st >= 2 && !stack.isEmpty()) {
        wait(
            k.name,
            STATES[st],
            stack,
            st == 2 ? ti.getLockName() : null,
            st == 2 ? ti.getLockOwnerName() : null);
      }
    }
  }

  private void wait(String thread, String state, List<String> stack, String lock, String owner) {
    String site = stack.stream().filter(own).findFirst().orElse(stack.get(0));
    String key = state + '\0' + site + '\0' + lock;
    Place p = waits.get(key);
    if (p == null) {
      if (waits.size() >= MAX_WAITS) {
        return;
      }
      p = new Place(state, site, lock, stack.get(0));
      waits.put(key, p);
    }
    p.n++;
    p.threads.merge(thread, 1, Integer::sum);
    if (owner != null) {
      p.owners.merge(owner, 1, Integer::sum);
    }
  }

  /** where threads waited, longest first */
  synchronized List<Snapshot.Wait> waits() {
    List<Snapshot.Wait> out = new ArrayList<>();
    for (Place p : waits.values()) {
      Snapshot.Wait w = new Snapshot.Wait();
      w.state = p.state;
      w.site = p.site;
      w.lock = p.lock;
      w.top = p.top;
      w.sec = Math.round(p.n * 10.0 / PER_SEC) / 10.0;
      w.owner =
          p.owners.entrySet().stream()
              .max(Map.Entry.comparingByValue())
              .map(Map.Entry::getKey)
              .orElse(null);
      w.threads =
          p.threads.entrySet().stream()
              .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .map(Map.Entry::getKey)
              .limit(10)
              .toList();
      out.add(w);
    }
    out.sort((a, b) -> Double.compare(b.sec, a.sec));
    return out.subList(0, Math.min(50, out.size()));
  }

  private boolean named(String name) {
    return tracks.values().stream().anyMatch(k -> k.name.equals(name));
  }

  /**
   * closes second [t - 1, t): its state is the one sampled most; threads that ended within 2 s of
   * starting are dropped
   */
  synchronized List<Live.ThreadSec> second(int t) {
    tracks.values().removeIf(k -> !k.alive && t - k.born <= 2);
    List<Live.ThreadSec> out = new ArrayList<>();
    for (Track k : tracks.values()) {
      int best = -1;
      for (int i = 0; i < 4; i++) {
        if (k.now[i] > 0 && (best < 0 || k.now[i] > k.now[best])) {
          best = i;
        }
      }
      java.util.Arrays.fill(k.now, 0);
      if (best >= 0) {
        Retention.append(k.segs, new Seg(t - 1, t, STATES[best]));
      }
      Retention.segs(k.segs, t);
      Live.ThreadSec ts = new Live.ThreadSec();
      ts.name = k.name;
      ts.state = best >= 0 ? STATES[best] : null;
      ts.run = Math.round(k.sec[0] * 10) / 10.0;
      ts.wait = Math.round(k.sec[1] * 10) / 10.0;
      ts.block = Math.round(k.sec[2] * 10) / 10.0;
      ts.io = Math.round(k.sec[3] * 10) / 10.0;
      out.add(ts);
    }
    return out;
  }

  /** the threads as the snapshot holds them */
  synchronized List<Snapshot.RawThread> threads() {
    List<Snapshot.RawThread> out = new ArrayList<>();
    for (Track k : tracks.values()) {
      Snapshot.RawThread r = new Snapshot.RawThread();
      r.name = k.name;
      r.segs = new ArrayList<>();
      for (Seg g : k.segs) {
        r.segs.add(List.of(g.start, g.end, g.state));
      }
      r.stack = new ArrayList<>(k.stack);
      r.sec = new HashMap<>();
      for (int i = 0; i < 4; i++) {
        r.sec.put(STATES[i], Math.round(k.sec[i] * 10) / 10.0);
      }
      out.add(r);
    }
    return out;
  }

  synchronized int count() {
    return (int) tracks.values().stream().filter(k -> k.alive).count();
  }

  /** -1 for threads not started or ended */
  static int state(ThreadInfo ti) {
    switch (ti.getThreadState()) {
      case BLOCKED:
        return 2;
      case WAITING:
      case TIMED_WAITING:
        // parked on a lock that another thread owns (ReentrantLock and the like) waits for that
        // lock like a monitor
        return ti.getLockOwnerId() != -1 ? 2 : 1;
      case RUNNABLE:
        // JVM threads that wait in native code show as RUNNABLE: without Java frames (Signal
        // Dispatcher, Notification Thread),
        // or the Reference Handler waiting for references to process
        StackTraceElement[] st = ti.getStackTrace();
        if (st.length == 0 || st[0].getMethodName().equals("waitForReferencePendingList")) {
          return 1;
        }
        // a server waiting for a connection (accept) or an event loop in a selector waits for work:
        // not io
        return !st[0].isNativeMethod() || !io(st[0].getClassName()) ? 0 : idle(st[0]) ? 1 : 3;
      default:
        return -1;
    }
  }

  static boolean idle(StackTraceElement f) {
    String c = f.getClassName();
    return f.getMethodName().startsWith("accept")
        || c.startsWith("sun.nio.ch.EPoll")
        || c.startsWith("sun.nio.ch.KQueue")
        || c.startsWith("sun.nio.ch.WEPoll")
        || c.endsWith("SelectorImpl");
  }

  /**
   * native methods that wait for the network or files: sockets, selectors (sun.nio.ch), java.net,
   * file streams
   */
  static boolean io(String cls) {
    return cls.startsWith("sun.nio.ch.")
        || cls.startsWith("java.net.")
        || cls.startsWith("java.io.File")
        || cls.startsWith("java.io.RandomAccessFile")
        || cls.startsWith("sun.nio.fs.");
  }
}
