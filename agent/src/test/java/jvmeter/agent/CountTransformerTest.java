package jvmeter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

class CountTransformerTest {

  /** instrumented below: every kind of first argument the counter treats differently */
  public static class Target {
    public Target(int k) {}

    public static int fib(int n) {
      return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    public static long half(long x) {
      return x / 2;
    }

    public static float twice(float x) {
      return x * 2;
    }

    public static int len(String s, char c) {
      return s.length() + c;
    }

    public static int none() {
      return 1;
    }
  }

  /** counts stay exact when calls are spread over a thread's slot by their argument */
  @Test
  void countsEveryCallWhateverTheArguments() throws Exception {
    String name = Target.class.getName();
    byte[] b;
    try (InputStream in =
        Target.class.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      b = in.readAllBytes();
    }
    ClassReader cr = new ClassReader(b);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cr.accept(
        new CountTransformer.CountClassVisitor(cw, name.replace('.', '/')),
        ClassReader.EXPAND_FRAMES);
    byte[] counted = cw.toByteArray();
    Class<?> c =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            return n.equals(name)
                ? defineClass(n, counted, 0, counted.length)
                : super.loadClass(n, resolve);
          }
        }.loadClass(name);

    c.getMethod("fib", int.class).invoke(null, 20); // 21891 calls
    for (int i = 0; i < 1000; i++) {
      c.getMethod("half", long.class).invoke(null, (long) i);
      c.getMethod("twice", float.class).invoke(null, (float) i);
      c.getMethod("len", String.class, char.class).invoke(null, "x", (char) i);
      c.getMethod("none").invoke(null);
      c.getConstructor(int.class).newInstance(i);
    }

    assertEquals(21891, calls(name, "fib(I)I"));
    assertEquals(1000, calls(name, "half(J)J"));
    assertEquals(1000, calls(name, "twice(F)F"));
    assertEquals(1000, calls(name, "len(Ljava/lang/String;C)I"));
    assertEquals(1000, calls(name, "none()I"));
    assertEquals(1000, calls(name, "<init>(I)V"));
  }

  private static long calls(String cls, String method) {
    return CallCounter.count(MethodRegistry.register(cls + "." + method));
  }

  /** "*" counts application and library classes but never the JDK, the agent, or ASM */
  @Test
  void starCountsAllButTheJdkAndJvmeter() {
    String[] all = CountTransformer.prefixes("*");
    org.junit.jupiter.api.Assertions.assertTrue(CountTransformer.matches(all, "com/example/App"));
    org.junit.jupiter.api.Assertions.assertTrue(
        CountTransformer.matches(all, "org/springframework/web/DispatcherServlet"));
    org.junit.jupiter.api.Assertions.assertFalse(CountTransformer.matches(all, "java/lang/Object"));
    org.junit.jupiter.api.Assertions.assertFalse(
        CountTransformer.matches(all, "jdk/internal/loader/ClassLoaders"));
    org.junit.jupiter.api.Assertions.assertFalse(CountTransformer.matches(all, "sun/misc/Unsafe"));
    org.junit.jupiter.api.Assertions.assertFalse(
        CountTransformer.matches(all, "jvmeter/agent/CallCounter"));
    org.junit.jupiter.api.Assertions.assertFalse(
        CountTransformer.matches(all, "org/objectweb/asm/ClassReader"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new String[] {"com/example/", "app/", "Hello", "*"},
        CountTransformer.prefixes("com.example, app/, Hello, *"));
  }
}
