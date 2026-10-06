package jvmeter.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** The call tree with totals, hot spots, and per-method aggregation. */
public final class CallTree {

  public static final class Node {
    public final int id;
    public final String name;
    public final double self;
    public final double calls;
    public final Node parent;
    public final int depth;
    public final List<Node> children = new ArrayList<>();
    public double total;

    Node(int id, String name, double self, double calls, Node parent, int depth) {
      this.id = id;
      this.name = name;
      this.self = self;
      this.calls = calls;
      this.parent = parent;
      this.depth = depth;
    }
  }

  public static final class HotSpot {
    public final String name;
    public double self;
    public double calls;
    public double total;

    HotSpot(String name) {
      this.name = name;
    }
  }

  public record Entry(String name, double ms) {}

  public record MethodInfo(
      String name,
      double self,
      double total,
      double calls,
      List<Entry> callers,
      List<Entry> callees) {}

  public final Node root;
  public final List<HotSpot> hot;
  private int nid;

  /** exact per-method counts from the agent; empty when the tree nodes carry the counts */
  private final Map<String, Long> methodCalls = new HashMap<>();

  public CallTree(Snapshot.Cpu cpu) {
    if (cpu.methods != null) {
      cpu.methods.forEach(m -> methodCalls.put(m.name, m.calls));
    }
    root = prep(cpu.tree, null, 0);
    hot = buildHot();
  }

  private Node prep(Snapshot.Node n, Node parent, int depth) {
    Node node = new Node(nid++, n.name, n.self, n.calls, parent, depth);
    for (Snapshot.Node c : n.children == null ? List.<Snapshot.Node>of() : n.children) {
      node.children.add(prep(c, node, depth + 1));
    }
    double t = node.self;
    for (Node c : node.children) {
      t += c.total;
    }
    node.total = t;
    return node;
  }

  /**
   * the paths (names from the root) of these nodes: node ids change when a live recording sends a
   * new tree
   */
  public Set<String> paths(Set<Integer> ids) {
    Set<String> out = new java.util.HashSet<>();
    walk(
        root,
        n -> {
          if (ids.contains(n.id)) {
            out.add(path(n));
          }
        });
    return out;
  }

  /** the ids of the nodes at these paths */
  public Set<Integer> ids(Set<String> paths) {
    Set<Integer> out = new java.util.HashSet<>();
    walk(
        root,
        n -> {
          if (paths.contains(path(n))) {
            out.add(n.id);
          }
        });
    return out;
  }

  private static String path(Node n) {
    return n.parent == null ? n.name : path(n.parent) + "\n" + n.name;
  }

  public static void walk(Node n, Consumer<Node> f) {
    f.accept(n);
    for (Node c : n.children) {
      walk(c, f);
    }
  }

  private List<HotSpot> buildHot() {
    Map<String, HotSpot> m = new LinkedHashMap<>();
    walk(
        root,
        n -> {
          if (n.parent == null || n.self <= 0) {
            return;
          }
          HotSpot h = m.computeIfAbsent(n.name, HotSpot::new);
          h.self += n.self;
          h.calls += n.calls;
          h.total += n.total;
        });
    if (!methodCalls.isEmpty()) {
      m.values().forEach(h -> h.calls = methodCalls.getOrDefault(h.name, 0L));
    }
    return new ArrayList<>(m.values());
  }

  /** Expands the most expensive path below n (at most limit levels) and returns where it ends. */
  public static Node expandHotPath(Node n, Set<Integer> expanded, int limit) {
    Node cur = n;
    int i = 0;
    while (!cur.children.isEmpty() && i++ < limit) {
      expanded.add(cur.id);
      Node best = cur.children.get(0);
      for (Node c : cur.children) {
        if (c.total > best.total) {
          best = c;
        }
      }
      cur = best;
    }
    return cur;
  }

  /**
   * Aggregates a method over the whole tree (also works for self-0 methods missing from hot spots).
   */
  public MethodInfo methodInfo(String name) {
    List<Node> nodes = new ArrayList<>();
    walk(
        root,
        n -> {
          if (n.parent != null && n.name.equals(name)) {
            nodes.add(n);
          }
        });
    if (nodes.isEmpty()) {
      return null;
    }
    double self = 0, total = 0, calls = 0;
    Map<String, Double> callers = new LinkedHashMap<>(), callees = new LinkedHashMap<>();
    for (Node n : nodes) {
      self += n.self;
      calls += n.calls;
      if (inSame(n, name)) {
        continue; // do not double-count recursion in total
      }
      total += n.total;
      String c = n.parent.parent != null ? n.parent.name : "(thread root)";
      callers.merge(c, n.total, Double::sum);
    }
    for (Node n : nodes) {
      for (Node ch : n.children) {
        if (!ch.name.equals(name)) {
          callees.merge(ch.name, ch.total, Double::sum);
        }
      }
    }
    if (!methodCalls.isEmpty()) {
      calls = methodCalls.getOrDefault(name, 0L);
    }
    return new MethodInfo(name, self, total, calls, sorted(callers), sorted(callees));
  }

  private static boolean inSame(Node n, String name) {
    for (Node p = n.parent; p != null; p = p.parent) {
      if (p.name.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static List<Entry> sorted(Map<String, Double> m) {
    List<Entry> l = new ArrayList<>();
    m.forEach((k, v) -> l.add(new Entry(k, v)));
    l.sort(Comparator.comparingDouble((Entry e) -> e.ms).reversed());
    return l;
  }
}
