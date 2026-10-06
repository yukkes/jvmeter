package jvmeter.agent;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.ObjectName;
import jvmeter.core.ClassStat;

/**
 * Instances and bytes per class, from the GC.class_histogram diagnostic command (as jcmd runs it).
 * With -all it walks the whole heap, unreachable objects included, without forcing a GC first: a
 * stop-the-world pause of roughly 10 to 100 ms per GB of heap. Right after a GC it is the live
 * objects only.
 */
final class Histogram {

  static final int TOP = 300;

  private Histogram() {}

  /** the TOP classes by size, largest first */
  static List<ClassStat> take() throws Exception {
    String out =
        (String)
            ManagementFactory.getPlatformMBeanServer()
                .invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram",
                    new Object[] {new String[] {"-all"}},
                    new String[] {String[].class.getName()});
    return parse(out);
  }

  /** " 1: 12345 1234567 [B (java.base@17)" lines; they come sorted by size */
  static List<ClassStat> parse(String out) {
    List<ClassStat> l = new ArrayList<>();
    for (String line : out.split("\n")) {
      String[] w = line.trim().split("\\s+");
      if (w.length >= 4 && w[0].endsWith(":") && l.size() < TOP) {
        l.add(new ClassStat(name(w[3]), Long.parseLong(w[1]), Long.parseLong(w[2])));
      }
    }
    return l;
  }

  /** JVM names to Java names: "[B" -> "byte[]", "[[Ljava.lang.String;" -> "java.lang.String[][]" */
  static String name(String n) {
    int dims = 0;
    while (dims < n.length() && n.charAt(dims) == '[') {
      dims++;
    }
    if (dims == 0) {
      return n;
    }
    String e = n.substring(dims);
    String base =
        switch (e) {
          case "B" -> "byte";
          case "C" -> "char";
          case "D" -> "double";
          case "F" -> "float";
          case "I" -> "int";
          case "J" -> "long";
          case "S" -> "short";
          case "Z" -> "boolean";
          default -> e.startsWith("L") && e.endsWith(";") ? e.substring(1, e.length() - 1) : e;
        };
    return base + "[]".repeat(dims);
  }
}
