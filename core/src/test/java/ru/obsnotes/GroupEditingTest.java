package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class GroupEditingTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Alpha\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"AlphaAlias\"});\n"
              + "```\n\n"
              + "## Beta\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"BetaAlias\"});\n"
              + "```\n");
  private static final Markdown DOC =
      new Markdown(
          "## Заметки\r\n\r\n"
              + "Note\r\n\r\n"
              + "## Alpha\r\n\r\n"
              + "First\r\n\r\n"
              + "Unselected\r\n\r\n"
              + "## Beta\r\n\r\n"
              + "Second\r\n\r\n"
              + "Tail\r\n");

  private EditorState batch(Markdown doc, int... indices) {
    var selection = new BlockSelection();
    for (int i : indices) selection.toggle(PATH, doc, doc.blocks.get(i));
    return EditorBatch.create(
        PATH, doc, TEMPLATE, null, selection.ordered(PATH, Map.of(PATH, doc)));
  }

  private List<VaultRepository.Change> plan(EditorState state, Markdown doc) {
    return VaultRepository.plan(state, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
  }

  private Markdown saved(EditorState state, Markdown doc) {
    return new Markdown(
        plan(state, doc).stream()
            .filter(c -> c.path().equals(PATH))
            .findFirst()
            .orElseThrow()
            .after());
  }

  @Test
  public void selectionUsesFileOrderKeepsHiddenEntriesAndRejectsChangedSnapshots() {
    var selection = new BlockSelection();
    selection.toggle(PATH, DOC, DOC.blocks.get(3));
    selection.toggle(PATH, DOC, DOC.blocks.get(1));
    assertEquals(
        List.of(DOC.blocks.get(1), DOC.blocks.get(3)),
        selection.ordered(PATH, Map.of(PATH, DOC)).stream()
            .map(BlockSelection.Entry::block)
            .toList());
    assertEquals(2, selection.size());
    assertTrue(selection.contains(PATH, DOC.blocks.get(1)));
    assertTrue(selection.contains(PATH, DOC.blocks.get(3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> selection.ordered(PATH, Map.of(PATH, new Markdown(DOC.text + "external"))));
    selection.clear();
    assertEquals(0, selection.size());
  }

  @Test
  public void togglingTheLastSelectedOriginalExitsAndSelectionCanStartAgain() {
    var selection = new BlockSelection();
    var first = DOC.blocks.get(1);
    var second = DOC.blocks.get(3);
    selection.toggle(PATH, DOC, first);
    selection.toggle(PATH, DOC, second);
    selection.toggle(PATH, DOC, first);
    assertEquals(1, selection.size());
    assertFalse(selection.contains(PATH, first));
    assertTrue(selection.contains(PATH, second));
    selection.toggle(PATH, DOC, second);
    assertEquals(0, selection.size());
    selection.toggle(PATH, DOC, first);
    assertEquals(
        List.of(first),
        selection.ordered(PATH, Map.of(PATH, DOC)).stream()
            .map(BlockSelection.Entry::block)
            .toList());
  }

  @Test
  public void routingCatalogReusesImmutableSnapshotsAcrossTypingAndCopies() {
    var state = batch(DOC, 1);
    var form = state.parts.parts.get(0).form();
    var catalog = form.routingCatalog(TEMPLATE);
    form.edit("New text");
    assertSame(catalog, form.routingCatalog(new Markdown(TEMPLATE.text)));
    assertSame(catalog, form.copy().routingCatalog(TEMPLATE));
  }

  @Test
  public void routingCatalogRefreshesForDestinationTemplateAndWorkAvailability() {
    var form = batch(DOC, 1).parts.parts.get(0).form();
    var before = form.routingCatalog(TEMPLATE);
    form.destinationSnapshot += "\r\n## Fresh\r\nKey:: NewValue\r\n";
    var changed = form.routingCatalog(TEMPLATE);
    assertNotSame(before, changed);
    assertTrue(changed.choices.stream().anyMatch(c -> c.label().equals("Key")));
    var template = new Markdown(TEMPLATE.text + "\n## NewTemplateSection\n");
    var changedTemplate = form.routingCatalog(template);
    assertNotSame(changed, changedTemplate);
    assertTrue(
        changedTemplate.choices.stream().anyMatch(c -> c.label().equals("NewTemplateSection")));
    form.workSnapshot = "## Основные события\n";
    var withWork = form.routingCatalog(template);
    assertNotSame(changedTemplate, withWork);
    assertTrue(
        withWork.choices.stream().anyMatch(c -> c.target().heading().equals(WorkDaily.ROUTE)));
    form.workSnapshot = null;
    assertTrue(
        form.routingCatalog(template).choices.stream()
            .noneMatch(c -> c.target().heading().equals(WorkDaily.ROUTE)));
  }

  @Test
  public void manySectionsKeepExactRangesAndCodeBoundaries() {
    for (String newline : List.of("\n", "\r\n")) {
      StringBuilder source = new StringBuilder("front matter" + newline);
      for (int i = 0; i < 120; i++)
        source.append(
            "## S"
                + i
                + newline
                + newline
                + "First"
                + i
                + newline
                + newline
                + "```text"
                + newline
                + "## NotASection"
                + newline
                + newline
                + "Code"
                + i
                + newline
                + "```"
                + newline
                + newline
                + "```dataviewjs"
                + newline
                + "generated();"
                + newline
                + "```"
                + newline
                + newline
                + "Last"
                + i
                + newline);
      var document = new Markdown(source.toString());
      assertEquals(120, document.sections.size());
      assertEquals(360, document.blocks.size());
      for (int i = 0; i < 120; i++) {
        var blocks = document.blocks.subList(i * 3, i * 3 + 3);
        assertEquals("First" + i, blocks.get(0).text());
        assertTrue(blocks.get(1).text().contains("## NotASection"));
        assertEquals("Last" + i, blocks.get(2).text());
        for (var block : blocks) {
          assertEquals("## S" + i, block.section().heading());
          assertEquals(block.text(), document.text.substring(block.start(), block.end()));
        }
      }
      assertTrue(new Markdown("## Empty").blocks.isEmpty());
    }
  }

  @Test
  public void openingAndRestoringChangesDoesNotWriteAndKeepsEachAssignment() {
    var state = batch(DOC, 3, 1);
    assertEquals("First\n\nSecond", state.editableText());
    assertEquals("## Alpha", state.parts.parts.get(0).form().heading);
    assertEquals("## Beta", state.parts.parts.get(1).form().heading);
    assertTrue(plan(state, DOC).isEmpty());
    state.edit("Changed\n\nSecond");
    state.edit("First\n\nSecond");
    assertTrue(plan(state, DOC).isEmpty());
  }

  @Test
  public void editingOneSelectedBlockRetainsUnselectedBytesAndOtherOriginalPosition() {
    var state = batch(DOC, 1, 3);
    state.edit("Updated\n\nSecond");
    var after = saved(state, DOC);
    assertEquals(DOC.text.replace("First", "Updated"), after.text);
    assertTrue(SectionUsage.planned(state, TEMPLATE, plan(state, DOC)).isEmpty());
  }

  @Test
  public void mergingDifferentSectionsKeepsUpperDestinationAndCrossPeopleForTheOther() {
    var state = batch(DOC, 1, 3);
    state.edit("First\nSecond");
    assertEquals("", state.parts.error);
    assertEquals(1, state.parts.parts.size());
    var after = saved(state, DOC);
    var merged =
        after.blocks.stream().filter(b -> b.text().contains("First")).findFirst().orElseThrow();
    assertEquals("## Alpha", merged.section().heading());
    assertTrue(merged.text().contains("#crossPeople/BetaAlias"));
    assertEquals(1, after.blocks.stream().filter(b -> b.text().contains("Second")).count());
    assertTrue(after.text.contains("Unselected"));
  }

  @Test
  public void splittingAgainKeepsBothSourcesExactlyOnce() {
    var state = batch(DOC, 1, 3);
    state.edit("First\n\nNew part\n\nSecond");
    var after = saved(state, DOC);
    for (String text : List.of("First", "New part", "Second", "Unselected", "Tail"))
      assertEquals(text, 1, after.blocks.stream().filter(b -> b.text().contains(text)).count());
    assertFalse(after.text.contains("12:00"));
  }

  @Test
  public void selectedParentAndChildCanBeEditedTogetherWithoutDuplicateCallouts() {
    var doc =
        new Markdown("## Alpha\n\n" + Events.root("Root ^root") + "\n\n[[#^root|Root]]\nChild\n");
    var state = batch(doc, 0, 1);
    assertTrue(plan(state, doc).isEmpty());
    state.edit("Updated root\n\nUpdated child");
    var after = saved(state, doc);
    assertEquals(2, after.blocks.size());
    assertEquals("root", Events.rootId(after.blocks.get(0).text()));
    assertEquals(
        List.of(new Events.Parent(PATH, "root")), Events.parents(PATH, after.blocks.get(1).text()));
  }

  @Test
  public void mergingLinkedChildrenUnionsTheirParents() {
    var doc = new Markdown("## Alpha\n\n[[#^one|One]]\nFirst\n\n[[#^two|Two]]\nSecond\n");
    var state = batch(doc, 0, 1);
    state.edit("First\nSecond");
    assertEquals("", state.parts.error);
    var after = saved(state, doc);
    assertEquals(1, after.blocks.size());
    assertEquals(
        List.of(new Events.Parent(PATH, "one"), new Events.Parent(PATH, "two")),
        Events.parents(PATH, after.blocks.get(0).text()));
  }

  @Test
  public void mergingTwoRootsKeepsARecoverableErrorRatherThanLosingAnAnchor() {
    var doc =
        new Markdown(
            "## Alpha\n\n"
                + Events.root("First ^one")
                + "\n\n"
                + Events.root("Second ^two")
                + "\n");
    var state = batch(doc, 0, 1);
    state.edit("First\nSecond");
    assertFalse(state.parts.error.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> plan(state, doc));
    state.edit("First\n\nSecond");
    assertTrue(plan(state, doc).isEmpty());
  }

  @Test
  public void reorderKeepsAllGapsAnchorsAndCrossReferencesWithoutTouchingOtherSections() {
    var doc =
        new Markdown(
            "## Alpha\r\n\r\n"
                + "A ^one\r\n\r\n"
                + "2001-02-03 10:00:00\r\n"
                + "B\r\n\r\n"
                + "C\r\n\r\n"
                + "## Beta\r\n"
                + "Tail\r\n");
    var result = BlockOrder.move(doc, doc.blocks.get(0), doc.blocks.get(2));
    assertEquals(
        "## Alpha\r\n\r\n"
            + "B\r\n\r\n"
            + "2001-02-03 10:00:00\r\n"
            + "C\r\n\r\n"
            + "A ^one\r\n\r\n"
            + "## Beta\r\n"
            + "Tail\r\n",
        result.document().text);
    assertEquals(
        "A ^one",
        result.document().blocks.stream()
            .filter(b -> b.start() == result.positions().get(doc.blocks.get(0).start()))
            .findFirst()
            .orElseThrow()
            .text());
    assertThrows(
        IllegalArgumentException.class,
        () -> BlockOrder.move(doc, doc.blocks.get(0), doc.blocks.get(3)));
  }

  @Test
  public void keepingADailyPartAndMovingAnotherWritesWorkBeforeDiaryRemoval() {
    var work = new Markdown("## Основные события\n\nWork event\n");
    var note = new WorkDaily.Note(WorkDaily.path(DATE), work);
    var selection = new BlockSelection();
    selection.toggle(PATH, DOC, DOC.blocks.get(1));
    selection.toggle(PATH, DOC, DOC.blocks.get(3));
    var state =
        EditorBatch.create(PATH, DOC, TEMPLATE, note, selection.ordered(PATH, Map.of(PATH, DOC)));
    var form = state.parts.parts.get(1).form();
    form.applyRoutes(List.of(WorkDaily.choice()), form.routingCatalog(TEMPLATE));
    var changes = plan(state, DOC).stream().filter(c -> !c.before().equals(c.after())).toList();
    assertEquals(
        List.of(note.path(), PATH), changes.stream().map(VaultRepository.Change::path).toList());
    assertTrue(changes.get(0).after().contains("Second"));
    assertFalse(changes.get(1).after().contains("Second"));
    assertTrue(changes.get(1).after().contains("First"));
  }

  @Test
  public void workAndDiaryCanBeEditedTogetherWithoutTouchingOtherWorkSections() {
    var work =
        new Markdown("## Ideas\r\nKeep idea\r\n\r\n## Основные события\r\n\r\nWork event\r\n");
    var note = new WorkDaily.Note(WorkDaily.path(DATE), work);
    var selection = new BlockSelection();
    selection.toggle(PATH, DOC, DOC.blocks.get(1));
    selection.toggle(note.path(), work, work.blocks.get(1));
    var state =
        EditorBatch.create(
            PATH,
            DOC,
            TEMPLATE,
            note,
            selection.ordered(PATH, Map.of(PATH, DOC, note.path(), work)));
    assertTrue(plan(state, DOC).isEmpty());
    state.edit("Updated first\n\nWork event");
    var changes = plan(state, DOC);
    assertTrue(
        changes.stream()
            .filter(c -> c.path().equals(note.path()))
            .allMatch(c -> c.before().equals(c.after())));
    assertEquals(
        DOC.text.replace("First", "Updated first"),
        changes.stream().filter(c -> c.path().equals(PATH)).findFirst().orElseThrow().after());
  }

  @Test
  public void changedOrMissingBatchSourceFailsBeforeAnyWrite() throws Exception {
    var state = batch(DOC, 1, 3);
    state.edit("Updated\n\nSecond");
    var changes = plan(state, DOC);
    var storage = new RootDestinationTest.Storage();
    storage.files.put(PATH, DOC.text + "external");
    var journal =
        new VaultRepository.Journal() {
          public void prepare(List<VaultRepository.Change> c) {
            throw new AssertionError("No journal before validation");
          }

          public List<VaultRepository.Change> pending() {
            return List.of();
          }

          public void complete() {}
        };
    assertThrows(
        VaultRepository.Conflict.class,
        () -> new VaultRepository(storage, journal).commit(changes));
    storage.files.clear();
    assertThrows(
        java.io.FileNotFoundException.class,
        () -> new VaultRepository(storage, journal).commit(changes));
  }

  @Test
  public void selectedIdentitySurvivesAnExactLocalReorderWithDuplicateText() {
    var doc = new Markdown("## Alpha\n\nSame\n\nOther\n\nSame\n");
    var selected = new BlockSelection();
    selected.toggle(PATH, doc, doc.blocks.get(2));
    var moved = BlockOrder.move(doc, doc.blocks.get(2), doc.blocks.get(0));
    selected.reordered(PATH, doc, moved.document(), moved.positions());
    assertEquals(moved.document().blocks.get(0), selected.entries().get(0).block());
    assertEquals(moved.document().text, selected.entries().get(0).snapshot());
    assertFalse(selected.contains(PATH, moved.document().blocks.get(1)));
  }

  @Test
  public void ordinaryTaskAndFieldShellsRemainInTheirDedicatedEditors() {
    for (String body : List.of("- [ ] Ordinary fact", "Metric:: value")) {
      var doc = new Markdown("## Alpha\n\n" + body + "\n");
      assertFalse(EditorBatch.selectable(doc.blocks.get(0), false));
    }
  }

  @Test
  public void clearingAndRetypingTheWholeFieldKeepsEachOriginalAssignment() {
    var state = batch(DOC, 1, 3);
    state.edit("");
    state.parts.sync();
    String text = "";
    for (char c : "First\n\nSecond".toCharArray()) {
      text += c;
      state.edit(text);
      state.parts.sync();
    }
    assertEquals("", state.parts.error);
    assertEquals(2, state.parts.parts.size());
    assertEquals("## Alpha", state.parts.parts.get(0).form().heading);
    assertEquals("## Beta", state.parts.parts.get(1).form().heading);
    assertTrue(plan(state, DOC).isEmpty());
  }

  @Test
  public void mergingRootAndItsChildInEitherOrderRemovesSelfLinkAndKeepsIncomingLinks() {
    for (boolean rootFirst : List.of(true, false)) {
      String root = "[[#^upstream|Upstream]]\n" + Events.root("Root body ^stable");
      String child = "[[#^stable|Root]]\n[[#^extra|Extra]]\nChild body";
      var doc =
          new Markdown(
              "## Alpha\n\n"
                  + (rootFirst ? root + "\n\n" + child : child + "\n\n" + root)
                  + "\n\n[[#^stable|Root]]\nExternal leaf\n");
      var state = batch(doc, 0, 1);
      state.edit(rootFirst ? "Root body\nChild body" : "Child body\nRoot body");
      assertEquals("", state.parts.error);
      var after = saved(state, doc);
      assertEquals(2, after.blocks.size());
      var merged = after.blocks.get(0);
      assertEquals("stable", Events.rootId(merged.text()));
      assertTrue(merged.text().contains("outLinkInfo"));
      var parents = Events.parents(PATH, merged.text());
      assertEquals(2, parents.size());
      assertEquals(
          Set.of(new Events.Parent(PATH, "upstream"), new Events.Parent(PATH, "extra")),
          new HashSet<>(parents));
      assertEquals(doc.blocks.get(2).text(), after.blocks.get(1).text());
      assertEquals(
          List.of(new Events.Parent(PATH, "stable")),
          Events.parents(PATH, after.blocks.get(1).text()));
    }
  }

  @Test
  public void mergingTwoNewInternallyLinkedPartsKeepsOneRootWithoutSelfReference() {
    var state = EditorState.newNote(PATH, DOC, TEMPLATE, "## Alpha");
    state.enableParts(TEMPLATE);
    state.edit("Root body\n\nChild body");
    state.parts.addInternal(1, 0, null);
    state.edit("Root body\nChild body");
    assertEquals("", state.parts.error);
    var after = saved(state, DOC);
    var merged =
        after.blocks.stream().filter(b -> b.text().contains("Root body")).findFirst().orElseThrow();
    assertFalse(Events.rootId(merged.text()).isEmpty());
    assertTrue(Events.parents(PATH, merged.text()).isEmpty());
  }

  @Test
  public void equalHeadingNamesDoNotPermitDraggingBetweenDifferentPhysicalSections() {
    var doc = new Markdown("## Alpha\n\nFirst\n\n## Alpha\n\nSecond\n");
    assertThrows(
        IllegalArgumentException.class,
        () -> BlockOrder.move(doc, doc.blocks.get(0), doc.blocks.get(1)));
  }

  @Test
  public void editingInPlaceDoesNotCompressUnrelatedOriginalWhitespace() {
    var doc = new Markdown(DOC.text.replace("Unselected\r\n\r\n", "Unselected\r\n\r\n\r\n\r\n"));
    var state = batch(doc, 1, 3);
    state.edit("Updated\n\nSecond");
    assertEquals(doc.text.replace("First", "Updated"), saved(state, doc).text);
  }

  @Test
  public void opaqueSecretsKeepTheirOwnPayloadAndCannotBeMergedIntoPlainPlaceholders() {
    var doc = new Markdown("## Alpha\n\n`secret AAA=`\n\n`secret BBB=`\n");
    var state = batch(doc, 0, 1);
    assertTrue(state.sensitive);
    assertTrue(plan(state, doc).isEmpty());
    state.edit(SecretText.PLACEHOLDER + "\n" + SecretText.PLACEHOLDER);
    assertFalse(state.parts.error.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> plan(state, doc));
    state.edit(SecretText.PLACEHOLDER + "\n\n" + SecretText.PLACEHOLDER);
    assertTrue(plan(state, doc).isEmpty());
  }

  @Test
  public void batchNeverDropsAnAnchorWhenRoutingToAFieldOrMarkOnlyTask() {
    var doc =
        new Markdown(
            "## Alpha\n\n"
                + Events.root("Root body ^stable")
                + "\n\n## Beta\n\nMetric:: value\n\n- [ ] #task/dairy/fact Fact\n");
    for (var target :
        doc.targets(TEMPLATE).stream().filter(t -> t.kind() != Markdown.Kind.SECTION).toList()) {
      var state = batch(doc, 0);
      var form = state.activePart();
      var choice = form.routingCatalog(TEMPLATE).resolve(target);
      assertNotNull(state.parts.validateRoutes(form, List.of(choice)));
      form.chooseTarget(target);
      form.appendTaskText = false;
      assertThrows(IllegalArgumentException.class, () -> plan(state, doc));
    }
  }

  @Test
  public void oppositeFileTransfersAreRejectedBeforeEitherSourceIsRemoved() {
    var work = new Markdown("## Основные события\n\nWork event\n");
    var note = new WorkDaily.Note(WorkDaily.path(DATE), work);
    var selected = new BlockSelection();
    selected.toggle(PATH, DOC, DOC.blocks.get(1));
    selected.toggle(note.path(), work, work.blocks.get(0));
    var state =
        EditorBatch.create(
            PATH,
            DOC,
            TEMPLATE,
            note,
            selected.ordered(PATH, Map.of(PATH, DOC, note.path(), work)));
    var first = state.parts.parts.get(0).form();
    first.applyRoutes(List.of(WorkDaily.choice()), first.routingCatalog(TEMPLATE));
    var second = state.parts.parts.get(1).form();
    var alpha =
        second.routingCatalog(TEMPLATE).choices.stream()
            .filter(
                c ->
                    c.target().kind() == Markdown.Kind.SECTION
                        && c.target().heading().equals("## Alpha"))
            .findFirst()
            .orElseThrow();
    second.applyRoutes(List.of(alpha), second.routingCatalog(TEMPLATE));
    assertThrows(IllegalArgumentException.class, () -> plan(state, DOC));
  }
}
