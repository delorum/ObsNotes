package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class WorkDailyTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String DAY = DiaryLayout.path(DATE), WORK = WorkDaily.path(DATE);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Новости\n\n## Личное\n");
  private static final Markdown DAILY =
      new Markdown("## Заметки\n\nMove me\n\n## Новости\n\n## Личное\n");
  private static final Markdown WORKDOC =
      new Markdown(
          "---\r\n"
              + "keep: yes\r\n"
              + "---\r\n\r\n"
              + "## Идеи\r\n\r\n"
              + "Keep idea\r\n\r\n"
              + "## Основные события\r\n\r\n"
              + "Old event\r\n\r\n"
              + "## Встречи\r\n\r\n"
              + "Keep meeting\r\n");

  private static EditorState dailyEditor() {
    var state = EditorState.editing(DAY, DAILY, TEMPLATE, DAILY.blocks.get(0));
    WorkDaily.snapshot(state, new WorkDaily.Note(WORK, WORKDOC));
    state.enableParts(TEMPLATE);
    return state;
  }

  private static List<VaultRepository.Change> plan(EditorState state, Markdown source) {
    return VaultRepository.plan(state, source, DAILY, TEMPLATE, DATE, DATE.atTime(12, 0));
  }

  private static void chooseWork(EditorState form) {
    form.applyRoutes(List.of(WorkDaily.choice()), form.routingCatalog(TEMPLATE));
  }

  @Test
  public void pathUsesRussianMonthsAndIsoWeekYearExactlyIncludingYearBoundary() {
    assertEquals(
        "Работа/Daily/Хроника/2021/1/01-январь/2020-53-неделя/2021-01-01-пт-work.md",
        WorkDaily.path(LocalDate.of(2021, 1, 1)));
    assertEquals(WorkDaily.path(DATE), DailyChecklist.workPath(DATE));
  }

  @Test
  public void loadProbesOnlyTheExactPathAndDoesNotReadOrEnumerateMissingDays() throws Exception {
    List<String> calls = new ArrayList<>();
    VaultStorage storage =
        new VaultStorage() {
          public boolean exists(String p) {
            calls.add("exists " + p);
            return false;
          }

          public String read(String p) {
            throw new AssertionError("read");
          }

          public void write(String p, String t) {
            throw new AssertionError("write");
          }

          public List<String> dailyNotes() {
            throw new AssertionError("enumeration");
          }
        };
    assertNull(WorkDaily.load(storage, DATE));
    assertEquals(List.of("exists " + WORK), calls);
  }

  @Test
  public void workOutlineIsAfterNewsAndShowsOnlyEventsWithSourceIdentity() {
    var roots = DayOutline.sections(DAILY, TEMPLATE);
    assertSame(roots, WorkDaily.outline(roots, null, DayContentRules.EMPTY));
    var all = WorkDaily.outline(roots, new WorkDaily.Note(WORK, WORKDOC), DayContentRules.EMPTY);
    assertEquals(
        List.of("## Заметки", "## Новости", WorkDaily.ROOT, "## Личное"),
        all.stream().map(DayOutline.Section::heading).toList());
    var work = all.get(2);
    var events = work.children().get(0);
    assertEquals("Работа", work.title());
    assertEquals("Основные события", events.title());
    assertEquals(1, work.count());
    assertEquals("Old event", events.items().get(0).view().text());
    assertEquals(WORK, events.items().get(0).path());
    assertSame(WORKDOC.blocks.get(1), events.items().get(0).original());
    assertEquals(1, new DaySearch(all).result("Работа").sections().size());
  }

  private static final String WORK_HINT = "*Выделяй жирным шрифтом важное!*";

  private static DayOutline.Section workSection(Markdown work, DayContentRules rules) {
    return WorkDaily.outline(List.of(), new WorkDaily.Note(WORK, work), rules).get(0);
  }

  @Test
  public void workHintAloneIsNeitherVisibleNorCounted() {
    for (String eol : List.of("\n", "\r\n")) {
      var doc = new Markdown(WorkDaily.EVENTS + eol + eol + "  " + WORK_HINT + "  " + eol);
      var work = workSection(doc, DayContentRules.EMPTY);
      assertEquals(0, work.count());
      assertEquals(0, work.children().get(0).count());
      assertTrue(work.children().get(0).items().isEmpty());
      assertTrue(doc.text.contains(WORK_HINT));
      assertFalse(DayContentRules.EMPTY.excluded(WORK_HINT));
    }
  }

  @Test
  public void mixedWorkBlockFiltersHintAndExistingRulesWithoutChangingSourceOffsets() {
    var doc =
        new Markdown(
            WorkDaily.EVENTS
                + "\n\nBefore\n"
                + WORK_HINT
                + "\nOldHint\nServiceToken hidden\nAfter\n");
    var rules = new DayContentRules(List.of("OldHint"), List.of("ServiceToken"));
    var work = workSection(doc, rules);
    var events = work.children().get(0);
    assertEquals(1, work.count());
    assertEquals(1, events.items().size());
    var item = events.items().get(0);
    assertEquals("Before\nAfter", item.view().text());
    assertSame(doc.blocks.get(0), item.original());
    assertEquals(WORK, item.path());
    assertEquals(
        List.of(0, item.original().text().indexOf("After")),
        item.view().lines().stream().map(MarkdownLines.Line::start).toList());
    assertEquals(Set.of("OldHint"), rules.excludedLines());
    assertEquals(WORK_HINT, rules.view(WORK_HINT).text());
  }

  @Test
  public void workHintFilterKeepsEmbeddedTextAndFencedCode() {
    var doc =
        new Markdown(
            WorkDaily.EVENTS
                + "\n\n"
                + WORK_HINT
                + "\n\nPrefix "
                + WORK_HINT
                + "\n\n```text\n"
                + WORK_HINT
                + "\n```\n");
    var work = workSection(doc, DayContentRules.EMPTY);
    assertEquals(2, work.count());
    var items = work.children().get(0).items();
    assertEquals(2, items.size());
    assertEquals("Prefix " + WORK_HINT, items.get(0).view().text());
    assertTrue(items.get(1).view().text().contains(WORK_HINT));
  }

  @Test
  public void editingVisibleWorkEventKeepsHiddenHintAndPhysicalOffsets() {
    var doc = new Markdown(WorkDaily.EVENTS + "\r\n\r\n" + WORK_HINT + "\r\n\r\nActual event\r\n");
    var item = workSection(doc, DayContentRules.EMPTY).children().get(0).items().get(0);
    assertEquals("Actual event", item.view().text());
    var state =
        WorkDaily.editor(DAY, DAILY, new WorkDaily.Note(WORK, doc), item.original(), TEMPLATE);
    state.enableParts(TEMPLATE);
    assertTrue(plan(state, doc).isEmpty());
    state.edit("Changed event");
    var changes = plan(state, doc);
    assertEquals(1, changes.size());
    assertEquals(WORK, changes.get(0).path());
    assertEquals(doc.text.replace("Actual event", "Changed event"), changes.get(0).after());
  }

  @Test
  public void catalogOffersWorkOnlyWhenAvailableAndNeverAsAdditionalRoute() {
    assertFalse(new Routing.Catalog(DAILY, TEMPLATE).choices.contains(WorkDaily.choice()));
    assertTrue(new Routing.Catalog(DAILY, TEMPLATE, true).choices.contains(WorkDaily.choice()));
    var state = dailyEditor().activePart();
    assertNotNull(state.validateRoutes(List.of(WorkDaily.choice(), state.routes.get(0))));
    assertNotNull(state.validateRoutes(List.of(state.routes.get(0), WorkDaily.choice())));
    state.workSnapshot = null;
    assertNotNull(state.validateRoutes(List.of(WorkDaily.choice())));
  }

  @Test
  public void moveWritesWorkBeforeRemovingDailySourceAndKeepsOtherWorkSections() {
    var state = dailyEditor();
    chooseWork(state.activePart());
    var changes = plan(state, DAILY);
    assertEquals(List.of(WORK, DAY), changes.stream().map(VaultRepository.Change::path).toList());
    assertTrue(changes.get(0).after().contains("Move me"));
    assertTrue(changes.get(0).after().contains("Keep idea"));
    assertTrue(changes.get(0).after().contains("Keep meeting"));
    assertFalse(changes.get(0).after().contains("12:00"));
    assertFalse(changes.get(1).after().contains("Move me"));
    assertTrue(changes.get(0).after().contains("\r\n"));
  }

  @Test
  public void nativeWorkEditingNoopAndUpdateStayInEventsWithoutDailyWrite() {
    var note = new WorkDaily.Note(WORK, WORKDOC);
    var state = WorkDaily.editor(DAY, DAILY, note, WORKDOC.blocks.get(1), TEMPLATE);
    state.enableParts(TEMPLATE);
    assertTrue(EditorRevision.unchanged(state, TEMPLATE));
    assertTrue(plan(state, WORKDOC).isEmpty());
    state.edit("Updated event");
    var changes = plan(state, WORKDOC);
    assertEquals(1, changes.size());
    assertEquals(WORK, changes.get(0).path());
    assertEquals(WORKDOC.text.replace("Old event", "Updated event"), changes.get(0).after());
  }

  @Test
  public void newWorkNoteCanSplitIntoIndependentWorkPartsWithoutTimestampOrTags() {
    var state = WorkDaily.editor(DAY, DAILY, new WorkDaily.Note(WORK, WORKDOC), null, TEMPLATE);
    state.enableParts(TEMPLATE);
    state.edit("First\n\nSecond");
    assertEquals(2, state.parts.parts.size());
    var changes = plan(state, WORKDOC);
    assertTrue(
        changes.stream()
            .filter(c -> !c.before().equals(c.after()))
            .allMatch(c -> c.path().equals(WORK)));
    String after =
        changes.stream().filter(c -> c.path().equals(WORK)).findFirst().orElseThrow().after();
    assertTrue(after.contains("First\r\n\r\nSecond"));
    assertFalse(after.contains("#crossPeople"));
    assertFalse(after.contains("12:00"));
  }

  @Test
  public void mixedDailyAndWorkPartsAreCommittedTogetherDestinationFirst() {
    var state = dailyEditor();
    state.edit("Move\n\nStay");
    chooseWork(state.parts.parts.get(0).form());
    var changes = plan(state, DAILY);
    assertEquals(WORK, changes.get(0).path());
    String day =
        changes.stream().filter(c -> c.path().equals(DAY)).findFirst().orElseThrow().after();
    assertTrue(day.contains("Stay"));
    assertFalse(day.contains("Move me"));
    assertTrue(changes.get(0).after().contains("Move"));
  }

  @Test
  public void nativeWorkPartCanMoveToDailyWhileAnotherPartStaysAtWork() {
    var state =
        WorkDaily.editor(
            DAY, DAILY, new WorkDaily.Note(WORK, WORKDOC), WORKDOC.blocks.get(1), TEMPLATE);
    state.enableParts(TEMPLATE);
    state.edit("Stay at work\n\nBack home");
    var second = state.parts.parts.get(1).form();
    var catalog = second.routingCatalog(TEMPLATE);
    second.applyRoutes(
        List.of(
            catalog.choices.stream()
                .filter(c -> c.target().heading().equals("## Личное"))
                .findFirst()
                .orElseThrow()),
        catalog);
    var changes = plan(state, WORKDOC);
    assertEquals(DAY, changes.get(0).path());
    assertTrue(changes.get(0).after().contains("Back home"));
    assertTrue(
        changes.stream()
            .filter(c -> c.path().equals(WORK))
            .findFirst()
            .orElseThrow()
            .after()
            .contains("Stay at work"));
  }

  @Test
  public void choosingWorkDropsMetadataAndValueShellButPreservesVisibleText() {
    var state = dailyEditor();
    var form = state.activePart();
    form.addTags(0, List.of("Unknown"), false, false);
    chooseWork(form);
    assertTrue(form.tags().isEmpty());
    assertEquals(1, form.routes.size());
    assertFalse(plan(state, DAILY).get(0).after().contains("#crossPeople"));
  }

  @Test
  public void workCalloutIsHiddenAsAWholeWhileSurroundingTextAndOtherCalloutsRemain() {
    String text =
        "Before\n"
            + "> [!info]- Работа за 2001-02-03\n"
            + "> ```dataviewjs\n"
            + "> ignored();\n"
            + "> ```\n\n"
            + "After\n"
            + "> [!info]- Other\n"
            + "> Keep this";
    var view = DayContentRules.EMPTY.view(text);
    assertTrue(view.text().contains("Before"));
    assertTrue(view.text().contains("After"));
    assertTrue(view.text().contains("Keep this"));
    assertFalse(view.raw().contains("Работа за"));
    assertFalse(view.raw().contains("ignored"));
    assertEquals(
        text.indexOf("After"),
        view.lines().stream()
            .filter(l -> l.text().equals("After"))
            .findFirst()
            .orElseThrow()
            .start());
    assertEquals(
        "",
        DayContentRules.EMPTY
            .view(text.substring(text.indexOf("> [!info]- Работа"), text.indexOf("After")))
            .raw());
  }

  private static final class Store implements VaultStorage {
    final Map<String, String> files = new HashMap<>();
    final List<String> writes = new ArrayList<>();

    public String read(String path) throws IOException {
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      return files.get(path);
    }

    public void write(String path, String text) throws IOException {
      if (!files.containsKey(path)) throw new AssertionError("Attempted creation");
      writes.add(path);
      files.put(path, text);
    }

    public List<String> dailyNotes() {
      throw new AssertionError("scan");
    }
  }

  private static final class Journal implements VaultRepository.Journal {
    List<VaultRepository.Change> pending = List.of();
    int prepared;

    public void prepare(List<VaultRepository.Change> c) {
      pending = c;
      prepared++;
    }

    public List<VaultRepository.Change> pending() {
      return pending;
    }

    public void complete() {
      pending = List.of();
    }
  }

  @Test
  public void changedOrDeletedWorkDestinationNeverRemovesSourceOrCreatesAFile() throws Exception {
    var state = dailyEditor();
    chooseWork(state.activePart());
    var changes = plan(state, DAILY);
    for (boolean deleted : List.of(false, true)) {
      var store = new Store();
      var journal = new Journal();
      store.files.put(DAY, DAILY.text);
      if (!deleted) store.files.put(WORK, WORKDOC.text + "external");
      var repo = new VaultRepository(store, journal);
      if (deleted) assertThrows(FileNotFoundException.class, () -> repo.commit(changes));
      else assertThrows(VaultRepository.Conflict.class, () -> repo.commit(changes));
      assertEquals(DAILY.text, store.files.get(DAY));
      assertTrue(store.writes.isEmpty());
      assertEquals(0, journal.prepared);
    }
  }

  @Test
  public void successfulMoveUsesVerifiedWorkWriteBeforeDiaryRemoval() throws Exception {
    var state = dailyEditor();
    chooseWork(state.activePart());
    var store = new Store();
    store.files.put(DAY, DAILY.text);
    store.files.put(WORK, WORKDOC.text);
    var journal = new Journal();
    new VaultRepository(store, journal).commit(plan(state, DAILY));
    assertEquals(List.of(WORK, DAY), store.writes);
    assertTrue(store.files.get(WORK).contains("Move me"));
    assertFalse(store.files.get(DAY).contains("Move me"));
    assertTrue(journal.pending.isEmpty());
  }

  @Test
  public void workFileMissingEventsGetsOnlyEventsSectionInExistingFile() {
    var work = new Markdown("## Встречи\n\nKeep\n");
    var state = WorkDaily.editor(DAY, DAILY, new WorkDaily.Note(WORK, work), null, TEMPLATE);
    state.enableParts(TEMPLATE);
    state.edit("Added");
    var changes = plan(state, work);
    assertTrue(
        changes.stream()
            .filter(c -> !c.before().equals(c.after()))
            .allMatch(c -> c.path().equals(WORK)));
    String after =
        changes.stream().filter(c -> c.path().equals(WORK)).findFirst().orElseThrow().after();
    assertTrue(after.contains("## Основные события"));
    assertTrue(after.contains("Keep"));
    assertTrue(after.contains("Added"));
    assertFalse(after.contains("## Заметки"));
  }

  @Test
  public void selectingADateWithoutWorkLeavesTheEditorUntouched() {
    var state = dailyEditor();
    chooseWork(state.activePart());
    String before = EditorRevision.contents(state, TEMPLATE);
    assertThrows(IllegalArgumentException.class, () -> WorkDaily.requireTarget(state, null));
    assertEquals(before, EditorRevision.contents(state, TEMPLATE));
    assertEquals(DAY, state.destinationPath);
  }

  @Test
  public void resolvingNativeWorkConflictCopiesToDailyNotesAndKeepsWorkExternalEdits()
      throws Exception {
    var state =
        WorkDaily.editor(
            DAY, DAILY, new WorkDaily.Note(WORK, WORKDOC), WORKDOC.blocks.get(1), TEMPLATE);
    state.enableParts(TEMPLATE);
    state.edit("My edit");
    var store = new Store();
    store.files.put(DAY, DAILY.text);
    store.files.put(WORK, WORKDOC.text + "External");
    var opened =
        ConflictResolution.resolve(
            new VaultRepository(store, new Journal()),
            store,
            state,
            TEMPLATE,
            true,
            DATE.atTime(12, 0));
    assertEquals(DAY, opened.path());
    assertTrue(opened.document().text.contains("My edit"));
    assertEquals(WORKDOC.text + "External", store.files.get(WORK));
    assertEquals(List.of(DAY), store.writes);
  }

  @Test
  public void adjacentNonWorkCalloutIsNotHiddenWithWorkCallout() {
    String raw = "> [!info]- Работа за 2001-02-03\n> hidden\n> [!warning]- Keep\n> visible";
    var view = DayContentRules.EMPTY.view(raw);
    assertFalse(view.text().contains("hidden"));
    assertTrue(view.text().contains("visible"));
    assertEquals(raw.indexOf("> [!warning]"), view.lines().get(0).start());
  }

  @Test
  public void ordinaryWorkEditsDoNotIncreaseRoutingFrequency() {
    var state =
        WorkDaily.editor(
            DAY, DAILY, new WorkDaily.Note(WORK, WORKDOC), WORKDOC.blocks.get(1), TEMPLATE);
    state.enableParts(TEMPLATE);
    state.edit("Changed");
    assertTrue(SectionUsage.planned(state, TEMPLATE, plan(state, WORKDOC)).isEmpty());
  }

  @Test
  public void workPartMovesToTheExistingWorkFileOfTheSelectedDate() {
    var state = dailyEditor();
    chooseWork(state.activePart());
    var nextDate = DATE.plusDays(3);
    String nextDay = DiaryLayout.path(nextDate), nextWork = WorkDaily.path(nextDate);
    var nextDiary = new Markdown("## Заметки\n\n## Новости\n");
    var nextWorkDoc = new Markdown("## Основные события\n\nNext existing event\n");
    var note = new WorkDaily.Note(nextWork, nextWorkDoc);
    WorkDaily.requireTarget(state, note);
    state.chooseDay(nextDay, nextDiary.text);
    WorkDaily.snapshot(state, note);
    state.activePart().initializeRoutes(TEMPLATE);
    assertTrue(WorkDaily.selected(state.activePart()));
    var changes =
        VaultRepository.plan(state, DAILY, nextDiary, TEMPLATE, nextDate, nextDate.atTime(12, 0));
    assertEquals(
        List.of(nextWork, DAY),
        changes.stream()
            .filter(c -> !c.before().equals(c.after()))
            .map(VaultRepository.Change::path)
            .toList());
    assertTrue(changes.get(0).after().contains("Next existing event"));
    assertTrue(changes.get(0).after().contains("Move me"));
  }

  @Test
  public void workVisibilityHidesBothTheBranchAndItsPickerChoice() {
    var structure =
        new Markdown(TEMPLATE.text + "\n" + WorkDaily.ROOT + "\n" + WorkDaily.ROUTE + "\n");
    var hidden = new HiddenSections(DAILY, structure, Set.of(WorkDaily.ROOT));
    assertTrue(hidden.hidden(WorkDaily.ROUTE));
    var outline =
        WorkDaily.outline(
            DayOutline.sections(DAILY, TEMPLATE),
            new WorkDaily.Note(WORK, WORKDOC),
            DayContentRules.EMPTY);
    assertTrue(new DaySearch(hidden.outline(outline)).result("Работа").sections().isEmpty());
    var catalog = new Routing.Catalog(DAILY, TEMPLATE, true);
    assertFalse(
        hidden.offered(catalog.choices, c -> c.target().heading()).contains(WorkDaily.choice()));
    assertEquals(
        List.of(WorkDaily.choice()),
        catalog.choices.stream().filter(c -> FuzzySearch.matches(c.label(), "Работа")).toList());
  }
}
