package jvmeter.core;

import io.avaje.jsonb.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU %, heap MB and thread count over time, one point per second when new. Older points cover more
 * seconds ({@link Retention}), so hours of recording stay small. Fields mirror the snapshot's
 * "telemetry" (generated avaje adapters read and write it).
 */
@Json
public final class Telemetry {

  /** the second each point ends at, and how many seconds it averages */
  public List<Double> t = new ArrayList<>();

  public List<Integer> n = new ArrayList<>();
  public List<Double> cpu = new ArrayList<>();
  public List<Double> heap = new ArrayList<>();
  public List<Double> threads = new ArrayList<>();

  public Telemetry() {}

  /** one second's values */
  public void add(double sec, double cpuPct, double heapMB, double threadCount) {
    t.add(sec);
    n.add(1);
    cpu.add(cpuPct);
    heap.add(heapMB);
    threads.add(threadCount);
  }

  /** points in one bucket of their tier become one, averaging the values (weighted by seconds) */
  public void compact(double now) {
    int o = -1;
    String pk = null;
    for (int i = 0; i < t.size(); i++) {
      int size = Retention.tier(now - t.get(i));
      String key = size + ":" + Math.ceil(t.get(i) / size);
      if (size > 1 && key.equals(pk)) {
        int w = n.get(o) + n.get(i);
        for (List<Double> l : List.of(cpu, heap, threads)) {
          l.set(o, (l.get(o) * n.get(o) + l.get(i) * n.get(i)) / w);
        }
        n.set(o, w);
        t.set(o, t.get(i));
      } else {
        o++;
        for (List<Double> l : List.of(t, cpu, heap, threads)) {
          l.set(o, l.get(i));
        }
        n.set(o, n.get(i));
        pk = key;
      }
    }
    for (List<?> l : List.of(t, n, cpu, heap, threads)) {
      l.subList(o + 1, l.size()).clear();
    }
  }

  /** the values of the one-second points: the last 10 minutes */
  public List<Double> recent(List<Double> l) {
    List<Double> out = new ArrayList<>();
    for (int i = 0; i < l.size(); i++) {
      if (n.get(i) == 1) {
        out.add(l.get(i));
      }
    }
    return out;
  }

  public Telemetry copy() {
    Telemetry c = new Telemetry();
    c.t.addAll(t);
    c.n.addAll(n);
    c.cpu.addAll(cpu);
    c.heap.addAll(heap);
    c.threads.addAll(threads);
    return c;
  }

  public boolean isEmpty() {
    return t.isEmpty();
  }

  public static double last(List<Double> l) {
    return l.get(l.size() - 1);
  }
}
