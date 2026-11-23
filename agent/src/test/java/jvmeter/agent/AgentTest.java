package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentTest {

  @Test
  void mainTokenPreservesSpacesInJarPaths() {
    assertEquals(
        "C:\\Program Files\\My App\\server.jar",
        Agent.mainToken("C:\\Program Files\\My App\\server.jar --port=8080"));
    assertEquals("/opt/my app/service.jar", Agent.mainToken("/opt/my app/service.jar"));
    assertEquals("com.example.App", Agent.mainToken("com.example.App --jar other.jar"));
    assertEquals("com.example.App", Agent.mainToken("com.example.App arg1 arg2"));
    assertEquals("app.Main", Agent.mainToken("app.Main /data/lib.jar"));
    assertEquals("app.Main", Agent.mainToken("app.Main C:\\data\\lib.jar"));
    assertEquals("app.Main", Agent.mainToken("app.Main other lib.jar"));
    assertEquals("?", Agent.mainToken("  "));
    assertEquals("?", Agent.mainToken(null));
  }

  @Test
  void periodAndHistogramEnforceLowerBounds() {
    String oldP = System.getProperty("jvmeter.period"),
        oldH = System.getProperty("jvmeter.histogram");
    try {
      System.setProperty("jvmeter.period", "0");
      System.setProperty("jvmeter.histogram", "-5");
      assertEquals(1, Agent.period().toMillis());
      assertEquals(0, Agent.histogramEvery());
    } finally {
      restore("jvmeter.period", oldP);
      restore("jvmeter.histogram", oldH);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
