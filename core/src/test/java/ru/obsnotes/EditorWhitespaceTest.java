package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

/** Synthetic fixtures: the user's diary contents never belong in regression tests. */
public class EditorWhitespaceTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Events\n\n## Other\n");

  private EditorState editor(String raw) {
    var document = new Markdown("## Events\n\n" + raw + "\n");
    var state = EditorState.editing(PATH, document, TEMPLATE, document.blocks.get(0));
    state.enableParts(TEMPLATE);
    return state;
  }

  private void editAndRecover(EditorState state, String value) {
    state.edit(value);
    assertEquals("Before recovery sync", value, state.editableText());
    state.parts.sync(); // EditorRecovery.save does this after each text change.
    assertEquals("After recovery sync", value, state.editableText());
    assertEquals("", state.parts.error);
  }

  @Test
  public void rootedLinkedTaggedBlockAcceptsASpaceAndThenTheNextWord() {
    var state = editor("[[#^earlier|Earlier]]\n" + Events.root("Walk #crossPeople/Other"));
    String id = Events.rootId(state.original);
    String partId = state.parts.parts.get(0).form().partId;
    assertEquals("Walk", state.editableText());
    editAndRecover(state, "Walk ");
    editAndRecover(state, "Walk next");
    var form = state.parts.parts.get(0).form();
    assertEquals(partId, form.partId);
    assertEquals(id, Events.rootId(form.text));
    assertEquals(1, form.tags().size());
    assertEquals("Other", form.tags().get(0).ids());
    assertEquals(1, state.parts.references(0).size());
    assertEquals("earlier", state.parts.references(0).get(0).id());
    assertTrue(form.savedText().contains("Walk next #crossPeople/Other ^" + id));
    var document = new Markdown(state.sourceSnapshot);
    var changes =
        VaultRepository.plan(state, document, document, TEMPLATE, DATE, DATE.atTime(12, 0));
    assertEquals(1, changes.size());
    assertEquals(document.text.replace("Walk", "Walk next"), changes.get(0).after());
  }

  @Test
  public void whitespaceBeforeAHiddenAnchorSurvivesEveryKeystrokeAndSingleFormEditing() {
    for (String raw : List.of("Line ^stable", Events.root("Line ^stable"))) {
      var state = editor(raw);
      for (String suffix : List.of(" ", "  ", "\t", " \t ", " next")) {
        editAndRecover(state, "Line" + suffix);
        assertEquals("stable", Events.id(state.parts.parts.get(0).form().text));
      }
      var form = state.parts.parts.get(0).form().copy();
      form.edit("Line ");
      assertEquals("Line ", form.editableText());
      form.edit("Line continued");
      assertEquals("Line continued", form.editableText());
    }
  }

  @Test
  public void continuingAnyPartPreservesItsRootTagAndOtherParts() {
    var state = editor(Events.root("First #crossOneLine/Other"));
    editAndRecover(state, "First\n\nSecond");
    String firstId = state.parts.parts.get(0).form().partId;
    String secondId = state.parts.parts.get(1).form().partId;
    editAndRecover(state, "First \n\nSecond");
    editAndRecover(state, "First continued\n\nSecond");
    editAndRecover(state, "First continued\n\nSecond ");
    editAndRecover(state, "First continued\n\nSecond continued");
    assertEquals(firstId, state.parts.parts.get(0).form().partId);
    assertEquals(secondId, state.parts.parts.get(1).form().partId);
    assertTrue(state.parts.isRoot(0));
    assertEquals(1, state.parts.parts.get(0).form().tags().size());
    assertEquals("crossOneLine", state.parts.parts.get(0).form().tags().get(0).kind());
  }

  @Test
  public void typingThenRemovingASpaceLeavesRootedDocumentUnchanged() {
    String raw = "[[#^earlier|Earlier]]\n" + Events.root("Line #crossPeople/Other ^stable");
    var document = new Markdown("## Events\n\n" + raw + "\n");
    var state = EditorState.editing(PATH, document, TEMPLATE, document.blocks.get(0));
    state.enableParts(TEMPLATE);
    editAndRecover(state, "Line ");
    editAndRecover(state, "Line");
    assertTrue(EditorRevision.unchanged(state, TEMPLATE));
    assertTrue(
        VaultRepository.plan(state, document, document, TEMPLATE, DATE, DATE.atTime(12, 0))
            .isEmpty());
  }

  @Test
  public void existingAnchorSeparatorFormattingDoesNotCauseANoopWrite() {
    for (String eol : List.of("\n", "\r\n"))
      for (String separator : List.of(" ", "  ", "\t", " \t ")) {
        String raw = "[[#^earlier|Earlier]]\n" + Events.root("Line" + separator + "^stable");
        var document = new Markdown(("## Events\n\n" + raw + "\n\nNeighbor\n").replace("\n", eol));
        var state = EditorState.editing(PATH, document, TEMPLATE, document.blocks.get(0));
        state.enableParts(TEMPLATE);
        state.parts.sync();
        assertTrue(EditorRevision.unchanged(state, TEMPLATE));
        assertTrue(
            VaultRepository.plan(state, document, document, TEMPLATE, DATE, DATE.atTime(12, 0))
                .isEmpty());
      }
  }
}
