package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

/** Back, Today and background exits use the same save plan as the explicit save button. */
public class ExitSaveTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 16);
  private static final LocalDateTime NOW = DATE.atTime(12, 0);

  private EditorState editing(Markdown m) {
    var b = m.blocks.get(0);
    var s = new EditorState();
    s.sourcePath = s.destinationPath = "2026-11-16-пн.md";
    s.sourceSnapshot = s.destinationSnapshot = m.text;
    s.fresh = false;
    s.start = b.start();
    s.end = b.end();
    s.text = s.original = b.text();
    s.initializeRoutes(m);
    return s;
  }

  @Test
  public void blankNewNoteOrMetadataAloneMakesNoChanges() {
    var m = new Markdown("## Заметки\n\n");
    var s = new EditorState();
    s.text = "  #crossPeople/Лена";
    assertTrue(s.emptyNote());
    assertTrue(VaultRepository.plan(s, m, m, m, DATE, NOW).isEmpty());
  }

  @Test
  public void clearedExistingTextRemovesSourceAndOrphanTimestamp() {
    var m = new Markdown("## Заметки\n\n2026-11-16 10:00:00\nтекст\n\n## Дела\n");
    var s = editing(m);
    s.edit("");
    s.rootPath = "unused.md";
    s.destinationPath = "past.md";
    var changes = VaultRepository.plan(s, m, m, m, DATE, NOW);
    assertEquals(1, changes.size());
    assertEquals(s.sourcePath, changes.get(0).path());
    assertEquals("## Заметки\n\n## Дела\n", changes.get(0).after());
  }

  @Test
  public void clearedValueKeepsItsDataviewKey() {
    var m = new Markdown("## Заметки\n\nShortDescr:: события\n");
    var s = editing(m);
    s.editField(0, "");
    assertFalse(s.emptyNote());
    assertTrue(
        VaultRepository.plan(s, m, m, m, DATE, NOW).get(0).after().contains("ShortDescr::\n"));
  }

  @Test
  public void rootCannotBeDeletedByEmptyingTextOnExit() {
    var m = new Markdown("## Заметки\n\n" + Events.root("событие") + "\n");
    var s = editing(m);
    s.edit("");
    assertThrows(IllegalArgumentException.class, () -> VaultRepository.plan(s, m, m, m, DATE, NOW));
  }

  @Test
  public void ordinaryExitPlanSavesCurrentTextInPlaceWithoutDuplicatingIt() {
    var m = new Markdown("## Заметки\n\nтекст\n");
    var s = editing(m);
    s.edit("текущий текст");
    var changes = VaultRepository.plan(s, m, m, m, DATE, NOW);
    assertEquals(
        List.of(new VaultRepository.Change(s.sourcePath, m.text, "## Заметки\n\nтекущий текст\n")),
        changes);
  }
}
