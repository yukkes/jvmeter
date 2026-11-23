package jvmeter.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Where a running agent leaves its port and token, so `list` can connect to it again:
 * agent-PID.json in ~/.jvmeter, or in TMPDIR/jvmeter-USER when the home directory cannot be written
 * (as in many containers). The directory and the file are this user's only: the token lets a client
 * control the agent.
 */
final class AgentFile {

  private AgentFile() {}

  /**
   * this user's directory, created when missing; null when there is none that only this user owns
   */
  static Path dir() {
    String user = System.getProperty("user.name");
    for (Path d :
        List.of(
            Path.of(System.getProperty("user.home"), ".jvmeter"),
            Path.of(System.getProperty("java.io.tmpdir"), "jvmeter-" + user))) {
      try {
        try {
          Files.createDirectories(
              d,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException windows) {
          Files.createDirectories(d);
        } catch (FileAlreadyExistsException link) {
          continue; // a file or a dangling link in the way
        }
        // a directory someone else created (in a shared /tmp) could be read by them
        if (Files.isWritable(d)
            && ownerMatches(Files.getOwner(d).getName(), user)
            && !Files.isSymbolicLink(d)) {
          return d;
        }
      } catch (IOException | RuntimeException e) {
        // try the next one
      }
    }
    return null;
  }

  /** Windows domain accounts report owner as DOMAIN backslash user while user.name is user */
  static boolean ownerMatches(String owner, String user) {
    return owner != null && user != null && (owner.equals(user) || owner.endsWith("\\" + user));
  }

  static Path of(long pid) {
    Path d = dir();
    return d == null ? null : d.resolve("agent-" + pid + ".json");
  }

  /** written whole (a temporary file moved into place), readable by this user only */
  static void write(long pid, String json) throws IOException {
    Path f = of(pid);
    if (f == null) {
      throw new IOException(
          "no directory only this user can write (~/.jvmeter or TMPDIR/jvmeter-USER)");
    }
    Path tmp;
    try {
      tmp =
          Files.createTempFile(
              f.getParent(),
              "agent-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException windows) {
      tmp = Files.createTempFile(f.getParent(), "agent-", ".tmp");
    }
    Files.writeString(tmp, json, StandardCharsets.UTF_8);
    try {
      Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
      Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String read(long pid) {
    try {
      Path f = of(pid);
      if (f == null || !Files.isRegularFile(f)) {
        return null;
      }
      var started = ProcessHandle.of(pid).flatMap(p -> p.info().startInstant());
      if (started.isPresent()
          && Files.getLastModifiedTime(f).toInstant().isBefore(started.get().minusSeconds(5))) {
        Files.deleteIfExists(f); // stale file from an earlier process that had this PID
        return null;
      }
      return Files.readString(f);
    } catch (IOException e) {
      return null;
    }
  }

  static void delete(long pid) {
    try {
      Path f = of(pid);
      if (f != null) {
        Files.deleteIfExists(f);
      }
    } catch (IOException e) {
      // left behind; list skips files of JVMs that are gone
    }
  }
}
