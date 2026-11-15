package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class TaskCompletionTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
  private static final LocalDateTime NOW = DATE.atTime(12, 0);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Дела\n");
  private static final Markdown SOURCE =
      new Markdown("## Заметки\n\nпобрился\n\n## Дела\n\n- [ ] #task/hygiene Побрился\n");

  private static EditorState state(boolean fresh) {
    EditorState s = new EditorState();
    s.sourcePath = "today.md";
    s.destinationPath = "today.md";
    s.fresh = fresh;
    if (!fresh) {
      var b = SOURCE.blocks.get(0);
      s.start = b.start();
      s.end = b.end();
      s.text = b.text();
      s.original = b.text();
    }
    s.chooseTarget(
        SOURCE.targets(TEMPLATE).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow());
    return s;
  }

  @Test
  public void markOnlyConsumesSourceBlockWithoutAppendingItToTask() {
    var s = state(false);
    s.appendTaskText = false;
    String result = VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE, NOW).get(0).after();
    assertEquals("## Заметки\n\n## Дела\n\n- [x] #task/hygiene Побрился ✅ 2026-11-15\n", result);
  }

  @Test
  public void markOnlyAllowsEmptyEditorAndKeepsExistingTaskTextExactly() {
    var s = state(true);
    s.appendTaskText = false;
    String result = VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE, NOW).get(0).after();
    assertEquals(
        SOURCE.text.replace(
            "- [ ] #task/hygiene Побрился", "- [x] #task/hygiene Побрился ✅ 2026-11-15"),
        result);
  }

  @Test
  public void appendRemainsDefaultAndEmptyNewNoteDoesNotWrite() {
    var s = state(true);
    assertTrue(s.appendTaskText);
    assertTrue(VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE, NOW).isEmpty());
    s.text = "подробности";
    assertTrue(
        VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE, NOW)
            .get(0)
            .after()
            .contains("Побрился; подробности ✅ 2026-11-15"));
  }

  @Test
  public void markOnlyInAnotherDayWritesDestinationBeforeRemovingOriginal() {
    var s = state(false);
    s.destinationPath = "past.md";
    s.appendTaskText = false;
    var changes = VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE.minusDays(1), NOW);
    assertEquals("past.md", changes.get(0).path());
    assertTrue(changes.get(0).after().contains("Побрился ✅ 2026-11-14"));
    assertFalse(changes.get(0).after().contains("; побрился"));
    assertEquals("## Заметки\n\n## Дела\n\n- [ ] #task/hygiene Побрился\n", changes.get(1).after());
  }

  @Test
  public void selectingSectionOrAnotherDateResetsTaskMode() {
    var s = state(false);
    s.appendTaskText = false;
    s.chooseTarget(new Markdown.Target("Заметки", "## Заметки", -1, "", Markdown.Kind.SECTION));
    assertTrue(s.appendTaskText);
    s = state(false);
    s.appendTaskText = false;
    s.sourcePath = "2026-11-15-вс.md";
    s.chooseDay("2026-11-14-сб.md", "");
    assertTrue(s.appendTaskText);
  }

  @Test
  public void markOnlyDoesNotCreateUnusedPendingRoot() {
    var s = state(true);
    s.appendTaskText = false;
    s.rootPath = "ignored.md";
    var changes = VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, DATE, NOW);
    assertEquals(changes, VaultRepository.addRoot(changes, s));
  }

  @Test
  public void markOnlyNeverDeletesAnExistingAnchor() {
    Markdown anchored = new Markdown(SOURCE.text.replace("побрился", "побрился ^event-root"));
    var s = state(false);
    var b = anchored.blocks.get(0);
    s.original = b.text();
    s.text = b.text();
    s.start = b.start();
    s.end = b.end();
    s.chooseTarget(
        anchored.targets(TEMPLATE).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow());
    s.appendTaskText = false;
    assertThrows(
        IllegalArgumentException.class,
        () -> VaultRepository.plan(s, anchored, anchored, TEMPLATE, DATE, NOW));
  }

  @Test
  public void markOnlyKeepsAnExistingCompletionDateAndIsIdempotent() {
    Markdown done =
        new Markdown(
            SOURCE.text.replace(
                "- [ ] #task/hygiene Побрился", "- [x] #task/hygiene Побрился ✅ 2026-11-12"));
    var s = state(true);
    s.chooseTarget(
        done.targets(TEMPLATE).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow());
    s.appendTaskText = false;
    assertEquals(
        done.text, VaultRepository.plan(s, done, done, TEMPLATE, DATE, NOW).get(0).after());
  }
}
