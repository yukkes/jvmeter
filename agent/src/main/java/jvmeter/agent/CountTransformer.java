package jvmeter.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * Inserts a single {@code CallCounter.hit(METHOD_ID)} at the entry of every method of the target
 * classes, or {@code hit(METHOD_ID, arg)} with its first integer argument when it has one.
 *
 * <p>Only calls are counted, so no exit/exception hooks and no shadow stack are needed. Targets:
 * packages such as "com.example.orders,app" (prefixes of class names). They can change while the
 * JVM runs: {@link #include(Instrumentation, String)} re-instruments the classes already loaded.
 */
public class CountTransformer implements ClassFileTransformer {

  private static final Type COUNTER = Type.getType("Ljvmeter/agent/CallCounter;");
  private static final Method HIT = new Method("hit", "(I)V");
  private static final Method HIT_ARG = new Method("hit", "(II)V");

  /** internal-name prefixes ("com/example/orders/"); empty: nothing is counted; "*" matches all */
  private volatile String[] include = {};

  /**
   * never counted, even with "*": the JDK (instrumented code in its hot paths could call the
   * counter recursively), the agent itself, and ASM (retransforming it while it transforms a class
   * would recurse)
   */
  private static final String[] SKIP = {"java/", "jdk/", "sun/", "jvmeter/", "org/objectweb/asm/"};

  private volatile String packages = "";

  /**
   * counts calls in these packages from now on ("" counts none): loaded classes of the old and new
   * packages are retransformed
   */
  public synchronized void include(Instrumentation inst, String packages) {
    String[] old = include;
    this.packages = packages;
    include = prefixes(packages);
    List<Class<?>> redo = new ArrayList<>();
    for (Class<?> c : inst.getAllLoadedClasses()) {
      String n = c.getName().replace('.', '/');
      if (inst.isModifiableClass(c) && (matches(old, n) || matches(include, n))) {
        redo.add(c);
      }
    }
    // one class at a time: a class that cannot be retransformed (e.g. a hidden class) must not stop
    // the others
    for (Class<?> c : redo) {
      try {
        inst.retransformClasses(c);
      } catch (Throwable t) {
        // left as it was
      }
    }
  }

  public String include() {
    return packages;
  }

  /**
   * "com.example.orders, app/, Hello" -> {"com/example/orders/", "app/", "Hello"} (a class name,
   * capitalized, matches itself and its nested classes); "*" stays "*": every class but the JDK and
   * jvmeter's own ({@link #SKIP})
   */
  static String[] prefixes(String packages) {
    return Arrays.stream(packages.split(","))
        .map(String::trim)
        .filter(p -> !p.isEmpty())
        .map(p -> p.replace('.', '/'))
        .map(
            p ->
                p.equals("*")
                        || p.endsWith("/")
                        || Character.isUpperCase(p.charAt(p.lastIndexOf('/') + 1))
                    ? p
                    : p + "/")
        .toArray(String[]::new);
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (className == null || !matches(include, className)) {
      return null;
    }
    try {
      ClassReader cr = new ClassReader(classfileBuffer);
      ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
      cr.accept(new CountClassVisitor(cw, className), ClassReader.EXPAND_FRAMES);
      return cw.toByteArray();
    } catch (Throwable t) {
      System.err.println("[jvmeter] instrument failed: " + className + ": " + t);
      return null;
    }
  }

  static boolean matches(String[] include, String className) {
    for (String p : include) {
      if (p.equals("*")) {
        for (String s : SKIP) {
          if (className.startsWith(s)) {
            return false;
          }
        }
        return true;
      }
      if (!p.isEmpty() && className.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  /** the index of the first argument that is an integer (boolean … long), -1 if none */
  static int intArg(Type[] args) {
    for (int i = 0; i < args.length; i++) {
      int sort = args[i].getSort();
      if (sort >= Type.BOOLEAN && sort <= Type.LONG && sort != Type.FLOAT) {
        return i;
      }
    }
    return -1;
  }

  static class CountClassVisitor extends ClassVisitor {
    private final String className;

    CountClassVisitor(ClassVisitor cv, String className) {
      super(Opcodes.ASM9, cv);
      this.className = className;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (mv == null
          || name.equals("<clinit>")
          || (access
                  & (Opcodes.ACC_ABSTRACT
                      | Opcodes.ACC_NATIVE
                      | Opcodes.ACC_BRIDGE
                      | Opcodes.ACC_SYNTHETIC))
              != 0) {
        return mv;
      }
      int id = MethodRegistry.register(className.replace('/', '.') + "." + name + descriptor);
      if (id >= CallCounter.MAX_METHODS) {
        return mv; // methods beyond the limit are not counted
      }
      // a constructor counts after super(), when its argument slots may hold something else
      int arg = name.equals("<init>") ? -1 : intArg(Type.getArgumentTypes(descriptor));
      return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
        @Override
        protected void onMethodEnter() {
          push(id);
          if (arg < 0) {
            invokeStatic(COUNTER, HIT);
            return;
          }
          loadArg(arg);
          if (getArgumentTypes()[arg].getSort() == Type.LONG) {
            visitInsn(Opcodes.L2I);
          }
          invokeStatic(COUNTER, HIT_ARG);
        }
      };
    }
  }
}
