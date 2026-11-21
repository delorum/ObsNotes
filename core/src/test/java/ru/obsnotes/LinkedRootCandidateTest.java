package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class LinkedRootCandidateTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## События\n");

  private static Markdown document(String eol) {
    return new Markdown(
        ("## Заметки\n\nChild event\n\n## События\n\n"
                + Events.root("Parent event ^parent")
                + "\n\n"
                + "[[#^parent|Parent event]]\nIntermediate event #crossPeople/Test\n")
            .replace("\n", eol));
  }

  @Test
  public void parentLinkDoesNotHideCandidateBeforeItIsExplicitlyMadeARoot() {
    for (String eol : List.of("\n", "\r\n")) {
      var doc = document(eol);
      var roots = new DiaryIndex().roots(PATH, doc, doc.blocks.get(0).start());
      assertEquals(2, roots.size());
      assertEquals("parent", roots.get(0).id());
      var candidate = roots.get(1);
      assertEquals("", candidate.id());
      assertSame(doc.blocks.get(2), candidate.block());
      assertEquals("Intermediate event", candidate.visibleText(DayContentRules.EMPTY));
      assertTrue(FuzzySearch.matches(candidate.label(DayContentRules.EMPTY), "intrmdteevnt"));
      assertFalse(FuzzySearch.matches(candidate.label(DayContentRules.EMPTY), "Parent event"));
      assertEquals(
          doc.blocks.get(2).text(),
          doc.text.substring(candidate.block().start(), candidate.block().end()));
    }
  }

  @Test
  public void selectingLinkedCandidateCreatesChainAndPreservesItsParentsTagsAndLineEndings() {
    for (String eol : List.of("\n", "\r\n")) {
      var doc = document(eol);
      var owner = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
      owner.enableParts(TEMPLATE);
      var candidate =
          new DiaryIndex()
              .roots(PATH, doc, owner.start).stream()
                  .filter(r -> r.block().text().contains("Intermediate event"))
                  .findFirst()
                  .orElseThrow();
      owner.parts.addExternal(0, candidate, doc.text, null);
      var changes = VaultRepository.plan(owner, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
      assertEquals(1, changes.size());
      assertEquals(PATH, changes.get(0).path());
      var after = new Markdown(changes.get(0).after());
      var middle =
          after.blocks.stream()
              .filter(b -> b.text().contains("Intermediate event #crossPeople/Test"))
              .findFirst()
              .orElseThrow();
      String id = Events.rootId(middle.text());
      assertFalse(id.isEmpty());
      assertEquals(List.of(new Events.Parent(PATH, "parent")), Events.parents(PATH, middle.text()));
      assertTrue(middle.text().startsWith("[[#^parent|Parent event]]" + eol));
      assertEquals(
          List.of(new Events.Parent(PATH, id)), Events.parents(PATH, after.blocks.get(0).text()));
      assertEquals(doc.blocks.get(1).text(), after.blocks.get(1).text());
      assertEquals(eol, after.newline);
      assertEquals(2, new DiaryIndex().roots(PATH, after, after.blocks.get(0).start()).size());
    }
  }

  @Test
  public void multipleFullPathParentsAndExistingAnchorRemainEligibleAndRetained() {
    String prefix =
        "[[" + PATH.replaceFirst("\\.md$", "") + "#^first|First]]\n[[#^second|Second]]\n";
    var doc = new Markdown("## Заметки\n\n" + prefix + "Intermediate event ^stable\n");
    var roots = new DiaryIndex().roots(PATH, doc, -1);
    assertEquals(1, roots.size());
    var candidate = roots.get(0);
    assertEquals("", candidate.id());
    String promoted = Events.root(candidate.block().text());
    assertTrue(promoted.startsWith(prefix));
    assertEquals("stable", Events.rootId(promoted));
    assertEquals(
        List.of(new Events.Parent(PATH, "first"), new Events.Parent(PATH, "second")),
        Events.parents(PATH, promoted));
  }

  @Test
  public void linkedHiddenOnlyBlocksAndTheEditedBlockStillDoNotAppear() {
    var doc =
        new Markdown(
            "## Заметки\n\n[[#^parent|Parent]]\n*Hidden*\n\n[[#^parent|Parent]]\nEdited event\n");
    var rules = new DayContentRules(List.of("*Hidden*"), List.of());
    var roots = new DiaryIndex().roots(PATH, doc, doc.blocks.get(1).start(), rules);
    assertTrue(roots.isEmpty());
  }

  @Test
  public void selectingStaleLinkedCandidateStillFailsWithoutChangingTheEditor() throws Exception {
    var doc = document("\n");
    var storage = new RootDestinationTest.Storage();
    storage.files.put(PATH, doc.text);
    var owner = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    var index = new DiaryIndex();
    var candidate =
        index.roots(storage, owner).stream()
            .filter(r -> r.id().isEmpty())
            .findFirst()
            .orElseThrow();
    assertEquals(doc.text, index.rootSnapshot(storage, owner, candidate));
    storage.files.put(PATH, doc.text.replace("Intermediate event", "External edit"));
    assertThrows(
        VaultRepository.Conflict.class, () -> index.rootSnapshot(storage, owner, candidate));
    assertEquals("Child event", owner.editableText());
    assertTrue(owner.rootEdits.isEmpty());
  }
}
