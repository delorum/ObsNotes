package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class RepositoryTest {
  static class Storage implements VaultStorage {
    Map<String, String> files = new HashMap<>(Map.of("past", "old past", "today", "old today"));
    int writes = 0;
    int failAt = 0;
    boolean corrupt = false;

    public String read(String p) throws IOException {
      if (!files.containsKey(p)) throw new FileNotFoundException(p);
      return files.get(p);
    }

    public void write(String p, String value) throws IOException {
      if (++writes == failAt) throw new IOException("interrupted");
      if (!files.containsKey(p)) throw new FileNotFoundException(p);
      files.put(p, corrupt ? "damaged" : value);
    }

    public List<String> dailyNotes() {
      return List.copyOf(files.keySet());
    }
  }

  private final List<VaultRepository.Change> changes =
      List.of(
          new VaultRepository.Change("past", "old past", "new past"),
          new VaultRepository.Change("today", "old today", "new today"));
  private Path dir;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("notes-journal-test");
  }

  @After
  public void cleanup() throws IOException {
    try (var files = Files.walk(dir)) {
      for (Path p :
          files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
        Files.delete(p);
    }
  }

  @Test
  public void conflictDoesNotWriteAnyFile() throws IOException {
    Storage storage = new Storage();
    storage.files.put("today", "external edit");
    FileJournal journal = new FileJournal(dir);
    assertThrows(
        VaultRepository.Conflict.class,
        () -> new VaultRepository(storage, journal).commit(changes));
    assertEquals(0, storage.writes);
    assertTrue(journal.pending().isEmpty());
  }

  @Test
  public void interruptedMoveRecoversWithoutDuplicatingDestination() throws IOException {
    Storage storage = new Storage();
    storage.failAt = 2;
    assertThrows(
        IOException.class,
        () -> new VaultRepository(storage, new FileJournal(dir)).commit(changes));
    assertEquals("new past", storage.read("past"));
    assertEquals("old today", storage.read("today"));
    new VaultRepository(storage, new FileJournal(dir)).recover();
    assertEquals("new today", storage.read("today"));
    assertEquals(3, storage.writes);
    assertTrue(new FileJournal(dir).pending().isEmpty());
  }

  @Test
  public void recoveryNeverOverwritesNewSyncthingEdits() throws IOException {
    Storage storage = new Storage();
    storage.failAt = 2;
    var repo = new VaultRepository(storage, new FileJournal(dir));
    assertThrows(IOException.class, () -> repo.commit(changes));
    storage.files.put("past", "new external");
    assertThrows(VaultRepository.Conflict.class, repo::recover);
    assertEquals("old today", storage.read("today"));
  }

  @Test
  public void failedDestinationVerificationLeavesSourceIntact() throws IOException {
    Storage storage = new Storage();
    storage.corrupt = true;
    assertThrows(
        IOException.class,
        () -> new VaultRepository(storage, new FileJournal(dir)).commit(changes));
    assertEquals("old today", storage.read("today"));
    assertFalse(new FileJournal(dir).pending().isEmpty());
  }

  @Test
  public void missingDayIsNeverCreated() throws IOException {
    Storage storage = new Storage();
    storage.files.remove("past");
    assertThrows(
        FileNotFoundException.class,
        () -> new VaultRepository(storage, new FileJournal(dir)).commit(changes));
    assertFalse(storage.files.containsKey("past"));
    assertEquals(0, storage.writes);
  }

  @Test
  public void backupsRetainTenTransactions() throws IOException {
    FileJournal j = new FileJournal(dir);
    for (int i = 0; i < 14; i++) {
      j.prepare(changes);
      j.complete();
    }
    try (var files = Files.list(dir)) {
      assertEquals(10, files.count());
    }
  }
}
