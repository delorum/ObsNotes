package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class ValuePartsTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE),
      PAST = DiaryLayout.path(DATE.minusDays(1));
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n## Поля\nMetric::\nOther::\n\n"
              + section("## Работа", "Work")
              + section("## Дом", "Home")
              + "## Факты\n- [ ] #task/diary/fact Routine\n");

  private static String section(String heading, String id) {
    return heading
        + "\n```dataviewjs\nawait dv.view(\"Скрипты/Dataview/crossPeople\", {person: \""
        + id
        + "\"});\n```\n\n";
  }

  private static Markdown doc(String values) {
    return new Markdown(
        "---\r\nkey: keep\r\n---\r\n\r\n## Заметки\r\n\r\nKeep\r\n\r\n"
            + "## Поля\r\n\r\n"
            + values.replace("\n", "\r\n")
            + "\r\n\r\n## Работа\r\n\r\n## Дом\r\n\r\n"
            + "## Факты\r\n\r\n- [ ] #task/diary/fact Routine ^task-id\r\n");
  }

  private static EditorState field(Markdown doc) {
    var block =
        doc.blocks.stream()
            .filter(b -> b.section().heading().equals("## Поля"))
            .findFirst()
            .orElseThrow();
    var s = EditorState.editing(PATH, doc, TEMPLATE, block);
    s.enableParts(TEMPLATE);
    return s;
  }

  private static EditorState task(Markdown doc) {
    var s = EditorState.appendTask(PATH, doc, TEMPLATE, DiaryTasks.in(doc.text, 0).get(0));
    s.enableParts(TEMPLATE);
    return s;
  }

  private static String saved(EditorState s, Markdown doc) {
    var changes = VaultRepository.plan(s, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
    return changes.isEmpty() ? doc.text : changes.get(0).after();
  }

  private static void route(EditorState s, int part, String heading) {
    var f = s.parts.parts.get(part).form();
    var catalog = f.routingCatalog(TEMPLATE);
    var choice =
        catalog.choices.stream()
            .filter(
                c ->
                    c.target().kind() == Markdown.Kind.SECTION
                        && c.target().heading().equals(heading))
            .findFirst()
            .orElseThrow();
    f.applyRoutes(List.of(choice), catalog);
  }

  @Test
  public void fieldMultilineStaysVisibleAndOnlyItsSavedValueCollapses() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    assertEquals("old\n\nkeep", s.editableText());
    assertEquals(s.editableText().length(), s.cursor);
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    s.edit("one\ntwo\n\nkeep");
    assertEquals(2, s.parts.parts.size());
    assertEquals("one\ntwo\n\nkeep", s.editableText());
    String after = saved(s, doc);
    assertEquals(doc.text.replace("Metric:: old", "Metric:: one; two"), after);
    assertEquals("Metric", s.parts.parts.get(0).form().protectedFieldName());
    s.edit("old\n\nkeep");
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    assertEquals(doc.text, saved(s, doc));
  }

  @Test
  public void fieldCanCreateMultipleIndependentPartsWithTagsAndAChainToItsOwnRoot() {
    var doc = doc("Metric:: old");
    var s = field(doc);
    s.edit("one\ntwo\n\nSecond\n\nThird");
    assertEquals(3, s.parts.parts.size());
    assertEquals("", s.parts.parts.get(1).form().fieldPrefix);
    assertEquals("## Заметки", s.parts.parts.get(1).form().heading);
    route(s, 1, "## Работа");
    route(s, 2, "## Дом");
    s.parts.parts.get(1).form().addTags(0, List.of("Home"), false, false);
    s.parts.addInternal(1, 0, null);
    s.parts.addInternal(2, 1, null);
    String after = saved(s, doc);
    assertTrue(after.contains("Metric:: one; two ^event-"));
    var result = new Markdown(after);
    var metric =
        result.blocks.stream()
            .filter(b -> b.text().startsWith("Metric::"))
            .findFirst()
            .orElseThrow();
    var second =
        result.blocks.stream().filter(b -> b.text().contains("Second")).findFirst().orElseThrow();
    var third =
        result.blocks.stream().filter(b -> b.text().contains("Third")).findFirst().orElseThrow();
    assertEquals(
        Events.rootId(metric.text()), Events.parent(PATH, second.text()).orElseThrow().id());
    assertEquals(
        Events.rootId(second.text()), Events.parent(PATH, third.text()).orElseThrow().id());
    assertTrue(second.text().contains("#crossPeople/Home"));
    assertTrue(doc.text.contains("Metric:: old"));
  }

  @Test
  public void emptyTaskAdditionKeepsTaskUnchangedWhileNewPartsGoToTheirOwnSections() {
    var doc = doc("Metric:: old");
    var s = task(doc);
    s.edit("\n\nSide\n\nTail");
    assertEquals(3, s.parts.parts.size());
    assertTrue(s.parts.parts.get(0).form().taskAppend);
    assertEquals("", s.parts.parts.get(0).form().editableText());
    route(s, 1, "## Работа");
    route(s, 2, "## Дом");
    s.parts.addInternal(2, 1, null);
    String after = saved(s, doc);
    assertTrue(after.contains("- [ ] #task/diary/fact Routine ^task-id"));
    assertTrue(after.contains("Side ^event-"));
    assertTrue(after.contains("Tail"));
    assertFalse(after.contains("Routine; Side"));
  }

  @Test
  public void taskMultilineAppendsOneValueWithNoteDateAndAdditionalPartsRemainSeparate() {
    var doc = doc("Metric:: old");
    var s = task(doc);
    s.edit("one\r\ntwo\n\nSide");
    assertEquals("one\ntwo\n\nSide", s.editableText());
    assertEquals(2, s.parts.parts.size());
    assertFalse(s.parts.parts.get(1).form().taskAppend);
    route(s, 1, "## Работа");
    String after = saved(s, doc);
    assertTrue(after.contains("Routine; one; two ✅ 2001-02-03 ^task-id"));
    assertTrue(
        new Markdown(after)
            .blocks.stream()
                .anyMatch(
                    b -> b.section().heading().equals("## Работа") && b.text().equals("Side")));
  }

  @Test
  public void emptyFieldsRetainTheirKeysAndAcceptTypingAtEitherValue() {
    var doc = doc("Metric:: \nOther:: ");
    var s = field(doc);
    assertEquals(2, s.parts.parts.size());
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    s.edit("\n\nsecond");
    assertEquals(2, s.parts.parts.size());
    assertEquals("Metric", s.parts.parts.get(0).form().protectedFieldName());
    assertEquals("Other", s.parts.parts.get(1).form().protectedFieldName());
    assertEquals(doc.text.replace("Other:: ", "Other:: second"), saved(s, doc));
    s = field(doc);
    s.edit("first\n\n");
    assertEquals(doc.text.replace("Metric:: ", "Metric:: first"), saved(s, doc));
  }

  @Test
  public void clearingAllValuesPreservesKeyOrderAndProtectedSeparators() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    s.edit("");
    s.parts.sync();
    assertEquals(2, s.parts.parts.size());
    assertEquals("\n\n", s.editableText());
    assertEquals(
        List.of("Metric", "Other"),
        s.parts.parts.stream().map(p -> p.form().protectedFieldName()).toList());
    assertEquals(
        doc.text.replace("Metric:: old\r\nOther:: keep", "Metric:: \r\nOther:: "), saved(s, doc));
  }

  @Test
  public void multilinePartCanTargetADataviewKeyAndKeepsLaterIndependentParts() {
    var doc = doc("Metric:: old");
    var s = EditorState.newNote(PATH, doc, TEMPLATE, "## Заметки");
    s.enableParts(TEMPLATE);
    s.edit("one\ntwo\n\nSide");
    var f = s.parts.parts.get(0).form();
    var catalog = f.routingCatalog(TEMPLATE);
    f.applyRoutes(
        List.of(
            catalog.choices.stream()
                .filter(
                    c ->
                        c.target().kind() == Markdown.Kind.FIELD
                            && c.target().source().startsWith("Metric::"))
                .findFirst()
                .orElseThrow()),
        catalog);
    route(s, 1, "## Работа");
    String after = saved(s, doc);
    assertTrue(after.contains("Metric:: old; one; two"));
    assertFalse(after.contains("Metric:: old; one; two; Side"));
  }

  @Test
  public void distinctProtectedKeysCannotAccidentallyMergeButASplitCanBeUndone() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    s.edit("old\nkeep");
    assertFalse(s.parts.error.isEmpty());
    s.edit("old\n\nkeep");
    assertEquals("", s.parts.error);
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    s = field(doc("Metric:: old"));
    s.edit("old\n\nside");
    s.edit("old\nside");
    assertEquals("", s.parts.error);
    assertEquals("Metric:: old; side", s.parts.parts.get(0).form().savedText());
  }

  @Test
  public void existingProtectedFieldsDoNotCountAsNewPrimaryAssignments() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    s.edit("new\nvalue\n\nkeep");
    var changes = VaultRepository.plan(s, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
    assertTrue(SectionUsage.planned(s, TEMPLATE, changes).isEmpty());
  }

  @Test
  public void weatherAppendUpdatesSharedValuesWithoutTouchingAnotherPart() {
    var doc = doc("Погода:: manual\nТемпература:: +5");
    var s = field(doc);
    s.edit("manual\n\n+5\n\nSide");
    s.addWeather(new WeatherSummary("auto", "+7"));
    assertEquals("manual; auto\n\n+5; +7\n\nSide", s.editableText());
    var after = saved(s, doc);
    assertTrue(after.contains("Погода:: manual; auto"));
    assertTrue(after.contains("Температура:: +5; +7"));
    assertTrue(after.contains("Side"));
  }

  @Test
  public void fieldGroupMovesToAnExistingDayWithDestinationFirstAndKeepsAllParts() {
    var doc = doc("Metric:: old");
    var destination = new Markdown("## Заметки\n\nPastKeep\n\n## Поля\n");
    var s = field(doc);
    s.edit("one\ntwo\n\nSide");
    s.chooseDay(PAST, destination.text);
    for (var part : s.parts.parts) part.form().initializeRoutes(TEMPLATE);
    var changes =
        VaultRepository.plan(s, doc, destination, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0));
    assertEquals(List.of(PAST, PATH), changes.stream().map(VaultRepository.Change::path).toList());
    assertTrue(changes.get(0).after().contains("Metric:: one; two"));
    assertTrue(changes.get(0).after().contains("Side"));
    assertTrue(changes.get(0).after().contains("PastKeep"));
    assertFalse(changes.get(1).after().contains("Metric:: old"));
  }

  @Test
  public void taskSplitThenRestoredToEmptyIsANoOpAndKeepsTheTaskUnchecked() {
    var doc = doc("Metric:: old");
    var s = task(doc);
    s.edit("done\nmore\n\nSide");
    s.edit("");
    s.parts.sync();
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    assertEquals(doc.text, saved(s, doc));
  }

  @Test
  public void splittingAfterChoosingAFieldOrTaskCreatesAnOrdinaryIndependentPart() {
    var doc = doc("Metric:: old");
    for (var kind : List.of(Markdown.Kind.FIELD, Markdown.Kind.TASK)) {
      var s = EditorState.newNote(PATH, doc, TEMPLATE, "## Заметки");
      s.enableParts(TEMPLATE);
      s.edit("one");
      var first = s.parts.parts.get(0).form();
      var catalog = first.routingCatalog(TEMPLATE);
      first.applyRoutes(
          List.of(
              catalog.choices.stream()
                  .filter(c -> c.target().kind() == kind)
                  .findFirst()
                  .orElseThrow()),
          catalog);
      s.edit("one\ntwo\n\nSide");
      assertEquals(kind, s.parts.parts.get(0).form().kind);
      assertEquals(Markdown.Kind.SECTION, s.parts.parts.get(1).form().kind);
      assertEquals("## Заметки", s.parts.parts.get(1).form().heading);
      var after = saved(s, doc);
      assertTrue(after.contains("one; two"));
      assertTrue(after.contains("Side"));
      assertFalse(after.contains("one; two; Side"));
    }
  }

  @Test
  public void replacingEveryValueAtOnceKeepsProtectedSlotsInTheirOriginalOrder() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    s.edit("first\nline\n\nsecond");
    s.parts.sync();
    assertEquals(2, s.parts.parts.size());
    assertEquals(
        List.of("Metric", "Other"),
        s.parts.parts.stream().map(p -> p.form().protectedFieldName()).toList());
    assertEquals(
        doc.text.replace("Metric:: old\r\nOther:: keep", "Metric:: first; line\r\nOther:: second"),
        saved(s, doc));
    s = field(doc);
    s.edit("replacement");
    s.parts.sync();
    assertEquals("replacement\n\n", s.editableText());
    assertEquals(
        doc.text.replace("Metric:: old\r\nOther:: keep", "Metric:: replacement\r\nOther:: "),
        saved(s, doc));
  }

  @Test
  public void duplicateKeyNamesRetainDistinctProtectedValueSlots() {
    var doc = doc("Metric:: old\nMetric:: keep");
    var s = field(doc);
    s.editField(1, "second\nline");
    assertEquals("old", s.fieldValue(0));
    assertEquals("second\nline", s.fieldValue(1));
    assertEquals(doc.text.replace("Metric:: keep", "Metric:: second; line"), saved(s, doc));
    s.edit("old\nsecond\nline");
    assertFalse(s.parts.error.isEmpty());
  }

  @Test
  public void typingAndDeletingAcrossProtectedSlotsNeverLosesKeysOrBreaksSaving() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    var random = new Random(20261119);
    for (int i = 0; i < 80; i++) {
      String value = s.editableText();
      int a = random.nextInt(value.length() + 1);
      int b = a + random.nextInt(value.length() - a + 1);
      String insert = List.of("x", "yz", "\n", "\n\n", "").get(random.nextInt(5));
      s.edit(value.substring(0, a) + insert + value.substring(b));
      s.parts.sync();
      if (!s.parts.error.isEmpty()) continue;
      String after = saved(s, doc);
      assertEquals("Metric at step " + i, 1, after.split("Metric::", -1).length - 1);
      assertEquals("Other at step " + i, 1, after.split("Other::", -1).length - 1);
      assertTrue(after.contains("key: keep"));
      assertTrue(after.contains("#task/diary/fact Routine ^task-id"));
    }
  }

  @Test
  public void conflictCopyKeepsProtectedKeyLabelsAndAllNewParagraphsWithoutActiveFields() {
    var doc = doc("Metric:: old\nOther:: keep");
    var s = field(doc);
    s.edit("one\nline\n\nkeep\n\nSide");
    s.parts.sync();
    String copy = s.conflictCopyText();
    assertEquals("Metric: one\nline\n\nOther: keep\n\nSide", copy);
    assertFalse(copy.contains("::"));
    assertTrue(s.parts.parts.get(0).form().savedText().startsWith("Metric:: one; line"));
  }
}
