package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class EditorTest {
  @Test
  public void savingRootKeepsAnchorAttachedAfterTrailingBlankLines() {
    EditorState s = new EditorState();
    s.text = Events.root("корень");
    s.edit("  новая запись\n\n");
    String result = s.savedText();
    assertTrue(result.startsWith("  новая запись ^event-"));
    assertEquals(1, new Markdown("## Заметки\n\n" + result).blocks.size());
    s.edit(" \n");
    assertThrows(IllegalArgumentException.class, s::savedText);
  }

  @Test
  public void switchingToPastDayResetsTaskDestinationAndReturningKeepsOriginalSnapshot() {
    EditorState s = new EditorState();
    s.sourcePath = "2026-11-15-вс.md";
    s.destinationPath = s.sourcePath;
    s.sourceSnapshot = "original";
    s.chooseTarget(new Markdown.Target("task", "## Утро", 12, "- [ ] дело", Markdown.Kind.TASK));
    s.chooseDay("2026-11-14-сб.md", "past");
    assertEquals(Markdown.Kind.SECTION, s.kind);
    assertEquals(-1, s.targetLine);
    assertEquals("past", s.destinationSnapshot);
    s.chooseDay(s.sourcePath, "new external version");
    assertEquals("original", s.destinationSnapshot);
    s.chooseDay("2026-11-16-пн.md", "future existing file");
    assertEquals("future existing file", s.destinationSnapshot);
  }

  @Test
  public void formRejectsMovingIntoOwnTask() {
    EditorState s = new EditorState();
    s.sourcePath = "same";
    s.destinationPath = "same";
    s.fresh = false;
    s.start = 10;
    s.end = 30;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            s.chooseTarget(
                new Markdown.Target("task", "## Утро", 15, "- [ ] own", Markdown.Kind.TASK)));
  }

  @Test
  public void windowsLineEndingsDoNotLeakAnchorIntoEditor() {
    EditorState s = new EditorState();
    s.text = Events.root("пример").replace("\n", "\r\n");
    String id = Events.id(s.text);
    assertEquals("пример", s.editableText());
    s.edit("правка");
    assertEquals(id, Events.id(s.text));
    assertEquals("правка", s.editableText());
  }

  @Test
  public void unsupportedNumericAliasesAreNotOfferedToObsidianScript() {
    Markdown template =
        new Markdown(
            DiaryTest.TEMPLATE
                + "\n"
                + "## Перекус 1\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Пер1\", heading: \"##"
                + " Перекус 1\"});\n"
                + "```\n");
    assertTrue(
        CrossReferences.destinations(template).stream().anyMatch(d -> d.id().equals("Пер1")));
    assertFalse(
        CrossReferences.selectable(template, false).stream().anyMatch(d -> d.id().equals("Пер1")));
    assertTrue(
        CrossReferences.selectable(template, true).stream()
            .allMatch(d -> Set.of("Лена", "Аня", "Вика", "Мама").contains(d.id())));
  }

  @Test
  public void eventPickerIncludesUnmarkedCandidatesWithoutIncludingCurrentBlock() {
    Markdown m =
        new Markdown(
            "## Заметки\n\nтекущий\n\n## Утро\n\nкандидат\n\n" + Events.root("корневой") + "\n");
    var roots = new DiaryIndex().roots("day.md", m, m.blocks.get(0).start());
    assertEquals(2, roots.size());
    assertTrue(roots.get(0).id().startsWith("event-"));
    assertEquals("", roots.get(1).id());
  }
}
