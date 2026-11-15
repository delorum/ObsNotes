package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TaggedTextTest {
  @Test
  public void overviewPreviewShowsBodyInsteadOfLeadingServiceLink() {
    assertEquals(
        "Дополнение",
        CrossReferences.clean("[[day#^event-one|Событие]]\nДополнение #crossPeople/Утро"));
  }

  @Test
  public void tagRoundTripKeepsAnchorsAtTheEndOfEveryBodyLine() {
    var t =
        TaggedText.parse("событие #crossPeople/Утро ^event-original\n> [!note] Связи\nпродолжение");
    assertEquals("event-original", Events.id(t.edit(t.clean).render()));
  }

  @Test
  public void textFieldHidesAllFourTagKindsAndRetainsPersonNames() {
    var t =
        TaggedText.parse(
            "видел #person/Лена #crossPeople/Утро\nс #personOneLine/Мама #crossOneLine/День");
    assertEquals("видел Лена\nс Мама", t.clean);
    assertEquals(4, t.tags.size());
    assertFalse(t.clean.contains("#"));
    assertEquals(t.clean, TaggedText.parse(t.render()).clean);
  }

  @Test
  public void insertingLineBeforeTaggedLineMovesItsBinding() {
    var t = TaggedText.parse("один\nдва #crossOneLine/Утро").edit("вставка\nодин\nдва");
    assertEquals(3, t.tags.get(0).line(t.clean));
    assertTrue(t.render().endsWith("два #crossOneLine/Утро"));
  }

  @Test
  public void newlineAtEndLeavesOneLineTagOnOriginalLine() {
    var t = TaggedText.parse("один #crossOneLine/Утро").edit("один\nдва");
    assertEquals("один #crossOneLine/Утро\nдва", t.render());
  }

  @Test
  public void deletingTaggedLineRemovesOnlyItsOneLineAssignment() {
    var t = TaggedText.parse("один #crossPeople/Лена\nдва #crossOneLine/Утро").edit("один");
    assertEquals(1, t.tags.size());
    assertEquals("crossPeople", t.tags.get(0).kind());
    assertEquals("один #crossPeople/Лена", t.render());
  }

  @Test
  public void personReferenceSurvivesEditingAroundNameButNotDeletingIt() {
    var t = TaggedText.parse("#person/Лена пришла").edit("Вчера Лена пришла");
    assertEquals("Вчера #person/Лена пришла", t.render());
    assertTrue(t.edit("Вчера пришла").tags.isEmpty());
  }

  @Test
  public void addAndRemoveBindingsKeepTextCleanAndDoNotDuplicate() {
    var t =
        TaggedText.parse("один\nдва")
            .add(6, List.of("Утро"), true, false)
            .add(6, List.of("Утро"), true, false);
    assertEquals("один\nдва", t.clean);
    assertEquals(1, t.tags.size());
    assertEquals(2, t.tags.get(0).line(t.clean));
    assertEquals("один\nдва", t.remove(0).render());
  }

  @Test
  public void editorPreservesTagsAndHiddenEventMetadataWhileSavingCleanEdits() {
    EditorState s = new EditorState();
    s.text = Events.root("событие #crossPeople/Утро");
    String id = Events.id(s.text);
    assertEquals("событие", s.editableText());
    s.edit("правка события");
    assertEquals("правка события", s.editableText());
    assertTrue(s.savedText().contains("#crossPeople/Утро"));
    assertEquals(id, Events.id(s.savedText()));
    assertTrue(s.savedText().contains("outLinkInfo"));
  }

  @Test
  public void editorHidesAndPreservesRootLinkAndAllowsExplicitRemoval() {
    EditorState s = new EditorState();
    s.text = "[[Дневник/day#^event-abc|Событие]]\nДополнение #crossPeople/Утро";
    assertEquals("Дополнение", s.editableText());
    assertEquals("Событие", s.rootLinkLabel());
    s.edit("Изменённое дополнение");
    assertTrue(s.savedText().startsWith("[[Дневник/day#^event-abc|Событие]]\n"));
    s.removeRootLink();
    assertFalse(s.savedText().contains("#^"));
    assertEquals(1, s.tags().size());
  }

  @Test
  public void visibleNameAndWholeBlockTagAreKeptWhenAddingAnotherAssignment() {
    EditorState s = new EditorState();
    s.text = "видел #person/Лена #crossPeople/Утро";
    s.addTags(0, List.of("День"), true, false);
    assertEquals("видел Лена", s.editableText());
    assertEquals(3, s.tags().size());
    assertTrue(s.savedText().contains("#person/Лена"));
  }
}
