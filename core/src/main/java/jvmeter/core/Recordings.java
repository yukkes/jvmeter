package jvmeter.core;

import io.avaje.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where every recording is saved when it stops: NAME.json (the snapshot) and NAME.jfr (the JFR
 * file) in ~/.jvmeter/recordings, or on Windows in %LOCALAPPDATA%\jvmeter\recordings (a roaming
 * profile copies the rest of the home directory at every logon). NAME.jfr.part is the JFR file of a
 * recording that ended unexpectedly (the JVM or the connection went away). NAME is the start time,
 * the main class, where the JVM ran and its pid, so the files sort by time and the two of a
 * recording share it.
 */
public final class Recordings {

  private static final String SNAPSHOT = ".json", PART = ".jfr.part";

  private Recordings() {}

  public static Path dir() {
    return dir(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        System.getenv("LOCALAPPDATA"));
  }

  static Path dir(String os, String home, String localAppData) {
    return os.startsWith("Windows") && localAppData != null && !localAppData.isBlank()
        ? Path.of(localAppData, "jvmeter", "recordings")
        : Path.of(home, ".jvmeter", "recordings");
  }

  /** dir as the GUI shows it: ~/… where ~ is understood, else the full path */
  public static String label(Path dir) {
    Path home = Path.of(System.getProperty("user.home"));
    return java.io.File.separatorChar == '/' && dir.startsWith(home)
        ? "~/" + home.relativize(dir)
        : dir.toString();
  }

  /** a saved recording: its snapshot file and what the start of that file says */
  public record Entry(Path file, Snapshot.Target target, long startMs, int sec, boolean part) {

    /** NAME, the part the snapshot and the JFR file share */
    public String name() {
      return base(file);
    }
  }

  private static String base(Path f) {
    String n = f.getFileName().toString();
    return n.substring(0, n.length() - SNAPSHOT.length());
  }

  /** 20261004-143000-OrderServiceApp-localhost-34567 */
  static String name(Snapshot.Target t, long startMs) {
    Snapshot.Via v = t.via;
    String host =
        v == null || v.kind.equals("local")
            ? "localhost"
            : v.kind.equals("ssh") && v.host != null
                ? v.host.substring(v.host.indexOf('@') + 1)
                : v.pod;
    return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()))
        + "-"
        + safe(t.name == null ? null : t.name.substring(t.name.lastIndexOf('.') + 1))
        + "-"
        + safe(host)
        + "-"
        + t.pid;
  }

  /**
   * the main class and the host come from the profiled JVM and what was typed: only letters,
   * digits, '.', '_' and '-' get into a file name (no separators, no "..")
   */
  static String safe(String s) {
    String x = s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "_");
    return x.isEmpty() ? "_" : x.substring(0, Math.min(60, x.length()));
  }

  /** Writes the session's snapshot as dir/NAME.json with {@link #write}; returns the file */
  public static Path save(Session s, Path dir) throws IOException {
    return save(s, dir, ATOMIC);
  }

  interface Move {
    void move(Path from, Path to) throws IOException;
  }

  private static final Move ATOMIC = (a, b) -> Files.move(a, b, StandardCopyOption.ATOMIC_MOVE);

  static Path save(Session s, Path dir, Move move) throws IOException {
    create(dir);
    String name = name(s.snap.target, s.startMs);
    Path f = dir.resolve(name + SNAPSHOT);
    for (int i = 2; Files.exists(f); i++) {
      f = dir.resolve(name + "-" + i + SNAPSHOT); // two recordings started in the same second
    }
    write(s, f, move);
    return f;
  }

  /**
   * Writes the session's snapshot to file, readable by this user only: written whole to a temporary
   * file next to it, then moved into place, so a crash never leaves half a snapshot. The move is
   * tried again for a while (on Windows a virus scanner or the indexer may hold a file just
   * written); if it still fails, the written file is kept and the error names it.
   */
  public static void write(Session s, Path file) throws IOException {
    write(s, file, ATOMIC);
  }

  static void write(Session s, Path file, Move move) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    String base = file.getFileName().toString();
    Path tmp;
    try {
      tmp =
          Files.createTempFile(
              dir,
              base + "-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException windows) {
      tmp = Files.createTempFile(dir, base + "-", ".tmp");
    }
    try {
      SnapshotWriter.write(s, tmp);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(tmp); // half a snapshot
      throw e;
    }
    for (int i = 1; ; i++) {
      try {
        move.move(tmp, file);
        return;
      } catch (IOException e) {
        if (i == 5) {
          throw new IOException(
              "could not name it " + file.getFileName() + " (" + e + "); it is kept as " + tmp, e);
        }
      }
      try {
        Thread.sleep(100L * i);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted; the snapshot is kept as " + tmp, e);
      }
    }
  }

  /** dir, created readable by this user only when missing */
  public static void create(Path dir) throws IOException {
    try {
      Files.createDirectories(
          dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (UnsupportedOperationException windows) {
      Files.createDirectories(dir);
    }
  }

  /** the recordings in dir, newest first; files that are not snapshots are left out */
  public static List<Entry> list(Path dir) {
    List<Entry> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SNAPSHOT)) {
      for (Path f : ds) {
        Entry e = head(f);
        if (e != null) {
          out.add(e);
        }
      }
    } catch (IOException e) {
      // what was read so far
    }
    out.sort(Comparator.comparingLong(Entry::startMs).reversed());
    return out;
  }

  /**
   * what a list shows, read from the start of the file only: target, startedAt and durationSec come
   * right after about and summary; null when it is not a snapshot
   */
  static Entry head(Path f) {
    try (InputStream in = Snapshot.open(f);
        JsonReader r = Snapshot.JSONB.reader(in)) {
      Snapshot.Target t = null;
      String startedAt = null;
      Double sec = null;
      r.beginObject();
      while (r.hasNextField() && (t == null || startedAt == null || sec == null)) {
        switch (r.nextField()) {
          case "target" -> t = Snapshot.JSONB.adapter(Snapshot.Target.class).fromJson(r);
          case "startedAt" -> startedAt = r.isNullValue() ? null : r.readString();
          case "durationSec" -> sec = r.readDouble();
          default -> r.skipValue();
        }
      }
      if (t == null || t.name == null) {
        return null;
      }
      return new Entry(
          f,
          t,
          startedAt != null
              ? OffsetDateTime.parse(startedAt).toInstant().toEpochMilli()
              : Files.getLastModifiedTime(f).toMillis(),
          sec != null ? (int) Math.round(sec) : 0,
          Files.exists(f.resolveSibling(base(f) + PART)));
    } catch (IOException | RuntimeException e) {
      return null; // unreadable, or not a snapshot
    }
  }
}
