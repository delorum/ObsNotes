package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class SectionUsageTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
  private static final LocalDateTime NOW = DAY.atTime(12, 0);
  private static final String PATH = DiaryLayout.path(DAY);
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Alpha\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"A,AliasAlpha\"});\n"
              + "```\n"
              + "## Beta\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"B\"});\n"
              + "```\n"
              + "## Мама\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Мама\"});\n"
              + "```\n");

  private SectionUsage empty() {
    return new SectionUsage(Map.of());
  }

  private SectionUsage.Use primary(String h) {
    return new SectionUsage.Use(h, SectionUsage.Role.PRIMARY);
  }

  private SectionUsage.Use extra(String h) {
    return new SectionUsage.Use(h, SectionUsage.Role.ADDITIONAL);
  }

  private List<VaultRepository.Change> changes(EditorState state) {
    return VaultRepository.plan(
        state,
        new Markdown(state.sourceSnapshot),
        new Markdown(state.destinationSnapshot),
        TEMPLATE,
        DAY,
        NOW);
  }

  private List<SectionUsage.Use> uses(EditorState state) {
    return SectionUsage.planned(state, TEMPLATE, changes(state));
  }

  private EditorState edit(String text) {
    var doc = new Markdown("## Заметки\n\n" + text + "\n\n## Alpha\n\n## Beta\n");
    return EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
  }

  private void route(EditorState state, String... headings) {
    var catalog = state.routingCatalog(TEMPLATE);
    state.applyRoutes(
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

  @Test
  public void mostUsedFirstWithStableTiesAllItemsAndUnchangedSelectionOrder() {
    var stats = empty().record(List.of(primary("Beta"), extra("Beta"), extra("Alpha")));
    var items = List.of("Unseen", "Alpha", "Beta", "Other");
    assertEquals(List.of("Beta", "Alpha", "Unseen", "Other"), stats.ranked(items, s -> s));
    assertEquals(List.of("Unseen", "Alpha", "Beta", "Other"), items);
    var selection = new PickerSelection<>(List.of("Other", "Alpha"));
    stats.ranked(selection.selected(), s -> s);
    assertEquals(List.of("Other", "Alpha"), selection.selected());
    assertTrue(empty().snapshot().isEmpty());
  }

  @Test
  public void exactAndLiteralSearchStillBeatMoreFrequentFuzzyMatches() {
    var stats = empty().record(List.of(primary("aXbYc"), extra("aXbYc"), extra("abc suffix")));
    var ranked = stats.ranked(List.of("ABC", "abc suffix", "aXbYc", "z"), s -> s);
    assertEquals(List.of("ABC", "abc suffix", "aXbYc"), FuzzySearch.ranked(ranked, "abc", s -> s));
    assertEquals(ranked, FuzzySearch.ranked(ranked, "", s -> s));
    assertEquals(4, ranked.size());
  }

  @Test
  public void hotSectionsPrecedeColdSectionsWhileTasksAndFieldsRemainAvailableAndSearchable() {
    var doc = new Markdown("## Заметки\n\n## Alpha\nField:: value\n\n- [ ] task\n\n## Beta\n");
    var catalog = new Routing.Catalog(doc, TEMPLATE);
    var stats = empty().record(List.of(extra("## Alpha")));
    var choices = stats.rankedRoutes(catalog.choices);
    assertEquals("## Alpha", choices.get(0).target().heading());
    assertEquals(catalog.choices.size(), choices.size());
    boolean seenValue = false;
    for (var choice : choices) {
      if (choice.target().kind() != Markdown.Kind.SECTION) seenValue = true;
      else assertFalse("Task/field values must not split the section list", seenValue);
    }
    assertTrue(
        FuzzySearch.ranked(choices, "field", catalog::searchLabel).stream()
            .anyMatch(c -> c.target().kind() == Markdown.Kind.FIELD));
    assertTrue(
        FuzzySearch.ranked(choices, "task", catalog::searchLabel).stream()
            .anyMatch(c -> c.target().kind() == Markdown.Kind.TASK));
  }

  @Test
  public void savedMoveCountsPrimaryAndOnlyNewExtraDestinations() {
    var state = edit("text #crossPeople/A");
    route(state, "## Beta", "## Alpha", "## Мама");
    assertEquals(List.of(primary("## Beta"), extra("## Мама")), uses(state));
    var stats = empty().record(uses(state));
    assertEquals(new SectionUsage.Count(1, 0), stats.count("## Beta"));
    assertEquals(new SectionUsage.Count(0, 0), stats.count("## Alpha"));
    assertEquals(new SectionUsage.Count(0, 1), stats.count("## Мама"));
  }

  @Test
  public void extraAliasesWholeBlockLineAndPersonTagsCountEachSectionOnce() {
    var state = edit("first\nsecond");
    state.addTags(0, List.of("AliasAlpha"), false, false);
    state.addTags(0, List.of("A"), true, false);
    state.addTags(state.editableText().length(), List.of("Мама"), true, true);
    assertEquals(List.of(extra("## Alpha"), extra("## Мама")), uses(state));
    assertEquals(2, empty().record(uses(state)).snapshot().size());
  }

  @Test
  public void editingExistingTextReselectingTheSameRoutesAndRemovingTagsDoNotIncreaseUsage() {
    var state = edit("text #crossPeople/A");
    state.edit("text changed");
    assertTrue(uses(state).isEmpty());
    route(state, "## Заметки", "## Alpha");
    assertTrue(uses(state).isEmpty());
    state.removeTag(0);
    assertTrue(uses(state).isEmpty());
  }

  @Test
  public void emptyNoOpDeletedAndWeatherFormsDoNotLearnAndCanceledPlansCanBeIgnored() {
    var doc = new Markdown("## Заметки\n\n## Alpha\n");
    var state = EditorState.newNote(PATH, doc, TEMPLATE, "## Alpha");
    assertTrue(uses(state).isEmpty());
    state.edit("text");
    var stats = empty();
    var planned = uses(state);
    assertEquals(List.of(primary("## Alpha")), planned);
    assertTrue(stats.snapshot().isEmpty()); // Planning/dialog selection is not a saved event.
    assertTrue(SectionUsage.planned(state, TEMPLATE, List.of()).isEmpty());
    assertTrue(
        SectionUsage.planned(
                state, TEMPLATE, List.of(new VaultRepository.Change(PATH, "same", "same")))
            .isEmpty());
    var deleted = edit("text");
    deleted.edit("");
    assertTrue(uses(deleted).isEmpty());
    var weather =
        EditorState.weather(PATH, new Markdown("## Погода\nПогода:: ясно\nТемпература:: +1\n"));
    weather.editField(0, "облачно");
    assertTrue(
        SectionUsage.planned(weather, TEMPLATE, List.of(new VaultRepository.Change(PATH, "a", "b")))
            .isEmpty());
  }

  @Test
  public void directCreationLearnsButDefaultNotesCaptureDoesNotDominateThePicker() {
    var doc = new Markdown("## Заметки\n\n## Alpha\n");
    var direct = EditorState.newNote(PATH, doc, TEMPLATE, "## Alpha");
    direct.edit("direct");
    assertEquals(List.of(primary("## Alpha")), uses(direct));
    var capture = EditorState.newNote(PATH, doc, TEMPLATE, "## Заметки");
    capture.edit("capture");
    assertTrue(uses(capture).isEmpty());
    route(capture, "## Заметки");
    assertEquals(List.of(primary("## Заметки")), uses(capture));
  }

  @Test
  public void movesToPastDaysAndFieldOrTaskDestinationsUseTheSectionIdentity() {
    var state = edit("text");
    state.chooseDay(DiaryLayout.path(DAY.minusDays(1)), "## Заметки\n");
    state.initializeRoutes(TEMPLATE);
    assertEquals(List.of(primary("## Заметки")), uses(state));
    var doc = new Markdown("## Заметки\n\ntext\n\n## Alpha\nField:: old\n\n- [ ] action\n");
    for (var target : doc.targets(TEMPLATE)) {
      if (target.kind() == Markdown.Kind.SECTION) continue;
      var form = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
      form.chooseTarget(target);
      assertEquals(List.of(primary("## Alpha")), uses(form));
    }
  }

  @Test
  public void literalUnknownAndSelfTagsAreNotDestinationsAndRolePromotionIsCounted() {
    var state = edit("text\n```text\n#crossPeople/A\n```\n#crossPeople/Unknown");
    assertTrue(uses(state).isEmpty());
    var doc = new Markdown("## Alpha\n\ntext #crossPeople/A\n\n## Beta\n");
    var self = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    self.edit(self.editableText() + " changed");
    assertTrue(uses(self).isEmpty());
    route(self, "## Beta", "## Alpha");
    assertEquals(List.of(primary("## Beta"), extra("## Alpha")), uses(self));
  }

  @Test
  public void negativeCountersAndOverflowCannotInvertRankAndSnapshotsAreImmutable() {
    var stats =
        new SectionUsage(
            Map.of(
                "A",
                new SectionUsage.Count(Long.MAX_VALUE, Long.MAX_VALUE),
                "B",
                new SectionUsage.Count(-3, -10)));
    stats = stats.record(List.of(primary("A"), primary("A"), extra("A")));
    assertEquals(Long.MAX_VALUE, stats.count("A").total());
    assertEquals(0, stats.count("B").total());
    assertEquals(List.of("A", "B"), stats.ranked(List.of("B", "A"), s -> s));
    var snapshot = stats.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
  }
}
