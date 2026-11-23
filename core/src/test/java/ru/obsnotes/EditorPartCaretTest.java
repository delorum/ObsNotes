package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class EditorPartCaretTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Other\n");

  private EditorState editor(String value) {
    var state = EditorState.newNote(PATH, TEMPLATE, TEMPLATE, "## Заметки");
    state.enableParts(TEMPLATE);
    state.edit(value);
    return state;
  }

  @Test
  public void cardSelectionAndTextSelectionWorkInBothDirections() {
    var state = editor("First\nline\n\nSecond\n\nThird 🙂");
    var panels = EditorPanels.of(state);
    String revision = EditorRevision.contents(state, TEMPLATE);
    assertEquals(1, state.parts.selectAt(state.editableText().indexOf("Second") + 1));
    for (int index : List.of(0, 2, 1, 0)) {
      var part = state.parts.parts.get(index);
      int end = state.parts.selectEnd(index);
      assertEquals(part.end(), end);
      assertEquals(end, state.cursor);
      assertEquals(part.form().editableText().length(), part.form().cursor);
      assertEquals(index, state.parts.selected);
      assertEquals(index, state.parts.selectAt(end));
      assertEquals(panels, EditorPanels.of(state));
      assertEquals(revision, EditorRevision.contents(state, TEMPLATE));
    }
  }

  @Test
  public void reusedCardsUseLatestRangesAndCommonActionsTargetTheChosenPart() {
    var state = editor("First\n\nSecond");
    int oldEnd = state.parts.parts.get(1).end();
    state.edit("First is longer\n\nSecond also changed");
    var panels = EditorPanels.of(state);
    assertTrue(state.parts.selectEnd(1) > oldEnd);
    assertEquals(state.editableText().length(), state.cursor);
    assertEquals(panels, EditorPanels.of(state));
    var form = state.parts.selected();
    var catalog = form.routingCatalog(TEMPLATE);
    form.applyRoutes(
        List.of(
            catalog.choices.stream()
                .filter(c -> c.label().equals("Other"))
                .findFirst()
                .orElseThrow()),
        catalog);
    assertEquals("## Other", state.parts.parts.get(1).form().heading);
    assertEquals("## Заметки", state.parts.parts.get(0).form().heading);
    assertEquals("First is longer\n\nSecond also changed", state.editableText());
  }

  @Test
  public void visibleEndExcludesHiddenMetadataAndSecretPayloadAndDoesNotWrite() {
    String raw =
        "[[#^earlier|Earlier]]\n"
            + Events.root("Visible 🙂\n`secret fixture-payload` #crossPeople/Unknown ^stable");
    var document = new Markdown("## Заметки\n\n" + raw + "\n");
    var state = EditorState.editing(PATH, document, TEMPLATE, document.blocks.get(0));
    state.enableParts(TEMPLATE);
    String visible = state.editableText();
    assertTrue(visible.contains(SecretText.PLACEHOLDER));
    assertFalse(visible.contains("fixture-payload"));
    assertEquals(visible.length(), state.parts.selectEnd(0));
    assertEquals(visible.length(), state.parts.selected().cursor);
    assertEquals("stable", Events.rootId(state.parts.selected().text));
    assertEquals(1, state.parts.references(0).size());
    assertTrue(EditorRevision.unchanged(state, TEMPLATE));
    assertTrue(
        VaultRepository.plan(state, document, document, TEMPLATE, DATE, DATE.atTime(12, 0))
            .isEmpty());
  }

  @Test
  public void emptyCardsSharingAPositionKeepTheExplicitChoice() {
    var document = new Markdown("## Заметки\n\nFirst\n\nSecond\n");
    var selected = new BlockSelection();
    for (var block : document.blocks) selected.toggle(PATH, document, block);
    var state =
        EditorBatch.create(
            PATH, document, TEMPLATE, null, selected.ordered(PATH, Map.of(PATH, document)));
    state.edit("");
    assertEquals(2, state.parts.parts.size());
    var panels = EditorPanels.of(state);
    String revision = EditorRevision.contents(state, TEMPLATE);
    for (int index : List.of(0, 1, 0)) {
      assertEquals(0, state.parts.selectEnd(index));
      assertEquals(index, state.parts.selectAt(0));
      assertEquals(0, state.parts.selected().cursor);
      assertEquals(panels, EditorPanels.of(state));
      assertEquals(revision, EditorRevision.contents(state, TEMPLATE));
    }
  }

  @Test
  public void fieldCardTargetsTheValueEndIncludingAnEmptyValue() {
    var document = new Markdown("## Заметки\n\nFirstField::\nSecondField:: Value\n");
    var state = EditorState.editing(PATH, document, TEMPLATE, document.blocks.get(0));
    state.enableParts(TEMPLATE);
    assertEquals(2, state.parts.parts.size());
    assertEquals(0, state.parts.selectEnd(0));
    assertEquals(0, state.parts.selectAt(state.cursor));
    assertEquals("FirstField::", state.parts.selected().fieldPrefix);
    assertEquals(state.editableText().length(), state.parts.selectEnd(1));
    assertEquals("Value".length(), state.parts.selected().cursor);
    assertTrue(EditorRevision.unchanged(state, TEMPLATE));
    assertTrue(
        VaultRepository.plan(state, document, document, TEMPLATE, DATE, DATE.atTime(12, 0))
            .isEmpty());
  }
}
