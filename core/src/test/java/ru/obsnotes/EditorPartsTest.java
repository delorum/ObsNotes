package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class EditorPartsTest {
  private static final String PATH = DiaryLayout.path(LocalDate.of(2026, 11, 16)),
      PAST = DiaryLayout.path(LocalDate.of(2026, 11, 15));
  private static final LocalDate DATE = LocalDate.of(2026, 11, 16);
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Работа\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Work\"});\n"
              + "```\n\n"
              + "## Дом\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Home\"});\n"
              + "```\n\n"
              + "## Другое\n\n"
              + "## Поля\n"
              + "ShortDescr:: old\n\n"
              + "## Задачи\n"
              + "- [ ] #task/diary TestFact\n");
  private final Markdown source =
      new Markdown(
          "---\n"
              + "key: value\n"
              + "---\n\n"
              + "## Заметки\n\n"
              + "Alpha\n\n"
              + "Tail\n\n"
              + "## Работа\n\n"
              + "Existing\n\n"
              + "## Поля\n"
              + "ShortDescr:: old\n\n"
              + "## Задачи\n"
              + "- [ ] #task/diary TestFact\n");

  private EditorState editor() {
    EditorState s =
        EditorState.editing(
            PATH,
            source,
            TEMPLATE,
            source.blocks.stream().filter(b -> b.text().equals("Alpha")).findFirst().orElseThrow());
    s.enableParts(TEMPLATE);
    return s;
  }

  private void route(EditorState s, int part, String... headings) {
    var form = s.parts.parts.get(part).form();
    var catalog = form.routingCatalog(TEMPLATE);
    form.applyRoutes(
        Arrays.stream(headings)
            .map(
                h ->
                    catalog.choices.stream()
                        .filter(
                            c ->
                                c.target().kind() == Markdown.Kind.SECTION
                                    && c.target().heading().equals(h))
                        .findFirst()
                        .orElseThrow())
            .toList(),
        catalog);
  }

  private List<VaultRepository.Change> plan(EditorState s, Markdown destination) {
    return VaultRepository.plan(s, source, destination, TEMPLATE, DATE, DATE.atTime(12, 0));
  }

  private String saved(EditorState s) {
    return plan(s, source).get(0).after();
  }

  @Test
  public void threePartsHaveIndependentOrderedRoutes() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    route(s, 0, "## Работа", "## Дом");
    route(s, 1, "## Дом", "## Работа");
    route(s, 2, "## Заметки");
    var m = new Markdown(saved(s));
    assertTrue(
        m.blocks.stream()
            .anyMatch(
                b ->
                    b.section().heading().equals("## Работа")
                        && b.text().equals("Alpha #crossPeople/Home")));
    assertTrue(
        m.blocks.stream()
            .anyMatch(
                b ->
                    b.section().heading().equals("## Дом")
                        && b.text().equals("Beta #crossPeople/Work")));
    assertTrue(
        m.blocks.stream()
            .anyMatch(b -> b.section().heading().equals("## Заметки") && b.text().equals("Gamma")));
    assertFalse(Events.hasRelations(PATH, saved(s)));
  }

  @Test
  public void editingMiddleAndInsertingBeforeItKeepsOtherAssignments() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    route(s, 1, "## Работа");
    route(s, 2, "## Дом");
    s.edit("Alpha\n\nNew\n\nBeta\n\nGamma");
    assertEquals(4, s.parts.parts.size());
    assertEquals("## Работа", s.parts.parts.get(2).form().heading);
    assertEquals("## Дом", s.parts.parts.get(3).form().heading);
    s.edit("Alpha\n\nNew\n\nBeta edited\n\nGamma");
    assertEquals("## Работа", s.parts.parts.get(2).form().heading);
    assertEquals("## Дом", s.parts.parts.get(3).form().heading);
  }

  @Test
  public void deletingUnanchoredPartDoesNotShiftRoutes() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    route(s, 1, "## Работа");
    route(s, 2, "## Дом");
    s.edit("Alpha\n\nGamma");
    assertEquals(2, s.parts.parts.size());
    assertEquals("## Дом", s.parts.parts.get(1).form().heading);
  }

  @Test
  public void splitInheritsBlockRoutesButLineTagsFollowTheirLine() {
    var s = editor();
    s.edit("Alpha\nBeta");
    route(s, 0, "## Работа", "## Дом");
    s.parts.parts.get(0).form().addTags(6, List.of("Work"), true, false);
    s.edit("Alpha\n\nBeta");
    assertEquals(2, s.parts.parts.size());
    assertEquals(2, s.parts.parts.get(1).form().routes.size());
    assertFalse(s.parts.parts.get(0).form().tags().stream().anyMatch(TaggedText.Tag::oneLine));
    assertTrue(s.parts.parts.get(1).form().tags().stream().anyMatch(TaggedText.Tag::oneLine));
  }

  @Test
  public void sameSectionSplitPreservesPositionAndOutsideBytesIncludingCRLF() {
    var input = new Markdown(source.text.replace("\n", "\r\n"));
    var block =
        input.blocks.stream().filter(b -> b.text().equals("Alpha")).findFirst().orElseThrow();
    var s = EditorState.editing(PATH, input, TEMPLATE, block);
    s.enableParts(TEMPLATE);
    s.edit("Alpha\n\nBeta");
    String actual =
        VaultRepository.plan(s, input, input, TEMPLATE, DATE, DATE.atTime(12, 0)).get(0).after();
    assertEquals(input.text.replace("Alpha", "Alpha\r\n\r\nBeta"), actual);
  }

  @Test
  public void linkSwitchCreatesOneRootAndEveryLowerPartPointsToIt() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    route(s, 0, "## Работа");
    route(s, 1, "## Дом");
    s.parts.linked = true;
    var m = new Markdown(saved(s));
    var root =
        m.blocks.stream().filter(b -> !Events.rootId(b.text()).isEmpty()).findFirst().orElseThrow();
    String id = Events.rootId(root.text());
    assertEquals(1, m.blocks.stream().filter(b -> !Events.rootId(b.text()).isEmpty()).count());
    assertEquals(
        2,
        m.blocks.stream()
            .filter(
                b ->
                    Events.parent(PATH, b.text())
                        .map(p -> p.id().equals(id) && p.path().equals(PATH))
                        .orElse(false))
            .count());
    assertFalse(s.editableText().contains("#^"));
  }

  @Test
  public void existingRootIdentityIsKeptOnFirstPart() {
    var m = new Markdown("## Заметки\n\n" + Events.root("Alpha\nBeta") + "\n");
    var s = EditorState.editing(PATH, m, TEMPLATE, m.blocks.get(0));
    String id = Events.rootId(s.text);
    s.enableParts(TEMPLATE);
    s.edit("Alpha\n\nBeta");
    s.parts.linked = true;
    var changes = VaultRepository.plan(s, m, m, TEMPLATE, DATE, DATE.atTime(12, 0));
    assertTrue(changes.get(0).after().contains("^" + id));
    assertEquals(
        1,
        new Markdown(changes.get(0).after())
            .blocks.stream().filter(b -> Events.rootId(b.text()).equals(id)).count());
  }

  @Test
  public void fencedBlankLinesDoNotStartParts() {
    var s = editor();
    s.edit("Alpha\n```java\nfirst\n\nsecond\n```\n\nBeta");
    assertEquals(2, s.parts.parts.size());
    assertTrue(s.parts.parts.get(0).form().editableText().contains("first\n\nsecond"));
  }

  @Test
  public void compatibleMergeKeepsLowerDestinationAsCrossPeople() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    route(s, 0, "## Работа");
    route(s, 1, "## Дом");
    s.edit("Alpha\nBeta");
    assertEquals("", s.parts.error);
    assertEquals(1, s.parts.parts.size());
    assertEquals("## Работа", s.parts.parts.get(0).form().heading);
    assertTrue(saved(s).contains("#crossPeople/Home"));
  }

  @Test
  public void unrepresentableMergeKeepsAllAssignmentsAndCanBeUndone() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    route(s, 1, "## Другое");
    s.edit("Alpha\nBeta");
    assertFalse(s.parts.error.isEmpty());
    assertEquals(2, s.parts.parts.size());
    assertThrows(IllegalArgumentException.class, () -> saved(s));
    assertEquals("Alpha\nBeta", s.conflictCopyText());
    s.edit("Alpha\n\nBeta");
    assertEquals("", s.parts.error);
    assertEquals("## Другое", s.parts.parts.get(1).form().heading);
  }

  @Test
  public void moveToPastConsolidatesDestinationBeforeSourceAndDoesNotDuplicateParts() {
    var past = new Markdown(TEMPLATE.text);
    var s = editor();
    s.edit("Alpha\n\nBeta");
    route(s, 0, "## Работа");
    route(s, 1, "## Дом");
    s.chooseDay(PAST, past.text);
    for (var p : s.parts.parts) p.form().initializeRoutes(TEMPLATE);
    var changes = plan(s, past);
    assertEquals(2, changes.size());
    assertEquals(PAST, changes.get(0).path());
    assertEquals(PATH, changes.get(1).path());
    assertEquals(1, changes.get(0).after().split("Alpha", -1).length - 1);
    assertFalse(changes.get(1).after().contains("Alpha"));
  }

  @Test
  public void ordinaryOnePartStillUsesLosslessExistingPlan() {
    var s = editor();
    s.edit("Alpha edited");
    assertEquals(source.text.replace("Alpha", "Alpha edited"), saved(s));
  }

  @Test
  public void fieldDestinationCollapsesMultilineAndGroupLinksToTasksAreRejected() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    var f = s.parts.parts.get(1).form();
    var catalog = f.routingCatalog(TEMPLATE);
    var target =
        catalog.choices.stream()
            .filter(c -> c.target().kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    f.applyRoutes(List.of(target), catalog);
    s.parts.linked = true;
    assertNotNull(s.parts.validateRoutes(f, List.of(target)));
    assertThrows(IllegalArgumentException.class, () -> saved(s));
    s.parts.linked = false;
    assertTrue(saved(s).contains("ShortDescr:: old; Beta"));
    f.edit("Beta\nline");
    assertNull(f.validateRoutes(List.of(target)));
    assertTrue(f.savedText().contains("Beta; line"));
  }

  @Test
  public void successfulPartAssignmentsContributeUsageOnlyOnce() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    route(s, 0, "## Работа", "## Дом");
    route(s, 1, "## Дом", "## Работа");
    var uses = SectionUsage.planned(s, TEMPLATE, plan(s, source));
    assertTrue(uses.contains(new SectionUsage.Use("## Работа", SectionUsage.Role.PRIMARY)));
    assertTrue(uses.contains(new SectionUsage.Use("## Дом", SectionUsage.Role.PRIMARY)));
    assertEquals(4, uses.size());
  }

  @Test
  public void addingPersonNameUpdatesSharedTextWithoutErasingOtherParts() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.parts.parts.get(1).form().addTags(0, List.of("Лена"), false, true);
    s.parts.sync();
    assertEquals("Alpha\n\nBeta Лена", s.editableText());
    assertTrue(saved(s).contains("Beta #person/Лена"));
    assertEquals(2, s.parts.parts.size());
  }

  @Test
  public void removingAnchoredFirstPartCannotSilentlyDeleteItsIncomingLinks() {
    var m = new Markdown("## Заметки\n\n" + Events.root("Alpha\nBeta") + "\n");
    var s = EditorState.editing(PATH, m, TEMPLATE, m.blocks.get(0));
    s.enableParts(TEMPLATE);
    s.edit("Alpha\n\nBeta");
    s.edit("Beta");
    assertFalse(s.parts.error.isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> VaultRepository.plan(s, m, m, TEMPLATE, DATE, DATE.atTime(12, 0)));
    s.edit("Alpha\n\nBeta");
    assertEquals("", s.parts.error);
  }

  @Test
  public void newGroupRootsCanBeCreatedAtPastDestinationWithoutBreakingExistingAnchors() {
    var past = new Markdown(TEMPLATE.text);
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.chooseDay(PAST, past.text);
    for (var p : s.parts.parts) p.form().initializeRoutes(TEMPLATE);
    s.parts.linked = true;
    var changes = plan(s, past);
    assertEquals(PAST, changes.get(0).path());
    var doc = new Markdown(changes.get(0).after());
    var root =
        doc.blocks.stream()
            .filter(b -> !Events.rootId(b.text()).isEmpty())
            .findFirst()
            .orElseThrow();
    assertTrue(
        doc.blocks.stream()
            .anyMatch(
                b ->
                    Events.parent(PAST, b.text())
                        .map(
                            parent ->
                                parent.path().equals(PAST)
                                    && parent.id().equals(Events.rootId(root.text())))
                        .orElse(false)));
    assertFalse(changes.get(1).after().contains("Alpha"));
  }

  private static class Journal implements VaultRepository.Journal {
    List<VaultRepository.Change> pending = List.of();

    public void prepare(List<VaultRepository.Change> changes) {
      pending = List.copyOf(changes);
    }

    public List<VaultRepository.Change> pending() {
      return pending;
    }

    public void complete() {
      pending = List.of();
    }
  }

  @Test
  public void interruptedMultiPartMoveRecoversWithoutRepeatingAnyPart() throws Exception {
    var past = new Markdown(TEMPLATE.text);
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.chooseDay(PAST, past.text);
    for (var part : s.parts.parts) part.form().initializeRoutes(TEMPLATE);
    route(s, 0, "## Работа");
    route(s, 1, "## Дом");
    var changes = plan(s, past);
    var storage = new RepositoryTest.Storage();
    storage.files.clear();
    storage.files.put(PATH, source.text);
    storage.files.put(PAST, past.text);
    storage.failAt = 2;
    var journal = new Journal();
    var repo = new VaultRepository(storage, journal);
    assertThrows(java.io.IOException.class, () -> repo.commit(changes));
    assertEquals(source.text, storage.read(PATH));
    assertEquals(changes.get(0).after(), storage.read(PAST));
    repo.recover();
    repo.recover();
    assertEquals(changes.get(1).after(), storage.read(PATH));
    for (String part : List.of("Alpha", "Beta", "Gamma"))
      assertEquals(1, storage.read(PAST).split(part, -1).length - 1);
    assertTrue(journal.pending().isEmpty());
  }

  @Test
  public void externalSourceChangePreventsAllMultiPartDestinationWrites() throws Exception {
    var past = new Markdown(TEMPLATE.text);
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.chooseDay(PAST, past.text);
    for (var part : s.parts.parts) part.form().initializeRoutes(TEMPLATE);
    var storage = new RepositoryTest.Storage();
    storage.files.clear();
    storage.files.put(PATH, source.text + "external");
    storage.files.put(PAST, past.text);
    var journal = new Journal();
    var repo = new VaultRepository(storage, journal);
    assertThrows(VaultRepository.Conflict.class, () -> repo.commit(plan(s, past)));
    assertEquals(0, storage.writes);
    assertEquals(past.text, storage.read(PAST));
    assertTrue(journal.pending().isEmpty());
  }

  @Test
  public void chainCreatesRootAndIntermediateRootWithCorrectLeadingLinks() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    route(s, 0, "## Работа");
    route(s, 1, "## Дом");
    route(s, 2, "## Работа");
    var m = new Markdown(saved(s));
    var first =
        m.blocks.stream()
            .filter(b -> CrossReferences.clean(b.text()).equals("Alpha"))
            .findFirst()
            .orElseThrow();
    var second =
        m.blocks.stream()
            .filter(b -> CrossReferences.clean(b.text()).equals("Beta"))
            .findFirst()
            .orElseThrow();
    var third =
        m.blocks.stream()
            .filter(b -> CrossReferences.clean(b.text()).equals("Gamma"))
            .findFirst()
            .orElseThrow();
    assertFalse(Events.rootId(first.text()).isEmpty());
    assertFalse(Events.rootId(second.text()).isEmpty());
    assertEquals(
        Events.rootId(first.text()), Events.parent(PATH, second.text()).orElseThrow().id());
    assertEquals(
        Events.rootId(second.text()), Events.parent(PATH, third.text()).orElseThrow().id());
    assertEquals("К части 1 · Корень", s.parts.relationLabel(1));
    assertEquals("К части 2", s.parts.relationLabel(2));
  }

  @Test
  public void cycleIsRejectedWithoutChangingTheOriginalGraph() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    var before = Map.copyOf(s.parts.links.parents);
    assertThrows(IllegalArgumentException.class, () -> s.parts.linkTo(0, 2));
    assertEquals(before, s.parts.links.parents);
    assertThrows(IllegalArgumentException.class, () -> s.parts.linkTo(1, 1));
    assertEquals(before, s.parts.links.parents);
  }

  @Test
  public void linksFollowIdentitiesWhenANewPartIsInsertedBeforeTheParent() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    String parent = s.parts.parts.get(1).form().partId, child = s.parts.parts.get(2).form().partId;
    s.edit("Alpha\n\nInserted\n\nBeta\n\nGamma");
    assertEquals("", s.parts.error);
    assertEquals(4, s.parts.parts.size());
    assertEquals(parent, s.parts.parts.get(2).form().partId);
    assertEquals(child, s.parts.parts.get(3).form().partId);
    assertEquals(parent, s.parts.links.parents.get(child));
    assertEquals("К части 3", s.parts.relationLabel(3));
  }

  @Test
  public void deletingConnectedPartKeepsItsEdgesAndCanBeUndone() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    var before = Map.copyOf(s.parts.links.parents);
    s.edit("Alpha\n\nGamma");
    assertFalse(s.parts.error.isEmpty());
    assertEquals(before, s.parts.links.parents);
    assertThrows(IllegalArgumentException.class, () -> saved(s));
    s.edit("Alpha\n\nBeta\n\nGamma");
    assertEquals("", s.parts.error);
    assertEquals(before, s.parts.links.parents);
  }

  @Test
  public void rootPromotionAndReparentingDoNotKeepUnrequestedIntermediateRoots() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linked = true;
    s.parts.linkTo(2, 1);
    assertTrue(s.parts.links.configured);
    assertEquals("К части 2", s.parts.relationLabel(2));
    s.parts.linkTo(2, 0);
    var m = new Markdown(saved(s));
    assertEquals(1, m.blocks.stream().filter(b -> !Events.rootId(b.text()).isEmpty()).count());
    s.parts.removeReference(1, s.parts.references(1).get(0).key());
    s.parts.makeRoot(1);
    s.parts.linkTo(2, 1);
    s.parts.independent(0);
    assertEquals("Корень", s.parts.relationLabel(1));
    assertEquals("Связь", s.parts.relationLabel(0));
    assertEquals(
        1,
        new Markdown(saved(s))
            .blocks.stream().filter(b -> !Events.rootId(b.text()).isEmpty()).count());
  }

  @Test
  public void commonControlsSelectThePartAndRelativeCursorWithoutMovingIt() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    assertEquals(0, s.parts.selectAt(1));
    assertEquals(1, s.parts.selectAt(8));
    assertEquals(2, s.parts.selectAt(s.editableText().length()));
    s.cursor = 8;
    s.parts.selectAt(s.cursor);
    route(s, s.parts.selected, "## Работа", "## Дом");
    assertEquals(8, s.cursor);
    assertEquals("## Работа", s.parts.parts.get(1).form().heading);
    assertEquals("## Заметки", s.parts.parts.get(0).form().heading);
  }

  @Test
  public void lineColorIndicesAreUniqueAcrossAllPartsAndSharedWithinOneLine() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    for (var part : s.parts.parts) part.form().addTags(0, List.of("Work"), true, false);
    s.parts.parts.get(1).form().addTags(0, List.of("Beta"), true, true);
    s.parts.sync();
    var marks = LineMarks.of(s.editableText(), s.tags());
    assertEquals(List.of(0, 1, 2), marks.stream().map(LineMarks.Mark::colorIndex).toList());
    assertEquals(
        3, marks.stream().map(mark -> LineColors.argb(mark.colorIndex())).distinct().count());
    assertEquals("Beta", s.editableText().substring(marks.get(1).start(), marks.get(1).end()));
  }

  @Test
  public void dateChoiceMovesEveryPartToExistingFutureSnapshotAndKeepsItsGraph() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    String future = DiaryLayout.path(DATE.plusDays(5));
    s.chooseDay(future, TEMPLATE.text);
    for (var part : s.parts.parts) {
      part.form().initializeRoutes(TEMPLATE);
      assertEquals(future, part.form().destinationPath);
    }
    var changes = plan(s, TEMPLATE);
    assertEquals(future, changes.get(0).path());
    assertEquals(PATH, changes.get(1).path());
    var m = new Markdown(changes.get(0).after());
    assertEquals(
        2, m.blocks.stream().filter(b -> Events.parent(future, b.text()).isPresent()).count());
    assertFalse(changes.get(1).after().contains("Alpha"));
  }

  @Test
  public void timestampNeverDisplacesALeadingEventParentLink() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    route(s, 0, "## Работа");
    var m = new Markdown(saved(s));
    assertEquals(
        2, m.blocks.stream().filter(b -> Events.parent(PATH, b.text()).isPresent()).count());
    assertTrue(m.text.contains(DATE + " 12:00:00"));
  }

  @Test
  public void rejectedLegacyStarCycleDoesNotChangeToggleOrRecoveryMode() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.parts.linked = true;
    var before = Map.copyOf(s.parts.effectiveLinks().parents);
    assertThrows(IllegalArgumentException.class, () -> s.parts.linkTo(0, 1));
    assertFalse(s.parts.links.configured);
    assertTrue(s.parts.linked);
    assertEquals(before, s.parts.effectiveLinks().parents);
  }

  @Test
  public void insertingBeforeFirstPartPreservesItsExistingIdentityAndChildren() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.parts.linkTo(1, 0);
    String root = s.parts.parts.get(0).form().partId, child = s.parts.parts.get(1).form().partId;
    s.edit("Inserted\n\nAlpha\n\nBeta");
    assertEquals("", s.parts.error);
    assertEquals(root, s.parts.parts.get(1).form().partId);
    assertEquals(child, s.parts.parts.get(2).form().partId);
    assertNotEquals(root, s.parts.parts.get(0).form().partId);
    assertEquals("К части 2", s.parts.relationLabel(2));
  }

  @Test
  public void customChainKeepsLinkCheckboxEnabledAndCanBeDisabledTogether() {
    var s = editor();
    s.edit("Alpha\n\nBeta\n\nGamma");
    assertFalse(s.parts.linksEnabled());
    s.parts.linkAll(true);
    assertTrue(s.parts.linksEnabled());
    s.parts.linkTo(2, 1);
    assertTrue(s.parts.linksEnabled());
    assertEquals("К части 2", s.parts.relationLabel(2));
    s.parts.linkAll(false);
    assertFalse(s.parts.linksEnabled());
    assertTrue(s.parts.effectiveLinks().parents.isEmpty());
    assertEquals("Связь", s.parts.relationLabel(0));
  }

  @Test
  public void legacyCheckboxRecoveryKeepsExistingEdgesButDoesNotAutoLinkNewParts() {
    var s = editor();
    s.edit("Alpha\n\nBeta");
    s.parts.linked = true;
    s.enableParts(TEMPLATE);
    assertFalse(s.parts.linked);
    assertTrue(s.parts.links.configured);
    assertEquals(1, s.parts.links.parents.size());
    s.edit("Alpha\n\nBeta\n\nGamma");
    assertEquals("", s.parts.error);
    assertEquals(1, s.parts.links.parents.size());
    assertEquals("Связь", s.parts.relationLabel(2));
  }
}
