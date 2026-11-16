package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class CleanupRulesTest {
  private static final String HEADER = "## Пустые маркеры\n";

  @Test
  public void parsesOnlyTheMarkerSectionAndNormalizesCaseWhitespaceAndDuplicates() {
    var rules =
        CleanupRules.parse(
            "# Пояснения\n- не маркер\n"
                + HEADER
                + "\n-  Свой Маркер  \n- свой маркер\n- .\n- ,\n## Другое\n- тоже не маркер\n");
    assertEquals(Set.of("свой маркер", ".", ","), rules.markers);
    assertThrows(UnsupportedOperationException.class, () -> rules.markers.add("x"));
  }

  @Test
  public void skipsFrontmatterCodeAndQuotedExamplesAndAcceptsBomAndCrLf() {
    String source =
        "\uFEFF---\nignored: true\n---\n````md\n"
            + HEADER
            + "- пример\n```\n````\n"
            + HEADER
            + "> - цитата\n- Нужный\n~~~md\n- код\n~~~\n";
    assertEquals(Set.of("нужный"), CleanupRules.parse(source.replace("\n", "\r\n")).markers);
  }

  @Test
  public void anExplicitEmptyListDisablesMarkerDeletionButNotWhitespaceCompression() {
    var empty = CleanupRules.parse(HEADER);
    assertTrue(empty.markers.isEmpty());
    assertEquals("текст\n\nвчера\n", DiaryCleanup.compress("текст\n\n\nвчера\n", empty));
    assertEquals("вчера\n", DiaryCleanup.compress("вчера\n"));
  }

  @Test
  public void invalidOrDuplicateSectionsAreRejectedInsteadOfActivatingAnUnexpectedList() {
    assertThrows(IllegalArgumentException.class, () -> CleanupRules.parse("- вчера\n"));
    assertThrows(IllegalArgumentException.class, () -> CleanupRules.parse(HEADER + HEADER));
    assertThrows(
        IllegalArgumentException.class, () -> CleanupRules.parse("```md\n" + HEADER + "```\n"));
  }

  private static class Storage implements VaultStorage {
    String config;
    IOException error;
    final List<String> reads = new ArrayList<>();

    public String read(String path) throws IOException {
      reads.add(path);
      if (error != null) throw error;
      if (config == null) throw new FileNotFoundException(path);
      return config;
    }

    public void write(String path, String text) {
      throw new AssertionError("No writes while loading rules");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("No enumeration for a configuration file");
    }
  }

  @Test
  public void loadsOnlyTheExactSharedPathAndSeesEditsOnTheNextOperation() throws Exception {
    var storage = new Storage();
    assertSame(CleanupRules.EMPTY, CleanupRules.load(storage));
    storage.config = HEADER + "- Первый\n";
    var first = CleanupRules.load(storage);
    storage.config = HEADER + "- Второй\n";
    var second = CleanupRules.load(storage);
    assertEquals(Set.of("первый"), first.markers);
    assertEquals(Set.of("второй"), second.markers);
    assertEquals(Collections.nCopies(3, CleanupRules.PATH), storage.reads);
  }

  @Test
  public void unreadableOrMalformedConfigurationDoesNotFallBackToWordDeletion() {
    var storage = new Storage();
    storage.config = "повреждённый файл";
    assertThrows(IOException.class, () -> CleanupRules.load(storage));
    storage.error = new IOException("Нет доступа");
    assertEquals(
        "Нет доступа",
        assertThrows(IOException.class, () -> CleanupRules.load(storage)).getMessage());
  }

  @Test
  public void customMarkersApplyOnlyToFullEmptyLinesAndProtectedRegionsSurvive() {
    var rules = CleanupRules.parse(HEADER + "- СвойМаркер\n");
    String source =
        "## Заметки\n\n СвойМаркер \n\nСвойМаркер в предложении\n\n"
            + "СвойМаркер\nтекст под маркером\n\nвчера\n\n"
            + "```text\nСвойМаркер\n\n```\n\n> СвойМаркер\n>\n";
    String result = DiaryCleanup.compress(source, rules);
    assertFalse(result.contains("\n СвойМаркер \n"));
    assertTrue(result.contains("СвойМаркер в предложении"));
    assertTrue(result.contains("СвойМаркер\nтекст под маркером"));
    assertTrue(result.contains("вчера\n"));
    assertTrue(result.contains("```text\nСвойМаркер\n\n```"));
    assertTrue(result.contains("> СвойМаркер\n>\n"));
  }

  @Test
  public void configuredRulesReachBothMoveFilesDeletionAndDoNotCompressAnOrdinaryEdit() {
    var rules = CleanupRules.parse(HEADER + "- СвойМаркер\n");
    var from = new Markdown("## Заметки\n\nзапись\n\nСвойМаркер\n\n\n## Дела\n");
    var to = new Markdown("## Заметки\n\nСвойМаркер\n\n## Дела\n");
    var b = from.blocks.get(0);
    var form = new EditorState();
    form.fresh = false;
    form.original = form.text = b.text();
    form.start = b.start();
    form.end = b.end();
    form.sourcePath = "source.md";
    form.destinationPath = "target.md";
    form.heading = "## Дела";
    var moved =
        VaultRepository.plan(
            form, from, to, to, LocalDate.of(2026, 11, 16), LocalDateTime.now(), rules);
    assertEquals(2, moved.size());
    for (var c : moved) assertFalse(c.after().contains("СвойМаркер"));
    assertEquals(to.text, moved.get(0).before());
    assertEquals(from.text, moved.get(1).before());
    form.destinationPath = form.sourcePath;
    var within =
        VaultRepository.plan(form, from, from, from, LocalDate.now(), LocalDateTime.now(), rules);
    assertFalse(within.get(0).after().contains("СвойМаркер"));
    assertFalse(DayEdits.delete(from, b, rules).text.contains("СвойМаркер"));
    form.heading = "## Заметки";
    form.text = "исправление";
    assertTrue(
        VaultRepository.plan(form, from, from, from, LocalDate.now(), LocalDateTime.now(), rules)
            .get(0)
            .after()
            .contains("СвойМаркер\n\n\n"));
  }
}
