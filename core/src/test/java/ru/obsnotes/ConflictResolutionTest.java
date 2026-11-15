package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class ConflictResolutionTest {
  @Test
  public void copiedFencedCodeKeepsLiteralDoubleColonsIncludingShorterNestedFence() {
    String text = "пример\n````text\nKey:: code\n```\nOther:: code\n````\nKey:: value";
    assertEquals(text.replace("Key:: value", "Key: value"), state(text).conflictCopyText());
  }

  @Test
  public void copiedInlineFieldsBecomeOrdinaryLabelsIncludingMixedAndNewText() {
    assertEquals("Key: value", state("Key:: value").conflictCopyText());
    assertEquals("текст\nKey: value", state("текст\nKey:: value").conflictCopyText());
    var s = state("добавление");
    s.kind = Markdown.Kind.FIELD;
    s.targetSource = "Weather:: существующее";
    assertEquals("Weather: добавление", s.conflictCopyText());
  }

  @Test
  public void copyKeepsCrLfAndRestoresOnlyMissingNotesHeading() throws Exception {
    var storage = new Storage();
    storage.files.put(PATH, "## Дела\r\n\r\nвнешнее\r\n");
    var result =
        ConflictResolution.resolve(
            new VaultRepository(storage, new Journal()),
            storage,
            state("раз\nдва"),
            TEMPLATE,
            true,
            NOW);
    assertTrue(result.document().text.contains("## Дела\r\n\r\nвнешнее\r\n"));
    assertTrue(result.document().text.contains("раз\r\nдва"));
    assertFalse(result.document().text.replace("\r\n", "").contains("\n"));
  }

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);
  private static final String PATH = DiaryLayout.path(NOW.toLocalDate());
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Дела\n");

  private static class Storage implements VaultStorage {
    Map<String, String> files = new HashMap<>();
    int writes = 0, reads = 0;
    boolean race;

    public String read(String path) throws IOException {
      reads++;
      if (race && reads == 2) files.put(path, files.get(path) + "\nпозже\n");
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      return files.get(path);
    }

    public void write(String path, String text) {
      writes++;
      assertTrue(files.containsKey(path));
      files.put(path, text);
    }

    public List<String> dailyNotes() {
      throw new AssertionError("No archive search");
    }
  }

  private static class Journal implements VaultRepository.Journal {
    List<VaultRepository.Change> changes = List.of();
    int completed;

    public void prepare(List<VaultRepository.Change> next) {
      changes = next;
    }

    public List<VaultRepository.Change> pending() {
      return changes;
    }

    public void complete() {
      changes = List.of();
      completed++;
    }
  }

  private static EditorState state(String text) {
    var s = new EditorState();
    s.sourcePath = PATH;
    s.destinationPath = "another.md";
    s.sourceSnapshot = "old";
    s.text = text;
    return s;
  }

  @Test
  public void copyAddsVisibleTextToLatestSourceAndKeepsExternalChangesAndOtherFile()
      throws Exception {
    var storage = new Storage();
    String external = "## Заметки\n\nвнешнее изменение\n\n\n## Дела\n\nработа\n";
    storage.files.put(PATH, external);
    storage.files.put("another.md", "другое");
    var journal = new Journal();
    var s = state("моя правка #crossOneLine/First");
    var result =
        ConflictResolution.resolve(
            new VaultRepository(storage, journal), storage, s, TEMPLATE, true, NOW);
    assertEquals(PATH, result.path());
    assertTrue(result.document().text.contains("внешнее изменение"));
    assertTrue(
        result.document().text.startsWith(external.substring(0, external.indexOf("## Дела"))));
    assertTrue(result.document().text.endsWith("## Дела\n\nработа\n"));
    assertTrue(result.document().text.contains("моя правка"));
    assertFalse(result.document().text.contains("#crossOneLine"));
    assertEquals("другое", storage.files.get("another.md"));
    assertEquals(1, storage.writes);
    assertEquals("моя правка #crossOneLine/First", s.text);
  }

  @Test
  public void discardOpensFreshFileWithoutWritingAndAcceptsPendingPartialTransfer()
      throws Exception {
    var storage = new Storage();
    storage.files.put(PATH, "## Заметки\nвнешняя версия\n");
    storage.files.put("another.md", "частичный перенос");
    var journal = new Journal();
    journal.changes = List.of(new VaultRepository.Change(PATH, "old", "planned"));
    var repo = new VaultRepository(storage, journal);
    assertThrows(VaultRepository.Conflict.class, repo::recover);
    var result =
        ConflictResolution.resolve(repo, storage, state("моя правка"), TEMPLATE, false, NOW);
    assertEquals(storage.files.get(PATH), result.document().text);
    assertEquals(0, storage.writes);
    assertTrue(journal.pending().isEmpty());
    assertEquals("частичный перенос", storage.files.get("another.md"));
  }

  @Test
  public void copyCannotDuplicateRootAnchorsOrActiveDataviewKeys() throws Exception {
    var s = state(Events.root("Поле:: моё"));
    s.fresh = false;
    s.initializeRoutes(TEMPLATE);
    assertEquals("Поле: моё", s.conflictCopyText());
    assertEquals("текст", state(Events.root("текст")).conflictCopyText());
    assertEquals("дополнение", state("[[day#^event-id|Корень]]\nдополнение").conflictCopyText());
    var storage = new Storage();
    storage.files.put(PATH, "## Заметки\n\nПоле:: внешнее\n");
    var result =
        ConflictResolution.resolve(
            new VaultRepository(storage, new Journal()), storage, s, TEMPLATE, true, NOW);
    assertTrue(result.document().text.contains("Поле:: внешнее"));
    assertTrue(result.document().text.contains("Поле: моё"));
    assertFalse(result.document().text.contains("^event-"));
  }

  @Test
  public void anotherExternalChangeDuringCopyIsRejectedWithoutOverwritingIt() throws Exception {
    var storage = new Storage();
    storage.files.put(PATH, "## Заметки\nвнешнее\n");
    storage.race = true;
    var s = state("моё");
    assertThrows(
        VaultRepository.Conflict.class,
        () ->
            ConflictResolution.resolve(
                new VaultRepository(storage, new Journal()), storage, s, TEMPLATE, true, NOW));
    assertEquals(0, storage.writes);
    assertTrue(storage.files.get(PATH).endsWith("позже\n"));
    assertEquals("моё", s.text);
  }

  @Test
  public void missingSourceCanBeDiscardedWithoutCreatingAnyFile() throws Exception {
    var storage = new Storage();
    var result =
        ConflictResolution.resolve(
            new VaultRepository(storage, new Journal()),
            storage,
            state("моё"),
            TEMPLATE,
            false,
            NOW);
    assertNull(result.document());
    assertEquals(0, storage.writes);
    assertTrue(storage.files.isEmpty());
  }

  @Test
  public void emptyCopyIsRejectedBeforeAcceptingJournalOrWriting() {
    var storage = new Storage();
    var journal = new Journal();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ConflictResolution.resolve(
                new VaultRepository(storage, journal), storage, state(""), TEMPLATE, true, NOW));
    assertEquals(0, journal.completed);
    assertEquals(0, storage.reads);
    assertEquals(0, storage.writes);
  }
}
