# Targets: local, SSH and Kubernetes

jvmeter profiles a JVM on this computer, on a host reached with SSH, or in a Kubernetes pod.
The agent, its protocol and the GUI are the same for all three. Only two things differ:
**how jvmeter runs a command** where the JVM is, and **how it reaches the agent's port**.

| | Run a command | Copy the agent jar | Reach the agent |
|---|---|---|---|
| Local | `ProcessBuilder` | not needed (ships with the GUI) | `127.0.0.1:PORT` |
| SSH | `ssh -o BatchMode=yes HOST -- CMD` (`sudo -u USER` with Run as; BatchMode: no password prompts, as there is no terminal) | `ssh HOST -- sh -c 'cat > ~/.jvmeter/jvmeter-agent.jar' < jar` | `ssh -N -L 127.0.0.1:LOCAL:127.0.0.1:PORT HOST` |
| Kubernetes | `kubectl --context C -n NS exec POD -c CONTAINER -- CMD` | `kubectl … exec -i … -- sh -c 'cat > /tmp/jvmeter/jvmeter-agent.jar' < jar` | `kubectl … port-forward --address 127.0.0.1 pod/POD LOCAL:PORT` |

So the GUI always talks to `127.0.0.1:LOCAL`; a target is just "a command prefix plus a port forward".
The Start Center of [`demo/prototype.html`](../demo/prototype.html) and the GUI (shown at startup, and from the target in the top bar)
shows the exact commands for each kind; `jvmeter.core.Targets` builds them and `TargetsTest` checks them against the prototype.

## Why the user's own `ssh` and `kubectl`

The GUI runs the `ssh` and `kubectl` already installed, instead of bundling an SSH or Kubernetes client library:

- Everything the user has set up works unchanged: `~/.ssh/config`, keys, ssh-agent, ProxyJump, known_hosts;
  kubeconfig contexts, SSO / exec credential plugins, RBAC.
- No library in the GUI (the distribution rule: FlatLaf and avaje-jsonb only). OpenSSH ships with Windows 10+, macOS and Linux.
- What jvmeter does can be shown and copied as plain commands, and repeated by hand when something fails.

## The agent as a launcher

`jvmeter-agent.jar` has a `Main-Class`, so the same jar both lists JVMs and loads itself (`jvmeter.agent.Launcher`):

```
java -jar jvmeter-agent.jar list             # JVMs of this user (like jps -v), each with the port and token of a running agent
/proc/PID/exe -jar jvmeter-agent.jar attach PID [include=com.example]     # loads the agent (Attach API); prints {"port": …, "token": "…"}
```

