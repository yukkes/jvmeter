package jvmeter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Older data is kept coarser, so a recording of hours stays small: 1 s for the last 10 minutes, 10
 * s up to an hour, 1 min up to 6 hours, then 10 min. Buckets are aligned to the start of the
 * recording, and each tier's size divides the next one's, so a bucket only ever merges into a
 * larger one. GC events are kept as they are.
 */
public final class Retention {

  private Retention() {}

  /** bucket size in seconds for data this old */
  public static int tier(double age) {
    return age < 600 ? 1 : age < 3600 ? 10 : age < 21600 ? 60 : 600;
  }

  /** class histories: the last one of each bucket */
  public static void history(List<Session.ClassSnap> h, double now) {
    List<Session.ClassSnap> out = new ArrayList<>();
    String pk = null;
    for (Session.ClassSnap e : h) {
      int size = tier(now - e.t());
      String key = size + ":" + Math.ceil(e.t() / size);
      if (size > 1 && key.equals(pk)) {
        out.set(out.size() - 1, e);
      } else {
        out.add(e);
        pk = key;
      }
    }
    h.clear();
    h.addAll(out);
  }

  /**
   * thread timelines: the segments that lie inside one bucket become one, in the state they spent
   * most time in
   */
  public static void segs(List<Seg> segs, double now) {
    List<Seg> out = new ArrayList<>();
    for (int i = 0; i < segs.size(); ) {
      Seg g = segs.get(i);
      int size = tier(now - g.end);
      double b0 = Math.floor(g.start / size) * size, b1 = b0 + size;
      Map<String, Double> sec = new LinkedHashMap<>();
      int j = i;
      while (size > 1 && j < segs.size() && segs.get(j).start >= b0 && segs.get(j).end <= b1) {
        sec.merge(segs.get(j).state, segs.get(j).end - segs.get(j).start, Double::sum);
        j++;
      }
      if (j - i > 1) {
        String st = null;
        for (Map.Entry<String, Double> e : sec.entrySet()) {
          if (st == null || e.getValue() > sec.get(st)) {
            st = e.getKey();
          }
        }
        append(out, new Seg(g.start, segs.get(j - 1).end, st));
        i = j;
      } else {
        append(out, new Seg(g.start, g.end, g.state));
        i++;
      }
    }
    segs.clear();
    segs.addAll(out);
  }

  /** adds a segment, extending the last one when it continues it in the same state */
  public static void append(List<Seg> segs, Seg g) {
    Seg l = segs.isEmpty() ? null : segs.get(segs.size() - 1);
    if (l != null && l.state.equals(g.state) && l.end == g.start) {
      l.end = g.end;
    } else {
      segs.add(g);
    }
  }
}
