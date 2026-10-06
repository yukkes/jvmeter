package jvmeter.core;

import io.avaje.jsonb.Json;
import java.util.List;
import java.util.Map;

/**
 * The agent's protocol (docs/targets.md): one JSON object per line over 127.0.0.1. First both ends
 * prove they know the token without sending it ({@link #proof}): the GUI sends a nonce, the agent
 * answers with its proof and a nonce of its own, the GUI with its proof. Then the GUI sends {@link
 * Command}s; the agent answers with "hello" and, while recording, sends {@link Message}s.
 */
public final class Live {

  private Live() {}

  /** a fresh random nonce, 32 hex digits */
  public static String nonce() {
    byte[] b = new byte[16];
    new java.security.SecureRandom().nextBytes(b);
    return java.util.HexFormat.of().formatHex(b);
  }

  /**
   * HMAC-SHA256 of side ("agent" or "gui") and the other end's nonce, keyed with the token: whoever
   * answers on the port learns nothing it could reuse, and a proof for one side never serves the
   * other
   */
  public static String proof(String token, String side, String nonce) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              token.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(
              mac.doFinal((side + " " + nonce).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** whether proof is token's for side and nonce (in constant time) */
  public static boolean proves(String proof, String token, String side, String nonce) {
    return java.security.MessageDigest.isEqual(
        proof.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        proof(token, side, nonce).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * One handshake line, read byte by byte so nothing after it is consumed: at most 200 bytes, and
   * all of it before deadline (System.nanoTime), so a peer that sends slowly or endlessly is cut
   * off.
   */
  public static String line(java.net.Socket s, long deadline) throws java.io.IOException {
    java.io.InputStream in = s.getInputStream();
    StringBuilder b = new StringBuilder();
    while (true) {
      long left = (deadline - System.nanoTime()) / 1_000_000;
      if (left <= 0 || b.length() >= 200) {
        throw new java.io.IOException("no handshake");
      }
      s.setSoTimeout((int) left);
      int c = in.read();
      if (c < 0) {
        throw new java.io.EOFException("closed during the handshake");
      }
      if (c == '\n') {
        return b.toString().trim();
      }
      b.append((char) c);
    }
  }

  /**
   * From the agent. type: hello (target, heapMaxMB, xmsMB, collector), tick (every second while
   * recording: t, cpu, heap, threads, new gc events, states), cpu (every 5 s and at the end: tree,
   * methods, stacks, waits, allocations, generations), classes (a class histogram: t, classes), end
   * (the recording stopped), error (message).
   */
  @Json
  public static final class Message {
    public String type;
    public Snapshot.Target target;
    public Double heapMaxMB, xmsMB;
    public String collector;
    public Double t, cpu, heap, threads;
    public List<GcEvent> gc;
    public List<ThreadSec> states;
    public Snapshot.Node tree;
    public List<Snapshot.MethodCalls> methods;
    public Map<String, List<String>> stacks;
    public List<Snapshot.Wait> waits;
    public List<Snapshot.Allocation> allocations;
    public Snapshot.Generations generations;
    public List<ClassStat> classes;
    public String message;

    public Message(String type) {
      this.type = type;
    }
  }

  /**
   * a thread in the second that just ended: its state then (null: it ended) and its seconds per
   * state so far
   */
  @Json
  public static final class ThreadSec {
    public String name, state;
    public double run, wait, block, io;
  }

  /**
   * from the GUI. cmd: start (include: the packages to count), stop, gc (System.gc(), then a class
   * histogram), histogram
   */
  @Json
  public static final class Command {
    public String cmd, include;

    public Command(String cmd, String include) {
      this.cmd = cmd;
      this.include = include;
    }
  }
}
