package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class EditorPanelsTest {
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Other\n\n");

  private EditorState editor(String text) {
    var state = EditorState.newNote("fixture.md", TEMPLATE, TEMPLATE, "## Заметки");
    state.enableParts(TEMPLATE);
    state.edit(text);
    return state;
  }

  @Test
  public void ordinaryTypingAndCursorChangesKeepPanelsDespiteReplacementForms() {
    var state = editor("First\n\nSecond");
    var before = EditorPanels.of(state);
    var old = state.parts.parts.get(1).form();
    state.edit("FirstMore\n\nSecondMore");
    state.cursor = 3;
    assertNotSame(old, state.parts.parts.get(1).form());
    assertEquals(before, EditorPanels.of(state));
  }

  @Test
  public void splittingAndRoutingAndRemovingALabelInvalidatePanels() {
    var state = editor("First");
    var single = EditorPanels.of(state);
    state.edit("First\n\nSecond");
    var split = EditorPanels.of(state);
    assertNotEquals(single, split);
    var form = state.parts.parts.get(1).form();
    var catalog = form.routingCatalog(TEMPLATE);
    var other =
        catalog.choices.stream().filter(c -> c.label().equals("Other")).findFirst().orElseThrow();
    form.applyRoutes(List.of(other), catalog);
    var routed = EditorPanels.of(state);
    assertNotEquals(split, routed);
    form.removeRoute(0, catalog);
    assertNotEquals(routed, EditorPanels.of(state));
  }

  @Test
  public void lineTagMovementAndRootRelationshipsInvalidatePanels() {
    var state = editor("First\nSecond\n\nThird");
    var plain = EditorPanels.of(state);
    var form = state.parts.parts.get(0).form();
    form.addTags(6, List.of("Alias"), true, false);
    state.parts.sync();
    var tagged = EditorPanels.of(state);
    assertNotEquals(plain, tagged);
    state.edit("NewLine\n" + state.editableText());
    var moved = EditorPanels.of(state);
    assertNotEquals(tagged, moved);
    state.parts.addInternal(1, 0, null);
    var linked = EditorPanels.of(state);
    assertNotEquals(moved, linked);
    state.parts.removeReference(1, state.parts.references(1).get(0).key());
    assertNotEquals(linked, EditorPanels.of(state));
  }

  @Test
  public void taskTextModeAndValueShellRemovalInvalidatePanels() {
    var source = new Markdown("## Заметки\n\n- [ ] Task; OldValue\n");
    var task = DiaryTasks.in(source.text, 0).get(0);
    var state = EditorState.editTask("fixture.md", source, TEMPLATE, task);
    state.enableParts(TEMPLATE);
    var before = EditorPanels.of(state);
    state.parts.parts.get(0).form().removeValueTarget();
    assertNotEquals(before, EditorPanels.of(state));
    var form = state.parts.parts.get(0).form();
    var shellRemoved = EditorPanels.of(state);
    form.kind = Markdown.Kind.TASK;
    form.appendTaskText = false;
    assertNotEquals(shellRemoved, EditorPanels.of(state));
  }
}
