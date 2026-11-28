package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

/** Synthetic Markdown only: no personal diary contents. */
public class MultipleLinksTest {
  static final LocalDate DATE = LocalDate.of(2026, 11, 17);
  static final String PATH = DiaryLayout.path(DATE), OTHER = DiaryLayout.path(DATE.minusDays(1));
  static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Работа\n\n");

  static String ref(String id) {
    return "[[#^" + id + "|" + id + "]]";
  }

  static String root(String id) {
    return Events.root("Root-" + id + " ^" + id);
  }

  static Markdown doc(String text) {
    return new Markdown("## Заметки\n\n" + text + "\n\n## Работа\n\nTail\n");
  }

  static EditorState edit(Markdown doc, String contains) {
    var block =
        doc.blocks.stream().filter(b -> b.text().contains(contains)).findFirst().orElseThrow();
    var s = EditorState.editing(PATH, doc, TEMPLATE, block);
    s.enableParts(TEMPLATE);
    return s;
  }

  static List<VaultRepository.Change> plan(EditorState s, Markdown doc) {
    return VaultRepository.plan(s, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
  }

  static Markdown saved(EditorState s, Markdown doc) {
    return new Markdown(
        plan(s, doc).stream().filter(c -> c.path().equals(PATH)).findFirst().orElseThrow().after());
  }

  static Events.Root candidate(String path, String body) {
    return new Events.Root(path, doc(body).blocks.get(0), Events.rootId(body));
  }

  @Test
  public void prefixRecognizesAllLeadingLinksAndHidesThemWithoutChangingOriginal() {
    for (String sep : List.of("\n", "\r\n", " ")) {
      String text = ref("one") + sep + ref("two") + "\r\nText";
      assertEquals(2, Events.parents(PATH, text).size());
      assertEquals("Text", Events.prefix(PATH, text).body());
      String eol = sep.equals("\r\n") ? "\r\n" : "\n";
      var m = new Markdown(doc(text.replace("\r\n", "\n")).text.replace("\n", eol));
      var s = edit(m, "Text");
      assertEquals("Text", s.editableText());
      assertEquals(2, s.parts.references(0).size());
      assertTrue(plan(s, m).isEmpty());
      assertEquals("Text", CrossReferences.clean(text));
    }
  }

  @Test
  public void codeAndInlineReferencesAreNeverHiddenAsParents() {
    for (String text :
        List.of(
            "Text " + ref("one"),
            "    " + ref("one"),
            "\t" + ref("one"),
            "```md\n" + ref("one") + "\n```",
            "---\nkey: x\n---\n" + ref("one")))
      assertTrue(text, Events.parents(PATH, text).isEmpty());
    assertEquals(1, Events.parents(PATH, ref("one") + "\n\n" + ref("two")).size());
    assertEquals("", Events.id("```md\nFake ^fake\n```"));
  }

  @Test
  public void replaceAndDeleteAreIndependentAndReturnToOriginalWithoutWrite() {
    var m = doc(ref("one") + "\n" + ref("two") + "\nText");
    var s = edit(m, "Text");
    var first = s.parts.references(0).get(0);
    var second = s.parts.references(0).get(1);
    s.parts.addExternal(0, candidate(PATH, root("three")), m.text, second.key());
    assertEquals(
        List.of("one", "three"), s.parts.references(0).stream().map(EventLink::id).toList());
    assertEquals(first, s.parts.references(0).get(0));
    assertEquals(second.key(), s.parts.references(0).get(1).key());
    s.parts.addExternal(0, candidate(PATH, root("two")), m.text, second.key());
    assertTrue(plan(s, m).isEmpty());
    s.parts.removeReference(0, first.key());
    var result = saved(s, m).blocks.get(0);
    assertEquals(List.of(new Events.Parent(PATH, "two")), Events.parents(PATH, result.text()));
    assertEquals("Text", CrossReferences.clean(result.text()));
  }

  @Test
  public void addIsIdempotentAndDuplicateReplacementIsAtomic() {
    var m = doc(ref("one") + "\n" + ref("two") + "\nText");
    var s = edit(m, "Text");
    s.parts.addExternal(0, candidate(PATH, root("one")), m.text, null);
    assertEquals(2, s.parts.references(0).size());
    String before = EditorRevision.contents(s, TEMPLATE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            s.parts.addExternal(
                0, candidate(PATH, root("one")), m.text, s.parts.references(0).get(1).key()));
    assertEquals(before, EditorRevision.contents(s, TEMPLATE));
  }

  @Test
  public void multipleInternalParentsSaveBothReferencesAndSecondaryCyclesAreAtomic() {
    var m = doc("Alpha");
    var s = edit(m, "Alpha");
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.addInternal(2, 0, null);
    s.parts.addInternal(2, 1, null);
    s.parts.addInternal(1, 0, null);
    String before = EditorRevision.contents(s, TEMPLATE);
    assertThrows(IllegalArgumentException.class, () -> s.parts.addInternal(0, 2, null));
    assertEquals(before, EditorRevision.contents(s, TEMPLATE));
    var result = saved(s, m);
    var a = result.blocks.get(0);
    var b = result.blocks.get(1);
    var c = result.blocks.get(2);
    assertEquals(
        List.of(
            new Events.Parent(PATH, Events.rootId(a.text())),
            new Events.Parent(PATH, Events.rootId(b.text()))),
        Events.parents(PATH, c.text()));
    assertEquals(Events.rootId(a.text()), Events.parents(PATH, b.text()).get(0).id());
    assertEquals("Gamma", CrossReferences.clean(c.text()));
  }

  @Test
  public void deletingPendingRootPreservesItsParentsAndRestoringRestoresIncomingOrder() {
    var m = doc("Alpha");
    var s = edit(m, "Alpha");
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.addInternal(1, 0, null);
    s.parts.addInternal(2, 0, null);
    s.parts.addInternal(2, 1, null);
    s.parts.makeRoot(1);
    String before = EditorRevision.contents(s, TEMPLATE);
    s.parts.deleteRoot(1);
    assertEquals(1, s.parts.references(1).size());
    assertEquals(1, s.parts.references(2).size());
    assertFalse(s.parts.isRoot(1));
    s.parts.makeRoot(1);
    assertEquals(before, EditorRevision.contents(s, TEMPLATE));
  }

  @Test
  public void internalReferencesKeepIdentityAfterInsertingAnEarlierPart() {
    var m = doc("Alpha");
    var s = edit(m, "Alpha");
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.addInternal(2, 0, null);
    s.parts.addInternal(2, 1, null);
    var refs = s.parts.references(2);
    s.edit("Alpha\n\nInserted\n\nBeta\n\nGamma");
    assertEquals(refs, s.parts.references(3));
    assertEquals("К части 3", s.parts.referenceLabel(refs.get(1)));
    assertEquals(2, Events.parents(PATH, saved(s, m).blocks.get(3).text()).size());
  }

  @Test
  public void pendingExternalRootsInSameFileMergeAndSharedRootIsCreatedOnce() {
    var m = doc("Alpha\n\nCandidateOne\n\nCandidateTwo");
    var s = edit(m, "Alpha");
    s.edit("Alpha\n\nBeta");
    var one = candidate(PATH, "CandidateOne");
    var two = candidate(PATH, "CandidateTwo");
    s.parts.addExternal(0, one, m.text, null);
    s.parts.addExternal(0, two, m.text, null);
    s.parts.addExternal(1, one, m.text, null);
    var changes = plan(s, m);
    assertEquals(1, changes.size());
    var result = new Markdown(changes.get(0).after());
    var a = result.blocks.get(0);
    var b = result.blocks.get(1);
    assertEquals(2, Events.parents(PATH, a.text()).size());
    assertEquals(Events.parents(PATH, a.text()).get(0), Events.parents(PATH, b.text()).get(0));
    assertEquals(2, result.blocks.stream().filter(x -> !Events.rootId(x.text()).isEmpty()).count());
  }

  @Test
  public void removedPendingRelationDoesNotCreateUnneededExternalRoot() {
    var m = doc("Alpha\n\nCandidate");
    var s = edit(m, "Alpha");
    s.parts.addExternal(0, candidate(PATH, "Candidate"), m.text, null);
    s.parts.removeReference(0, s.parts.references(0).get(0).key());
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void deletePersistedRootRemovesOnlyIncomingMetadataInThisFileIncludingSecondLink() {
    String own = ref("upstream") + "\n" + root("one");
    String linked = ref("two") + "\n[[2026-11-17-вт#^one|Alias]]\nChild";
    String untouched =
        "Inline " + ref("one") + "\n\n```md\n" + ref("one") + "\n```\n\n" + root("two");
    var m = doc(own + "\n\n" + linked + "\n\n" + untouched);
    var s = edit(m, "Root-one");
    s.parts.deleteRoot(0);
    var actual = saved(s, m);
    assertEquals(
        List.of(new Events.Parent(PATH, "upstream")),
        Events.parents(PATH, actual.blocks.get(0).text()));
    assertEquals("", Events.rootId(actual.blocks.get(0).text()));
    assertEquals("", Events.id(actual.blocks.get(0).text()));
    assertEquals(
        List.of(new Events.Parent(PATH, "two")), Events.parents(PATH, actual.blocks.get(1).text()));
    assertTrue(actual.text.contains(untouched));
    assertEquals(1, plan(s, m).size());
  }

  @Test
  public void rootRemovalAndRestoreIsExactlyNoOpIncludingExistingParentsAndCrlf() {
    var m =
        new Markdown(
            doc(ref("up") + "\n" + root("one") + "\n\n" + ref("one") + "\nChild")
                .text
                .replace("\n", "\r\n"));
    var s = edit(m, "Root-one");
    s.parts.deleteRoot(0);
    assertFalse(plan(s, m).isEmpty());
    s.parts.makeRoot(0);
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void deleteRootPreservesExistingStandaloneAnchorWhenUndoingNewRootMark() {
    var m = doc("Alpha ^existing");
    var s = edit(m, "Alpha");
    s.parts.makeRoot(0);
    s.parts.deleteRoot(0);
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void ambiguousRootDeletionIsRejectedBeforeChangingEditor() {
    var m = doc(root("one") + "\n\nDuplicate ^one");
    var s = edit(m, "Root-one");
    String before = EditorRevision.contents(s, TEMPLATE);
    assertThrows(IllegalArgumentException.class, () -> s.parts.deleteRoot(0));
    assertEquals(before, EditorRevision.contents(s, TEMPLATE));
  }

  @Test
  public void removingGeneratedCalloutPreservesOtherTrailingContentAndLineEndings() {
    String input = (ref("up") + "\n" + root("one") + "\nFollowing\n> quote").replace("\n", "\r\n");
    assertEquals(
        (ref("up") + "\nRoot-one\nFollowing\n> quote").replace("\n", "\r\n"),
        Events.withoutRoot(input, true));
  }

  @Test
  public void freshNoteTimestampComesAfterEveryLeadingParent() {
    var m = doc("Tail");
    var s = EditorState.newNote(PATH, m, TEMPLATE, "## Заметки");
    s.edit("New");
    s.enableParts(TEMPLATE);
    s.parts.addExternal(0, candidate(PATH, root("one")), m.text, null);
    s.parts.addExternal(0, candidate(PATH, root("two")), m.text, null);
    var result =
        saved(s, m).blocks.stream().filter(b -> b.text().contains("New")).findFirst().orElseThrow();
    assertEquals(2, Events.parents(PATH, result.text()).size());
    assertTrue(Events.prefix(PATH, result.text()).body().contains("2026-11-17 12:00:00"));
  }

  @Test
  public void deletionPreservesSourceOutsideBlocksAndCrlf() {
    String original =
        doc(root("one") + "\n\n" + ref("two") + "\n" + ref("one") + "\nChild")
            .text
            .replace("\n", "\r\n");
    var m = new Markdown(original);
    var s = edit(m, "Root-one");
    s.parts.deleteRoot(0);
    assertEquals(
        original
            .replace(root("one").replace("\n", "\r\n"), "Root-one")
            .replace(ref("one") + "\r\nChild", "Child"),
        saved(s, m).text);
  }

  private static String absolute(String local) {
    return "[[" + PATH.substring(0, PATH.length() - 3) + local.substring(2);
  }

  @Test
  public void generatedSameDayParentHasTheFullFilePath() {
    var m = doc(root("one") + "\n\nChild");
    var s = edit(m, "Child");
    s.parts.addExternal(0, candidate(PATH, root("one")), m.text, null);
    assertTrue(saved(s, m).text.contains(absolute("[[#^one|Root-one]]") + "\nChild"));
  }

  @Test
  public void bindingLegacyParentsKeepsAliasSpacingLineEndingsAndBody() {
    String external = "[[Elsewhere/Note#^other|Other]]";
    String body = "Body with an inline " + ref("one") + "\r\n" + ref("later");
    String source = ref("one") + "  " + external + "\r\n[[#^two]]\r\n" + body;
    assertEquals(
        absolute(ref("one")) + "  " + external + "\r\n" + absolute("[[#^two]]") + "\r\n" + body,
        Events.bindLocalParents(PATH, source));
  }

  @Test
  public void movingAnAdditionFromSingleAndSplitEditorsKeepsItsOriginalRoot() {
    var m = doc(root("one") + "\n\n" + ref("one") + "\nChild");
    var target = doc("Destination");
    var child = m.blocks.get(1);
    for (int parts : List.of(0, 1, 2)) {
      var s = EditorState.editing(PATH, m, TEMPLATE, child);
      if (parts > 0) s.enableParts(TEMPLATE);
      if (parts == 2) s.edit("Child\n\nSecond part");
      s.chooseDay(OTHER, target.text);
      var changes =
          VaultRepository.plan(s, m, target, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0));
      assertEquals(
          List.of(OTHER, PATH), changes.stream().map(VaultRepository.Change::path).toList());
      var moved = new Markdown(changes.get(0).after());
      assertEquals(
          List.of(absolute(ref("one"))),
          moved.blocks.stream()
              .flatMap(block -> Events.prefix(OTHER, block.text()).links().stream())
              .map(Events.Leading::raw)
              .toList());
      assertTrue(changes.get(1).after().contains("Root-one ^one"));
      assertFalse(changes.get(1).after().contains("Child"));
      assertEquals(parts == 2, moved.text.contains("Second part"));
    }
  }

  @Test
  public void movingSelectedAdditionsRebindsEveryLegacyParentInTheBatch() {
    var m = doc(root("one") + "\n\n" + ref("one") + "\nFirst\n\n" + ref("one") + "\nSecond");
    var target = doc("Destination");
    var selection = new BlockSelection();
    selection.toggle(PATH, m, m.blocks.get(1));
    selection.toggle(PATH, m, m.blocks.get(2));
    var s = EditorBatch.create(PATH, m, TEMPLATE, null, selection.ordered(PATH, Map.of(PATH, m)));
    assertEquals(1, s.parts.references(0).size());
    assertEquals(1, s.parts.references(1).size());
    s.chooseDay(OTHER, target.text);
    var changes =
        VaultRepository.plan(s, m, target, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0));
    assertEquals(List.of(OTHER, PATH), changes.stream().map(VaultRepository.Change::path).toList());
    var moved = new Markdown(changes.get(0).after());
    assertEquals(
        List.of(absolute(ref("one")), absolute(ref("one"))),
        moved.blocks.stream()
            .flatMap(block -> Events.prefix(OTHER, block.text()).links().stream())
            .map(Events.Leading::raw)
            .toList());
    assertTrue(changes.get(1).after().contains("Root-one ^one"));
    assertFalse(changes.get(1).after().contains("First"));
    assertFalse(changes.get(1).after().contains("Second"));
  }

  @Test
  public void movingARootStillRequiresAnUpdateOfItsIncomingLinks() {
    var m = doc(root("one") + "\n\n" + ref("one") + "\nChild");
    var target = doc("Destination");
    var s = edit(m, "Root-one");
    s.chooseDay(OTHER, target.text);
    assertThrows(
        IllegalArgumentException.class,
        () -> VaultRepository.plan(s, m, target, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0)));
  }

  @Test
  public void rootRemovedThenMovedCleansSourceAndPreservesExternalFiles() {
    var m = doc(root("one") + "\n\n" + ref("one") + "\nChild");
    var target = doc("UnchangedDestination");
    var s = edit(m, "Root-one");
    s.parts.deleteRoot(0);
    s.chooseDay(OTHER, target.text);
    var changes =
        VaultRepository.plan(s, m, target, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0));
    assertEquals(List.of(OTHER, PATH), changes.stream().map(VaultRepository.Change::path).toList());
    assertFalse(changes.get(0).after().contains("^one"));
    assertFalse(changes.get(1).after().contains("#^one"));
    assertFalse(changes.get(1).after().contains("Root-one"));
    assertTrue(changes.get(1).after().contains("Child"));
  }

  @Test
  public void rootRemovedThenTextClearedAlsoCleansItsChildren() {
    var m = doc(root("one") + "\n\n" + ref("two") + "\n" + ref("one") + "\nChild");
    var s = edit(m, "Root-one");
    s.parts.deleteRoot(0);
    s.edit("");
    var actual = saved(s, m);
    assertFalse(actual.text.contains("Root-one"));
    assertFalse(actual.text.contains("#^one"));
    assertTrue(actual.text.contains(ref("two") + "\nChild"));
  }

  @Test
  public void parentsOnlyBlockRetainsOtherParentUntilLastRemoval() {
    var m = doc(ref("one") + "\n" + ref("two"));
    var s = edit(m, "#^one");
    s.parts.removeReference(0, s.parts.references(0).get(0).key());
    assertTrue(saved(s, m).text.contains(ref("two")));
    s.parts.removeReference(0, s.parts.references(0).get(0).key());
    var actual = saved(s, m);
    assertFalse(actual.text.contains("#^"));
  }
}
