package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import jvmeter.core.ClassStat;
import org.junit.jupiter.api.Test;

class HistogramTest {

  @Test
  void parsesJcmdOutputWithJavaNames() {
    List<ClassStat> l =
        Histogram.parse(
            """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:          2214       20431016  [B (java.base@25)
               2:          1530         36720  java.lang.String (java.base@25)
               3:            12           576  [[Ljava.lang.Object; (java.base@25)
            Total          3756       20468312
            """);
    assertEquals(3, l.size());
    assertEquals("byte[]", l.get(0).name);
    assertEquals(2214, l.get(0).count);
    assertEquals(20431016, l.get(0).bytes);
    assertEquals("java.lang.String", l.get(1).name);
    assertEquals("java.lang.Object[][]", l.get(2).name);
  }

  @Test
  void takesAHistogramOfThisJvm() throws Exception {
    assertEquals(true, Histogram.take().stream().anyMatch(c -> c.name.equals("java.lang.String")));
  }

  @Test
  void agentOptions() {
    assertEquals(
        "com.example,app", Agent.options("port=7091,include=com.example,app").get("include"));
    assertEquals("7091", Agent.options("include=com.example,port=7091").get("port"));
  }
}
