package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class CalendarDaysTest {
  private static final DayContentRules RULES =
      new DayContentRules(List.of("PhotoFixture"), List.of("fixture-checklist-link"));

  private static Markdown note(String notes, String description) {
    return new Markdown(
        "## Заметки\n\n"
            + notes
            + "\n\n## Итог\n"
            + (description == null ? "" : "ShortDescr:: " + description + "\n"));
  }

  @Test
  public void emptyNotesAndFilledDescriptionAreCompleteButEitherMissingPartNeedsReview() {
    assertEquals(CalendarDays.State.COMPLETE, CalendarDays.assess(note("", "Filled"), RULES));
    assertEquals(CalendarDays.State.REVIEW, CalendarDays.assess(note("Pending", "Filled"), RULES));
    for (String value :
        Arrays.asList(
            null, "", "   ", " Заполни краткое описание!", "Заполни события! #crossPeople/Work"))
      assertEquals(
          String.valueOf(value),
          CalendarDays.State.REVIEW,
          CalendarDays.assess(note("", value), RULES));
    assertEquals(
        CalendarDays.State.COMPLETE,
        CalendarDays.assess(note("", "[[day#^id|Event]] ^summary"), RULES));
  }

  @Test
  public void notesUseTheSamePlaceholderEmptyTaskAndFieldRulesAsTheMainCounter() {
    String empty =
        "2026-11-19 12:00:00\n\nPhotoFixture\n\nfixture-checklist-link\n\n"
            + "- [ ] #task/diary/fact Routine\n\nField::\n\nPrompt:: Заполни значение!\n";
    assertEquals(CalendarDays.State.COMPLETE, CalendarDays.assess(note(empty, "Filled"), RULES));
    for (String value :
        List.of(
            "Text",
            "- [x] #task/diary/fact Routine ✅ 2026-11-19",
            "Field:: value",
            "```text\nliteral\n```",
            "`secret Zml4dHVyZQ==`"))
      assertEquals(
          value, CalendarDays.State.REVIEW, CalendarDays.assess(note(value, "Filled"), RULES));
  }

  @Test
  public void onlyNotesAndItsDescendantsArePendingNotUnrelatedSections() {
    var nested =
        new Markdown(
            "## Заметки\n### Вложенный\nPending\n\n## Работа\nDone\n\nShortDescr:: Filled\n");
    assertEquals(CalendarDays.State.REVIEW, CalendarDays.assess(nested, RULES));
    var outside = new Markdown("## Заметки\n\n## Работа\nDone\n\nShortDescr:: Filled\n");
    assertEquals(CalendarDays.State.COMPLETE, CalendarDays.assess(outside, RULES));
    assertEquals(
        CalendarDays.State.COMPLETE,
        CalendarDays.assess(new Markdown("## Работа\nDone\nShortDescr:: Filled\n"), RULES));
  }

  @Test
  public void scriptsRootMetadataAndHiddenHintsDoNotInventNotes() {
    String generated =
        "```dataviewjs\nawait fixture();\n```\n\n"
            + "[[day#^parent|parent]]\n"
            + Events.root("PhotoFixture ^root");
    assertEquals(
        CalendarDays.State.COMPLETE, CalendarDays.assess(note(generated, "Filled"), RULES));
  }

  @Test
  public void descriptionIgnoresYamlCodeAndSimilarKeysButSupportsLegacyInlineBrackets() {
    String fake =
        "---\nShortDescr:: fake\n## Заметки\nfake content\n---\n"
            + "## Заметки\n\n## Итог\n```text\nShortDescr:: fake\n```\nShortDescrWeek:: fake\n";
    assertEquals("", PastDays.description(fake));
    assertEquals(CalendarDays.State.REVIEW, CalendarDays.assess(new Markdown(fake), RULES));
    assertEquals(
        CalendarDays.State.COMPLETE,
        CalendarDays.assess(new Markdown(fake + "[ShortDescr:: Real]\n"), RULES));
    assertEquals(
        "Real", PastDays.description("\ufeff---\nShortDescr:: fake\n---\n[ShortDescr:: Real]\n"));
  }

  @Test
  public void tagsAndAnchorAloneAreNotAFilledSummaryAndDuplicateNotesDoNotCrash() {
    assertEquals(
        CalendarDays.State.REVIEW, CalendarDays.assess(note("", "#crossPeople/Work ^id"), RULES));
    var repeated =
        new Markdown("## Заметки\n\n## Заметки\nPending\n\n## Итог\nShortDescr:: Filled\n");
    assertEquals(CalendarDays.State.REVIEW, CalendarDays.assess(repeated, RULES));
    assertEquals(
        CalendarDays.State.COMPLETE, CalendarDays.assess(note("", "`secret Zml4dHVyZQ==`"), RULES));
  }

  private static final class Storage implements VaultStorage {
    final Map<String, String> files = new HashMap<>();
    final List<String> probes = new ArrayList<>(), reads = new ArrayList<>();
    IOException probeError, readError;
    Runnable afterProbe = () -> {}, afterRead = () -> {};

    public boolean exists(String path) throws IOException {
      probes.add(path);
      if (probeError != null) throw probeError;
      boolean exists = files.containsKey(path);
      afterProbe.run();
      return exists;
    }

    public String read(String path) throws IOException {
      reads.add(path);
      if (readError != null) throw readError;
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      String text = files.get(path);
      afterRead.run();
      return text;
    }

    public void write(String path, String text) {
      throw new AssertionError("Calendar is read-only");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Never enumerate the archive");
    }
  }

  @Test
  public void loadsOnlyShownMonthAndReadsContentsOnlyForExistingFiles() throws Exception {
    var storage = new Storage();
    var month = YearMonth.of(2026, 11);
    var complete = month.atDay(3);
    var pending = month.atDay(8);
    storage.files.put(DiaryLayout.path(complete), note("", "Filled").text);
    storage.files.put(DiaryLayout.path(pending), note("Pending", "Filled").text);
    var statuses = new ArrayList<CalendarDays.Day>();
    CalendarDays.load(storage, month, RULES, Set.of(), () -> false, statuses::add);
    assertEquals(30, statuses.size());
    assertEquals(30, storage.probes.size());
    assertEquals(List.of(DiaryLayout.path(complete), DiaryLayout.path(pending)), storage.reads);
    assertEquals(new CalendarDays.Day(complete, CalendarDays.State.COMPLETE), statuses.get(2));
    assertEquals(new CalendarDays.Day(pending, CalendarDays.State.REVIEW), statuses.get(7));
    assertEquals(CalendarDays.State.MISSING, statuses.get(0).state());
    assertEquals(DiaryLayout.path(month.atEndOfMonth()), storage.probes.get(29));
  }

  @Test
  public void cachedDatesAvoidAllProviderQueriesWhenReturningToAMonth() throws Exception {
    var storage = new Storage();
    var month = YearMonth.of(2028, 2);
    var known = new HashSet<LocalDate>();
    for (int i = 1; i <= month.lengthOfMonth(); i++) known.add(month.atDay(i));
    CalendarDays.load(
        storage, month, RULES, known, () -> false, ignored -> fail("Cached day emitted"));
    assertTrue(storage.probes.isEmpty());
    assertTrue(storage.reads.isEmpty());
    known.remove(month.atDay(29));
    CalendarDays.load(storage, month, RULES, known, () -> false, ignored -> {});
    assertEquals(List.of(DiaryLayout.path(month.atDay(29))), storage.probes);
  }

  @Test
  public void cancellationBeforeAfterMetadataAndAfterReadStopsWorkAndStaleResults()
      throws Exception {
    var month = YearMonth.of(2026, 11);
    var stopped = new AtomicBoolean(true);
    var storage = new Storage();
    CalendarDays.load(
        storage, month, RULES, Set.of(), stopped::get, ignored -> fail("Cancelled callback"));
    assertTrue(storage.probes.isEmpty());
    stopped.set(false);
    storage.files.put(DiaryLayout.path(month.atDay(1)), note("", "Filled").text);
    storage.afterProbe = () -> stopped.set(true);
    CalendarDays.load(
        storage, month, RULES, Set.of(), stopped::get, ignored -> fail("Cancelled callback"));
    assertEquals(1, storage.probes.size());
    assertTrue(storage.reads.isEmpty());
    stopped.set(false);
    storage.afterProbe = () -> {};
    storage.afterRead = () -> stopped.set(true);
    CalendarDays.load(
        storage, month, RULES, Set.of(), stopped::get, ignored -> fail("Cancelled callback"));
    assertEquals(1, storage.reads.size());
  }

  @Test
  public void deletionRaceIsMissingButAccessFailureNeverBecomesACompleteDay() throws Exception {
    var storage = new Storage();
    var month = YearMonth.of(2026, 11);
    storage.files.put(DiaryLayout.path(month.atDay(1)), note("", "Filled").text);
    storage.afterProbe = () -> storage.files.clear();
    var statuses = new ArrayList<CalendarDays.Day>();
    CalendarDays.load(storage, month, RULES, Set.of(), () -> false, statuses::add);
    assertEquals(CalendarDays.State.MISSING, statuses.get(0).state());
    storage.afterProbe = () -> {};
    storage.files.put(DiaryLayout.path(month.atDay(1)), note("", "Filled").text);
    storage.readError = new IOException("No access");
    assertSame(
        storage.readError,
        assertThrows(
            IOException.class,
            () ->
                CalendarDays.load(
                    storage,
                    month,
                    RULES,
                    Set.of(),
                    () -> false,
                    ignored -> fail("Failed day emitted"))));
  }

  @Test
  public void leapYearsAndYearBoundariesHaveExactlyTheirOwnDates() throws Exception {
    for (var month :
        List.of(
            YearMonth.of(1900, 2),
            YearMonth.of(2000, 2),
            YearMonth.of(2026, 12),
            YearMonth.of(2027, 1))) {
      var storage = new Storage();
      var days = new ArrayList<CalendarDays.Day>();
      CalendarDays.load(storage, month, RULES, Set.of(), () -> false, days::add);
      assertEquals(month.lengthOfMonth(), days.size());
      assertEquals(month.atDay(1), days.get(0).date());
      assertEquals(month.atEndOfMonth(), days.get(days.size() - 1).date());
      assertEquals(
          days.size(), new HashSet<>(days.stream().map(CalendarDays.Day::date).toList()).size());
    }
  }
}
