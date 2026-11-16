package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class EditorOpeningTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 16);
  private static final LocalDateTime NOW = DATE.atTime(12, 0);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Семья\n\n"
              + "### Вложенный\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"FamilyNested\"});\n"
              + "```\n\n"
              + "## Поля\n");
  private static final Markdown SOURCE =
      new Markdown(
          "## Заметки\n\nстарый текст\n\n## Семья\n\n### Вложенный\n\nсоседняя запись\n\n");

  @Test
  public void addDirectlyToNestedSectionSetsPrimaryRouteAndWritesOnlyThere() {
    var state = EditorState.newNote(PATH, SOURCE, TEMPLATE, "### Вложенный");
    assertTrue(state.fresh);
    assertEquals(PATH, state.sourcePath);
    assertEquals(PATH, state.destinationPath);
    assertEquals(SOURCE.text, state.sourceSnapshot);
    assertEquals(SOURCE.text, state.destinationSnapshot);
    assertEquals("### Вложенный", state.heading);
    assertEquals("### Вложенный", state.fallbackHeading);
    assertEquals(1, state.pickerRoutes().size());
    assertEquals("FamilyNested", state.routes.get(0).crossId());
    assertTrue(state.crossIds().isEmpty());
    state.edit("новая запись");
    var changes = VaultRepository.plan(state, SOURCE, SOURCE, TEMPLATE, DATE, NOW);
    assertEquals(1, changes.size());
    assertEquals(PATH, changes.get(0).path());
    String text = changes.get(0).after();
    assertTrue(text.contains("## Заметки\n\nстарый текст\n"));
    assertTrue(text.indexOf("новая запись") > text.indexOf("### Вложенный"));
    assertFalse(text.contains("#crossPeople/FamilyNested"));
    assertFalse(text.contains("2026-11-16 12:00:00"));
  }

  @Test
  public void emptyNewSectionEntryMakesNoFileChangeAndNoTimestamp() {
    var state = EditorState.newNote(PATH, SOURCE, TEMPLATE, "### Вложенный");
    assertEquals("", state.editableText());
    assertEquals(new EditorState.InputCaret(0, 0), state.inputCaret());
    assertTrue(VaultRepository.plan(state, SOURCE, SOURCE, TEMPLATE, DATE, NOW).isEmpty());
    assertEquals(
        "## Заметки\n\nстарый текст\n\n## Семья\n\n### Вложенный\n\nсоседняя запись\n\n",
        SOURCE.text);
  }

  @Test
  public void templateOnlyDestinationIsRestoredInTheSameExistingDayAndUnknownHeadingIsRejected() {
    var state = EditorState.newNote(PATH, SOURCE, TEMPLATE, "## Поля");
    state.edit("добавлено сразу");
    var changes = VaultRepository.plan(state, SOURCE, SOURCE, TEMPLATE, DATE, NOW);
    assertEquals(1, changes.size());
    assertEquals(PATH, changes.get(0).path());
    assertTrue(changes.get(0).after().contains("## Поля\n\nдобавлено сразу"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EditorState.newNote(PATH, SOURCE, TEMPLATE, "## Нет такого"));
  }

  @Test
  public void defaultNotesStillStartsUnselectedInPickerAndGetsItsTimestampWhenSaved() {
    var state = EditorState.newNote(PATH, SOURCE, TEMPLATE, "## Заметки");
    assertTrue(state.pickerRoutes().isEmpty());
    state.edit("быстрая запись");
    assertTrue(
        VaultRepository.plan(state, SOURCE, SOURCE, TEMPLATE, DATE, NOW)
            .get(0)
            .after()
            .contains("2026-11-16 12:00:00\nбыстрая запись"));
  }

  @Test
  public void editingOpensAtEndOfCleanTextAndPreservesLineTagsAndAnchor() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "первая #crossPeople/FamilyNested\n"
                + "вторая #crossOneLine/FamilyNested ^anchor-id\n");
    var block = doc.blocks.get(0);
    var state = EditorState.editing(PATH, doc, TEMPLATE, block);
    assertFalse(state.fresh);
    assertEquals("первая\nвторая", state.editableText());
    assertEquals(new EditorState.InputCaret(0, state.editableText().length()), state.inputCaret());
    assertEquals(block.text(), state.original);
    assertEquals(block.start(), state.start);
    assertEquals(block.end(), state.end);
    assertEquals(block.text(), state.savedText());
    assertEquals(2, state.routes.size());
  }

  @Test
  public void editingFieldsFocusesTheLastVisibleValueWithoutExposingKeysOrTags() {
    var doc = new Markdown("## Поля\n\nПервый:: alpha\nВторой:: beta #person/Мама\n");
    var state = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    assertEquals(2, state.fieldCount());
    assertEquals("beta Мама", state.fieldValue(1));
    assertEquals(new EditorState.InputCaret(1, 9), state.inputCaret());
    state.cursor = 0;
    assertEquals(new EditorState.InputCaret(0, 0), state.inputCaret());
    state.cursor = 5;
    assertEquals(new EditorState.InputCaret(0, 5), state.inputCaret());
    state.cursor = 6;
    assertEquals(new EditorState.InputCaret(1, 0), state.inputCaret());
    assertEquals("Первый", state.fieldName(0));
    assertEquals("Второй", state.fieldName(1));
  }

  @Test
  public void restoredCursorIsClampedAndRoutesDoNotMoveIt() {
    var state = EditorState.newNote(PATH, SOURCE, TEMPLATE, "### Вложенный");
    state.edit("a🙂z");
    state.cursor = 3;
    assertEquals(new EditorState.InputCaret(0, 3), state.inputCaret());
    var catalog = state.routingCatalog(TEMPLATE);
    state.applyRoutes(state.routes, catalog);
    assertEquals(new EditorState.InputCaret(0, 3), state.inputCaret());
    state.cursor = 999;
    assertEquals(new EditorState.InputCaret(0, 4), state.inputCaret());
    state.cursor = -1;
    assertEquals(new EditorState.InputCaret(0, 0), state.inputCaret());
  }

  @Test
  public void staleBlockCannotBecomeAnEditorOfAFreshExternalSnapshot() {
    var old = SOURCE.blocks.get(0);
    var fresh = new Markdown(SOURCE.text.replace("старый текст", "чужое исправление"));
    assertThrows(
        IllegalArgumentException.class, () -> EditorState.editing(PATH, fresh, TEMPLATE, old));
  }
}
