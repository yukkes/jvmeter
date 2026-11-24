package jvmeter.agent;

import io.avaje.jsonb.Jsonb;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jvmeter.core.Live;

/**
 * The agent's end of the protocol: listens on 127.0.0.1 only, and a client must prove it knows the
 * token ({@link Live#proof}) after the agent has proved it does. The token reaches the GUI only
 * through `attach` / `list` output, that is, over the ssh / kubectl channel the user already
 * authenticated, and through a file only the JVM's user can read. One client at a time: the newest.
 * It listens on both loopback addresses, 127.0.0.1 and ::1, on the same port, as ssh / kubectl port
 * forwarding connect to either. Each on a socket of its own protocol family: Java's usual
 * dual-stack sockets bind 127.0.0.1 as ::ffff:127.0.0.1, which WSL2 breaks (a fixed port fails to
 * bind, and port 0 binds but accepts no IPv4).
 */
final class Server {

  private static volatile Jsonb jsonb;

  /** the adapters are built on first use: loading the agent must not pay for them */
  static Jsonb jsonb() {
    if (jsonb == null) {
      synchronized (Server.class) {
        if (jsonb == null) {
          jsonb = Jsonb.builder().build();
        }
      }
    }
    return jsonb;
  }

  final int port;
  final String token = Live.nonce();

  /**
   * connections that have not proved the token yet: each holds a thread for at most {@link
   * #HANDSHAKE_MS}, and more than these are closed at once
   */
  private final java.util.concurrent.Semaphore unproved = new java.util.concurrent.Semaphore(8);

  static final long HANDSHAKE_MS = 10_000;
  static final int MAX_COMMAND_LEN = 65_536;
  private static final int SEND_QUEUE = 64;
  private static final Live.Message DONE = new Live.Message("");

  private java.util.concurrent.BlockingQueue<Live.Message> queue;
  private Socket client;

