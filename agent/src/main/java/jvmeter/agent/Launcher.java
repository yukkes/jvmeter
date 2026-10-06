package jvmeter.agent;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jvmeter.core.Targets;

/**
 * java -jar jvmeter-agent.jar list JVMs of this user, as JSON: pid, main class, JDK, JVM options
 * (like jps -v), and the port and token of an agent that already runs in it java -jar
 * jvmeter-agent.jar attach PID [options] loads the agent into PID (Attach API) and prints {"port",
 * "token"}; run it with the target's own java so the Attach API matches its version Errors print
 * {"error": "..."} and exit with 1. The GUI runs these here, over ssh, or with kubectl exec
 * (docs/targets.md).
 */
public final class Launcher {

  private Launcher() {}

  public static void main(String[] a) {
    try {
      if (a.length >= 1 && a[0].equals("list")) {
        // PID: the caller's, hidden too (the GUI running this would be offered to attach to itself)
        List<Targets.Jvm> l = list();
        if (a.length > 1) {
          long caller = Long.parseLong(a[1]);
          l.removeIf(j -> j.pid == caller);
        }
        System.out.println(Server.jsonb().type(jvmeter.core.Targets.Jvm.class).list().toJson(l));
      } else if (a.length >= 2 && a[0].equals("attach")) {
        System.out.println(
            Server.jsonb().type(Object.class).map().toJson(attach(a[1], a.length > 2 ? a[2] : "")));
      } else {
        fail(
            "usage: java -jar jvmeter-agent.jar list [PID] | attach PID"
                + " [include=com.example,...] (list hides PID and itself, e.g. the caller)");
      }
    } catch (Throwable e) {
      fail(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static void fail(String message) {
    System.out.println(Server.jsonb().type(Object.class).map().toJson(Map.of("error", message)));
    System.exit(1);
  }

  /**
   * the JVMs this user can attach to, from jvmstat (hsperfdata, read without touching the JVMs) or
   * else the Attach API
   */
  static List<Targets.Jvm> list() {
    long self = ProcessHandle.current().pid();
    List<Targets.Jvm> out = new ArrayList<>();
    try {
      for (Map<String, String> v : jvmstat()) {
        long pid = Long.parseLong(v.get("pid"));
        if (pid != self) {
          out.add(
              jvm(
                  pid,
                  v.get("sun.rt.javaCommand"),
                  Targets.jvmLabel(
                      v.get("java.property.java.vm.name"), v.get("java.property.java.version")),
                  v.get("java.rt.vmArgs")));
        }
      }
    } catch (ReflectiveOperationException | RuntimeException noJvmstat) {
      for (VirtualMachineDescriptor d : VirtualMachine.list()) {
        long pid = Long.parseLong(d.id());
        if (pid != self) {
          out.add(jvm(pid, d.displayName(), "", ""));
        }
      }
    }
    return out;
  }

  private static Targets.Jvm jvm(long pid, String command, String jvm, String args) {
    Targets.Jvm m =
        new Targets.Jvm(
            pid,
            Agent.mainClass(command == null || command.isBlank() ? "?" : command.split(" ")[0]),
            jvm,
            args == null ? "" : args,
            0);
    String f = AgentFile.read(pid);
    if (f != null) {
      Map<String, Object> a = Server.jsonb().type(Object.class).map().fromJson(f);
      m.port = ((Number) a.get("port")).intValue();
      m.token = (String) a.get("token");
    }
    return m;
  }

  /**
   * jvmstat through reflection: it is internal (jdk.internal.jvmstat), exported to this jar by its
   * manifest's Add-Exports
   */
  @SuppressWarnings("unchecked")
  private static List<Map<String, String>> jvmstat() throws ReflectiveOperationException {
    Class<?> hostC = Class.forName("sun.jvmstat.monitor.MonitoredHost"),
        idC = Class.forName("sun.jvmstat.monitor.VmIdentifier"),
        vmC = Class.forName("sun.jvmstat.monitor.MonitoredVm"),
        monC = Class.forName("sun.jvmstat.monitor.Monitor");
    Object host = hostC.getMethod("getMonitoredHost", String.class).invoke(null, (String) null);
    Method find = vmC.getMethod("findByName", String.class), value = monC.getMethod("getValue");
    List<Map<String, String>> out = new ArrayList<>();
    for (Integer pid : (Set<Integer>) hostC.getMethod("activeVms").invoke(host)) {
      Object vm;
      try {
        // the classes are found at run time: the argument is a VmIdentifier
        //noinspection JavaReflectionInvocation
        vm =
            hostC
                .getMethod("getMonitoredVm", idC)
                .invoke(host, idC.getConstructor(String.class).newInstance("//" + pid));
      } catch (ReflectiveOperationException gone) {
        continue; // it ended meanwhile, or belongs to another user
      }
      Map<String, String> v = new LinkedHashMap<>();
      v.put("pid", String.valueOf(pid));
      for (String k :
          List.of(
              "sun.rt.javaCommand",
              "java.rt.vmArgs",
              "java.property.java.version",
              "java.property.java.vm.name")) {
        Object mon = find.invoke(vm, k);
        v.put(k, mon == null ? null : String.valueOf(value.invoke(mon)));
      }
      // vm is a MonitoredVm
      //noinspection JavaReflectionInvocation
      hostC.getMethod("detach", vmC).invoke(host, vm);
      out.add(v);
    }
    return out;
  }

  /** loads this jar into pid as an agent, then reads the port and token it wrote */
  static Map<String, Object> attach(String pid, String options) throws Exception {
    String jar =
        Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    VirtualMachine vm = VirtualMachine.attach(pid);
    try {
      vm.loadAgent(jar, options);
    } finally {
      vm.detach();
    }
    for (int i = 0; i < 100; i++) {
      String f = AgentFile.read(Long.parseLong(pid));
      if (f != null) {
        Map<String, Object> a = Server.jsonb().type(Object.class).map().fromJson(f);
        return Map.of("port", ((Number) a.get("port")).intValue(), "token", a.get("token"));
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException(
        "the agent did not start in " + pid + " (see the JVM's output)");
  }
}