- `list` reads jvmstat (the `hsperfdata` files `jps` reads, without touching the JVMs): pid, main class
  (for `-jar`, the jar's `Start-Class` or `Main-Class`), JDK version and JVM options. jvmstat is internal to the JDK;
  the jar's manifest exports it to itself (`Add-Exports`). Errors print `{"error": "…"}` and exit with 1.
- The GUI bundles the jar and copies it to `~/.jvmeter/jvmeter-agent.jar` (this user's only) for local JVMs.

- `attach` runs with **the target's own java** (`/proc/PID/exe` on Linux), so the Attach API version matches the target.
  Attaching works only as the JVM's user: on SSH, Run as adds `sudo -u USER`; in a pod, `kubectl exec` already runs as the container's user.
  The GUI refuses a JVM of another user (`[ -O /proc/PID ]`, locally `ProcessHandle.info().user()`) and says whose it is:
  that user's java would otherwise run with the rights of whoever attaches (root, say), and that user could have put anything there.
- The agent listens on **loopback only**, 127.0.0.1 and ::1 on the same port (port 0 = any free port, or `-javaagent:…=port=7091`).
  Both, because ssh / kubectl forward to either, and each on a socket of its own family: Java's usual dual-stack sockets bind
  127.0.0.1 as `::ffff:127.0.0.1`, which WSL2 breaks (a fixed port fails to bind, and port 0 binds but accepts no IPv4).
  A connection must show it knows a random token, and the token itself never goes over the connection: the GUI sends a nonce,
  the agent answers with HMAC-SHA256(token, "agent" + that nonce) and a nonce of its own, and the GUI answers with
  HMAC-SHA256(token, "gui" + the agent's nonce) (`Live.proof`). The GUI checks the agent's proof first, so a process that took the
  forwarded port before ssh / kubectl did learns nothing it could reuse. Until then a connection may send at most 200 bytes a line
  within 10 s, and at most 8 such connections wait at a time; more are closed at once, so nobody without the token can fill the
  JVM's heap or threads. The token reaches the GUI only through the
  output of `attach` / `list`, that is, through the ssh / kubectl channel the user already authenticated.
  It is also stored in a file only the JVM's user can read (`~/.jvmeter/agent-PID.json`, or `TMPDIR/jvmeter-USER` when the home
  directory cannot be written; a directory owned by someone else is not used), so a later `list` can find a running agent.
  One client at a time: a new one replaces the previous one, which may be gone without the agent noticing
  (a GUI that crashed, an ssh tunnel that dropped).
- Over ssh, the GUI runs `sh -c '…'` there (behind `sudo -n -u USER` for Run as); over `kubectl exec`, `sh -c` too.
- The jar is copied into a directory only that user can write (`~/.jvmeter`, or `/tmp/jvmeter` inside the container),
  never into a shared `/tmp`: a jar that another user could replace would run inside the target JVM.
  A directory that is a link or that another user made first is refused (`[ ! -L DIR ] && [ -O DIR ]`), before the jar in it
  is checked or run. It is copied only when the same jar is not already there.
- The local end of a port forward is bound on 127.0.0.1 only (`-L 127.0.0.1:LOCAL:…`, `--address 127.0.0.1`): when another
  process took that port first, ssh / kubectl fail instead of falling back to ::1 and leaving 127.0.0.1 to it.
- Protocol (`jvmeter.core.Live`): after the handshake above, one JSON object per line (avaje-jsonb). The GUI sends commands:
  `start` (with the packages to count), `stop`, `gc` (System.gc(), then a class histogram), `histogram`.
  The agent says `hello` (target, heap settings, collector), and while recording sends a `tick` every second
  (CPU, heap, threads, new GC events, each thread's state and seconds per state), `cpu` every 5 s
  (call tree, call counts, thread stacks, generation sizes), `classes` for each class histogram, and `end` after `stop`.
  The GUI builds the session from these (`Session.apply`), so it saves the snapshot where it runs, and nothing is fetched
  from the remote file system.
- Connecting starts a recording. Closing the connection (another JVM, a snapshot, or closing the window) stops it and takes the
  counters out again; the agent stays loaded, and the next Connect finds it. A JVM started with `-javaagent` records from its start
  and writes its snapshot at exit; a GUI that starts a recording there replaces that one.

## Attaching later

Attaching to a JVM that is already running is the main path: nobody has to know in advance which JVM will be slow.
Two JVM options decide how well it works, and neither loads anything, so they can be on every JVM that might be profiled:

```
-XX:+EnableDynamicAgentLoading -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
```

- `-XX:+EnableDynamicAgentLoading` (JEP 451). Since JDK 21 a JVM prints a warning to its own log when an agent is loaded into it later
  (`WARNING: A Java agent has been loaded dynamically …`), and a future release will refuse it unless this option was given.
  With it, attaching is silent now and keeps working then. JDK 17 accepts it too (it is already the default there).
- `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`. Without it, compiled code keeps debug information only at safepoints,
  so time in inlined methods is attributed to their callers (a known 60/40 split showed up as 99/1, see [`design.md`](design.md)).
  It has to be set at startup; set then, an agent attached hours later attributes inlined time exactly.

| | Attach, JVM not prepared | Attach, JVM prepared with the options above | Start with the agent (`-javaagent`) |
|---|---|---|---|
| Restart to profile | no | no | yes, unless it was already started so |
| Time of inlined methods | shown in their callers | exact | exact with `DebugNonSafepoints` |
| JDK 21+ | a warning in the JVM's log; refused by a future JDK | silent | silent |
| Call counts | loaded classes are re-instrumented (`retransformClasses`), a short pause | the same | from the first call |
| Needs | `jdk.attach` in the runtime; in a pod also `sh` and a writable `/tmp` | the same | the jar where the JVM starts |

The Start Center shows what attaching means for the chosen JVM: `list` reports each JVM's version and options (as `jps -v` does),
so the note under Attach and connect is only the pause for a prepared JVM, and adds the missing pieces otherwise.
Its "Prepare a JVM" section shows the options for the command line, or `JDK_JAVA_OPTIONS` in a Deployment.
(The JVM counts only command-line options as explicit: given in `JAVA_TOOL_OPTIONS`, `-XX:+EnableDynamicAgentLoading` still allows attaching,
but JDK 21+ logs the warning anyway. `JDK_JAVA_OPTIONS` is read by the `java` launcher as if typed on the command line.)
When `DebugNonSafepoints` was off, the top bar shows **Inlined → callers** and the snapshot records it (`target.debugNonSafepoints`),
whether the agent was attached or started with the JVM. (The agent reads the flag through `HotSpotDiagnosticMXBean`;
a diagnostic flag "does not exist" there until it is unlocked, which counts as off.)
After an attach the agent stays loaded (a JVM cannot unload it): Disconnect stops recording and removes the counters,
and the next Connect finds the agent running.

JProfiler works the same way with its native JVMTI agent (`-agentpath`, or attaching from Quick Attach), and is subject to the same JEP 451 rules.

## Snapshot

`target` gains optional fields, so a reader (or an LLM) knows where the numbers come from and how exact they are:

```json
"target": { "name": "com.example.orders.OrderServiceApp", "pid": 1, "jvm": "OpenJDK 21.0.8",
            "agent": "attach", "debugNonSafepoints": false,
            "via": { "kind": "kubectl", "context": "prod-tokyo", "namespace": "orders",
                     "pod": "orders-api-7f9c6d-x2x4q", "container": "app" } }
```

- `agent`: `startup` (`-javaagent`, default) or `attach`. Written by the agent (premain or agentmain).
- `debugNonSafepoints`: `false` when time in inlined methods is attributed to their callers. Written by the agent; absent when unknown (not HotSpot).
- `via`: absent for a local JVM; `{ "kind": "ssh", "host": "app@prod-api-1", "runAs": "batch" }` or the Kubernetes form above.
  Written by the GUI, which knows how it connected.

## Limits

- Distroless and other images without `sh`: the jar cannot be copied in, so start the JVM with the agent.
  (`kubectl debug --target` with an ephemeral container is a possible later path, but attach then has to cross containers.)
- Runtimes trimmed with jlink without `jdk.attach`, and `readOnlyRootFilesystem` without a writable `/tmp`, cannot be attached to.
- Windows targets over SSH are out of scope for now (`/proc/PID/exe` and `sh` are assumed on remote hosts).
- One GUI connects to one JVM at a time; several pods of a Deployment are profiled one by one.
- A remembered pod name goes stale with every rollout: the Start Center then takes a pod of the same Deployment (pod name minus `-hash-suffix`).
