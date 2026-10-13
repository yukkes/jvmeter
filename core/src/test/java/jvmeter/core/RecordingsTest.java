package jvmeter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingsTest {

  @TempDir Path dir;

  @Test
  void savedRecordingsAreListedNewestFirst() throws IOException {
    Snapshot old = Snapshot.loadSample();
    old.startedAt = "2026-10-01T10:05:12+09:00";
    Snapshot recent = Snapshot.loadSample();
    recent.startedAt = "2026-10-03T22:10:04+09:00";
    recent.target.via = new Snapshot.Via("ssh");
    recent.target.via.host = "app@prod-api-1";
    Path a = Recordings.save(new Session(old), dir);
    Path b = Recordings.save(new Session(recent), dir);
    assertTrue(
        a.getFileName().toString().endsWith("-OrderServiceApp-localhost-34567.json"), a.toString());
    assertTrue(
        b.getFileName().toString().endsWith("-OrderServiceApp-prod-api-1-34567.json"),
        b.toString());
    assertTrue(Files.readString(a).startsWith("{"), "plain JSON, readable as it is");
    Files.writeString(dir.resolve("notes.json"), "not a snapshot");
    Files.createFile(dir.resolve(Recordings.list(dir).get(0).name() + ".jfr.part"));

    List<Recordings.Entry> l = Recordings.list(dir);
    assertEquals(List.of(b, a), l.stream().map(Recordings.Entry::file).toList());
    assertEquals("app@prod-api-1", l.get(0).target().via.host);
    assertEquals(120, l.get(0).sec());
    assertTrue(l.get(0).part()); // its JFR file was left unfinished
    assertFalse(l.get(1).part());
    assertEquals(old.target.name, Snapshot.read(a).target.name);
  }

  @Test
  void aSecondRecordingInTheSameSecondGetsItsOwnFile() throws IOException {
    Session s = new Session(Snapshot.loadSample());
    Path a = Recordings.save(s, dir), b = Recordings.save(s, dir);
    assertEquals(
        a.getFileName().toString().replace(".json", "-2.json"), b.getFileName().toString());
  }

  /** a roaming profile copies the home directory at every logon, but not AppData\Local */
  @Test
  void onWindowsRecordingsGoToLocalAppData() {
    assertEquals(
        Path.of("C:\\Users\\a\\AppData\\Local", "jvmeter", "recordings"),
        Recordings.dir("Windows 11", "C:\\Users\\a", "C:\\Users\\a\\AppData\\Local"));
    assertEquals(
        Path.of("C:\\Users\\a", ".jvmeter", "recordings"),
        Recordings.dir("Windows 11", "C:\\Users\\a", null));
    assertEquals(
        Path.of("/home/a", ".jvmeter", "recordings"), Recordings.dir("Linux", "/home/a", "/x"));
    if (java.io.File.separatorChar == '/') {
      assertEquals(
          "~/.jvmeter/recordings",
          Recordings.label(Path.of(System.getProperty("user.home"), ".jvmeter", "recordings")));
    }
  }

  /** a file a virus scanner holds for a moment is moved once it lets go */
  @Test
  void theMoveIsTriedAgain() throws IOException {
    int[] tries = {0};
    Path f =
        Recordings.save(
            new Session(Snapshot.loadSample()),
            dir,
            (a, b) -> {
              if (++tries[0] < 3) {
                throw new java.nio.file.AccessDeniedException(a.toString());
              }
              Files.move(a, b);
            });
    assertEquals(3, tries[0]);
    assertEquals(List.of(f), Recordings.list(dir).stream().map(Recordings.Entry::file).toList());
  }

  /** a recording that cannot be moved into place is kept, and the error says where */
  @Test
  void aRecordingThatCannotBeMovedIsKept() throws IOException {
    IOException e =
        assertThrows(
            IOException.class,
            () ->
                Recordings.save(
                    new Session(Snapshot.loadSample()),
                    dir,
                    (a, b) -> {
                      throw new java.nio.file.AccessDeniedException(a.toString());
                    }));
    Path kept = Path.of(e.getMessage().substring(e.getMessage().indexOf("kept as ") + 8));
    assertEquals(34567, Snapshot.read(kept).target.pid);
  }

  /** the main class and the host come from the profiled JVM: they never leave the directory */
  @Test
  void namesFromTheJvmStayInsideTheDirectory() {
    Snapshot.Target t = new Snapshot.Target();
    t.name = "x/../../evil";
    t.pid = 7;
    t.via = new Snapshot.Via("kubectl");
    t.via.pod = "../pod";
    String n = Recordings.name(t, 0);
    assertFalse(n.contains("/") || n.contains("\\"), n);
    assertTrue(n.endsWith("-_evil-__pod-7"), n);
    assertEquals("_x", Recordings.safe("..x"));
  }
}
