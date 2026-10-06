package jvmeter.core;

import io.avaje.json.JsonException;
import io.avaje.jsonb.Jsonb;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The GUI's end of the agent protocol ({@link Live}): connects to 127.0.0.1 (or ::1), checks that
 * the agent knows the token and proves it knows it too (never sending the token itself: whatever
 * holds the port may not be the agent), then sends commands.
 */
public final class AgentClient implements Closeable {

  private static final Jsonb JSONB = Jsonb.builder().build();
  private static final io.avaje.jsonb.JsonType<Live.Message> MSG = JSONB.type(Live.Message.class);
  private static final io.avaje.jsonb.JsonType<Live.Command> CMD = JSONB.type(Live.Command.class);

  private final Socket socket;
  private final Writer out;
  private volatile boolean closed;

  /**
   * Connects to the first of the ports where the agent answers (a port forward accepts even when it
   * cannot reach the agent, and then closes). Messages and the end of the connection are reported
   * on the reader thread; onClose gets why (null after {@link #close}).
   *
   * @throws IOException when the agent answers on none of them
   */
  public AgentClient(
      int[] ports, String token, Consumer<Live.Message> onMessage, Consumer<String> onClose)
      throws IOException {
    Socket s = null;
    BufferedReader r = null;
    String first = null;
    IOException last = new IOException("no port");
    for (int port : ports) {
      try {
        s = connect(port);
        if (handshake(s, token)) {
          r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
          first = r.readLine();
          if (first != null) {
            break;
          }
        }
        last =
            new IOException(
                "Something other than the agent answers on port "
                    + port
                    + " (or a wrong token); the token was not sent.");
      } catch (java.io.EOFException e) {
        last =
            new IOException(
                "The agent closed the connection at once (nothing behind the port forward, or an"
                    + " older agent: restart that JVM).");
      } catch (IOException e) {
        last = e;
      }
      if (s != null) {
        s.close();
      }
    }
    if (first == null) {
      throw last;
    }
    s.setSoTimeout(0);
    socket = s;
    out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    BufferedReader in = r;
    String hello = first;
    Thread t =
        new Thread(
            () -> {
              String why = "The JVM closed the connection (it may have exited).";
              try {
                onMessage.accept(MSG.fromJson(hello));
                for (String line; (line = in.readLine()) != null; ) {
                  onMessage.accept(MSG.fromJson(line));
                }
              } catch (IOException | JsonException e) {
                why = "The connection to the JVM was lost: " + e.getMessage();
              }
              onClose.accept(closed ? null : why);
            },
            "jvmeter-agent-client");
    t.setDaemon(true);
    t.start();
  }

  /**
   * our nonce, the agent's proof of the token for it and the agent's nonce, our proof for that:
   * false when the answer is no proof (then nothing derived from the token was sent)
   */
  private static boolean handshake(Socket s, String token) throws IOException {
    long deadline = System.nanoTime() + 15_000_000_000L;
    String ours = Live.nonce();
    s.getOutputStream().write((ours + "\n").getBytes(StandardCharsets.UTF_8));
    String[] answer = Live.line(s, deadline).split(" ");
    if (answer.length != 2 || !Live.proves(answer[0], token, "agent", ours)) {
      return false;
    }
    s.getOutputStream()
        .write((Live.proof(token, "gui", answer[1]) + "\n").getBytes(StandardCharsets.UTF_8));
    s.setSoTimeout(15_000);
    return true;
  }

  /** the agent listens on both loopback addresses; some systems accept only one of them */
  private static Socket connect(int port) throws IOException {
    IOException last = null;
    for (String host : new String[] {"127.0.0.1", "::1"}) {
      Socket s = new Socket();
      try {
        s.connect(new InetSocketAddress(host, port), 3000);
        return s;
      } catch (IOException e) {
        s.close();
        last = e;
      }
    }
    throw last;
  }

  public synchronized void send(Live.Command c) {
    try {
      out.write(CMD.toJson(c) + "\n");
      out.flush();
    } catch (IOException e) {
      // the reader thread reports the closed connection
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      socket.close();
    } catch (IOException e) {
      // already closed
    }
  }
}