  Server(int port) throws IOException {
    List<ServerSocket> sockets = new ArrayList<>();
    sockets.add(listen(StandardProtocolFamily.INET, "127.0.0.1", port));
    this.port = sockets.get(0).getLocalPort();
    try {
      sockets.add(listen(StandardProtocolFamily.INET6, "::1", this.port));
    } catch (IOException | UnsupportedOperationException noIpv6) {
      // IPv4 only
    }
    long pid = ProcessHandle.current().pid();
    try {
      AgentFile.write(
          pid,
          jsonb()
              .type(Object.class)
              .map()
              .toJson(Map.of("pid", pid, "port", this.port, "token", token)));
    } catch (IOException | RuntimeException e) {
      for (ServerSocket ss : sockets) {
        try {
          ss.close();
        } catch (IOException ignored) {
          // best effort
        }
      }
      throw e;
    }
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> AgentFile.delete(pid), "jvmeter-cleanup"));
    for (ServerSocket ss : sockets) {
      Thread t = new Thread(() -> accept(ss), "jvmeter-server");
      t.setDaemon(true);
      t.start();
    }
  }

  /** a listening socket of that family only (an IPv4 one is not ::ffff:127.0.0.1) */
  private static ServerSocket listen(StandardProtocolFamily family, String host, int port)
      throws IOException {
    ServerSocketChannel ch = ServerSocketChannel.open(family);
    try {
      ch.bind(new InetSocketAddress(host, port), 4);
    } catch (IOException e) {
      ch.close();
      throw e;
    }
    return ch.socket();
  }

  private void accept(ServerSocket ss) {
    while (true) {
      try {
        Socket s = ss.accept();
        if (!unproved.tryAcquire()) {
          s.close();
          continue;
        }
        Thread t = new Thread(() -> serve(s), "jvmeter-client");
        t.setDaemon(true);
        t.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket s) {
    try (s) {
      boolean proved;
      try {
        proved = handshake(s);
      } finally {
        unproved.release();
      }
      if (!proved) {
        return;
      }
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
      Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
      java.util.concurrent.BlockingQueue<Live.Message> q =
          new java.util.concurrent.ArrayBlockingQueue<>(SEND_QUEUE);
      // one client at a time: a new one (it knows the token) replaces the previous one, which may
      // be gone without
      // the agent noticing (a GUI that crashed, an ssh tunnel that dropped)
      synchronized (this) {
        if (queue != null) {
          finish(queue);
        }
        if (client != null) {
          client.close();
        }
        client = s;
        queue = q;
      }
      Thread sender = startSender(s, w, q);
      s.setSoTimeout(0);
      try {
        send(Agent.hello());
        for (String line; (line = readLine(in)) != null; ) {
          Live.Command c;
          try {
            c = jsonb().type(Live.Command.class).fromJson(line);
          } catch (RuntimeException e) {
            String shown = line.length() > 200 ? line.substring(0, 200) + "…" : line;
            send(error("Not a command: " + shown));
            continue;
          }
          if (c != null && c.cmd != null) {
            Agent.command(c);
          }
        }
      } finally {
        boolean current;
        synchronized (this) {
          current = client == s;
          if (current) {
            client = null;
            queue = null;
          }
        }
        finish(q);
        sender.interrupt();
        if (current) {
          Agent.disconnected();
        }
      }
    } catch (IOException e) {
      // the client went away
    }
  }

  /** a daemon thread that writes what the client is sent, until {@link #DONE} or an error */
  private static Thread startSender(
      Socket s, Writer w, java.util.concurrent.BlockingQueue<Live.Message> q) {
    Thread sender =
        new Thread(
            () -> {
              try {
                while (true) {
                  Live.Message m = q.take();
                  if (m == DONE) {
                    return;
                  }
                  write(w, m);
                }
              } catch (IOException | InterruptedException e) {
                try {
                  s.close();
                } catch (IOException ignored) {
                  // already closed
                }
              }
            },
            "jvmeter-send");
    sender.setDaemon(true);
    sender.start();
    return sender;
  }

  /**
   * drops what is still queued and puts {@link #DONE} in, even if another thread fills the queue
   */
  private static void finish(java.util.concurrent.BlockingQueue<Live.Message> q) {
    do {
      q.clear();
    } while (!q.offer(DONE));
  }

  /**
   * the client's nonce, the agent's proof and nonce, the client's proof: lines of bounded length,
   * all within {@link #HANDSHAKE_MS}
   */
  private boolean handshake(Socket s) throws IOException {
    long deadline = System.nanoTime() + HANDSHAKE_MS * 1_000_000;
    String theirs = Live.line(s, deadline), ours = Live.nonce();
    s.getOutputStream()
        .write(
            (Live.proof(token, "agent", theirs) + " " + ours + "\n")
                .getBytes(StandardCharsets.UTF_8));
    return Live.proves(Live.line(s, deadline), token, "gui", ours);
  }

  /** one command line of bounded length (null on EOF) */
  static String readLine(BufferedReader in) throws IOException {
    StringBuilder b = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        if (!b.isEmpty() && b.charAt(b.length() - 1) == '\r') {
          b.setLength(b.length() - 1);
        }
        return b.toString();
      }
      if (b.length() >= MAX_COMMAND_LEN) {
        throw new IOException("command line too long");
      }
      b.append((char) c);
    }
    return b.isEmpty() ? null : b.toString();
  }

  static Live.Message error(String message) {
    Live.Message m = new Live.Message("error");
    m.message = message;
    return m;
  }

  /** to the connected client, if any (non-blocking: never stalls the sampler) */
  synchronized void send(Live.Message m) {
    if (queue == null) {
      return;
    }
    if (queue.offer(m)) {
      return;
    }
    if ("tick".equals(m.type)) {
      return; // drop a tick when the client is behind
    }
    // make room for control / final messages by dropping a queued tick
    queue.removeIf(x -> "tick".equals(x.type));
    if (!queue.offer(m) && client != null) {
      try {
        client.close();
      } catch (IOException ignored) {
        // already closed
      }
    }
  }

  private static void write(Writer w, Live.Message m) throws IOException {
    w.write(jsonb().type(Live.Message.class).toJson(m));
    w.write('\n');
    w.flush();
  }
}
