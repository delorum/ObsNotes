package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class DayContentRulesTest {
  private static final DayContentRules RULES =
      new DayContentRules(List.of("*Подсказка*", "[[Служебное]]"), List.of("#Чеклист|В чеклист]]"));
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Родитель\n"
              + "### Дела\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Tasks\"});\n"
              + "```\n");

  private DayOutline.Section section(Markdown doc, String heading) {
    var catalog = new Routing.Catalog(doc, TEMPLATE);
    return DayOutline.search(
            DayOutline.sections(doc, TEMPLATE, RULES), catalog, heading.replaceFirst("^#+ ", ""))
        .stream()
        .filter(s -> s.heading().equals(heading))
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void readsJsLiteralListsIncludingEscapesCommentsCommasAndUrlsWithoutExecution() {
    String script =
        "// const excludeLines = ['wrong'];\n"
            + "/* const excludeContains = ['wrong']; */\n"
            + "const text = \"const excludeLines = ['fake']\";\n"
            + "const excludeContains = [/* hi */ 'needle',];\n"
            + "const excludeLines = ['[link](https://example.test/a]b)', 'it\\'s', \"quote\\\"\","
            + " '\\u0410\\x42', ];\n"
            + "throw new Error('Do not execute');";
    var rules = DayContentRules.parse(script);
    assertEquals(
        Set.of("[link](https://example.test/a]b)", "it's", "quote\"", "АB"), rules.excludedLines());
    assertTrue(rules.hidden("prefix needle suffix"));
    assertFalse(rules.hidden("wrong"));
  }

  @Test
  public void unsupportedDynamicOrMalformedListsFailRatherThanExecuteCodeOrInventFilters() {
    for (String script :
        List.of(
            "const excludeLines = build();",
            "const excludeLines = ['x' + 'y'];",
            "const excludeLines = ['unfinished];",
            "const excludeLines = ['x'];"))
      assertThrows(IllegalArgumentException.class, () -> DayContentRules.parse(script));
  }

  private static class Storage implements VaultStorage {
    String script;
    List<String> reads = new ArrayList<>();

    public String read(String path) throws IOException {
      reads.add(path);
      if (script == null) throw new FileNotFoundException(path);
      return script;
    }

    public void write(String path, String value) {
      throw new AssertionError("Display rules never write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Rules never enumerate the vault");
    }
  }

  @Test
  public void loadsExactlyTheJsModuleAndMissingFileKeepsEmptyInputRules() throws Exception {
    var storage = new Storage();
    assertSame(DayContentRules.EMPTY, DayContentRules.load(storage));
    storage.script = "const excludeLines = ['one']; const excludeContains = [];";
    assertTrue(DayContentRules.load(storage).hidden("one"));
    storage.script = "const excludeLines = ['two']; const excludeContains = [];";
    assertFalse(DayContentRules.load(storage).hidden("one"));
    assertEquals(Collections.nCopies(3, DayContentRules.PATH), storage.reads);
    storage.script = "invalid";
    assertThrows(IOException.class, () -> DayContentRules.load(storage));
  }

  @Test
  public void excludedOnlyBlocksDisappearAndMixedBlocksKeepOriginalOffsetsAndTextForEditing() {
    var doc =
        new Markdown(
            "## Заметки\r\n\r\n"
                + "*Подсказка*\r\n\r\n"
                + "*Подсказка*\r\n"
                + "важный текст\r\n\r\n"
                + "[[x#Чеклист|В чеклист]]\r\n");
    var section = section(doc, "## Заметки");
    assertEquals(1, section.items().size());
    assertEquals(1, section.count());
    var item = section.items().get(0);
    assertEquals("важный текст", item.view().text());
    assertSame(doc.blocks.get(1), item.original());
    assertTrue(item.original().text().startsWith("*Подсказка*\r\n"));
    assertEquals(
        "*Подсказка*\r\nважный текст",
        EditorState.editing("day.md", doc, TEMPLATE, item.original()).text);
    assertEquals(
        item.original().start() + "*Подсказка*\r\n".length(),
        item.original().start() + item.view().lines().get(0).start());
  }

  @Test
  public void uncheckedDiaryTasksAndCheckedNegativeFactsStayVisibleWithoutCounting() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "- [ ] #task/dairy/fact one\n\n"
                + "- [ ] #task/diary/fact two\n\n"
                + "- [x] #task/dairy/car Не ездил на машине ✅ 2026-11-16\n\n"
                + "- [X] #task/diary/diet не соблюдал диету ✅ 2026-11-16\n");
    var section = section(doc, "## Заметки");
    assertEquals(4, section.items().size());
    assertEquals(0, section.count());
    var task = DiaryTasks.in(doc.text, 0).get(0);
    var checked = DiaryTasks.toggle(doc, task, LocalDate.of(2026, 11, 16));
    assertEquals(1, section(checked, "## Заметки").count());
    assertEquals(
        0,
        section(
                DiaryTasks.toggle(
                    checked, DiaryTasks.in(checked.text, 0).get(0), LocalDate.of(2026, 11, 16)),
                "## Заметки")
            .count());
  }

  @Test
  public void emptyKeysStayEditableAndOnlyFilledValuesIncreaseTheBlockCount() {
    var doc =
        new Markdown("## Заметки\n\nВес::   \n\nТалия:: ^stable\n\nКалории:: 0\n\nПульс:: 70\n");
    var section = section(doc, "## Заметки");
    assertEquals(4, section.items().size());
    assertEquals(2, section.count());
    var first = section.items().get(0);
    var editor = EditorState.editing("day.md", doc, TEMPLATE, first.original());
    editor.editField(0, "90");
    var after =
        VaultRepository.plan(editor, doc, doc, TEMPLATE, LocalDate.now(), LocalDateTime.now())
            .get(0)
            .after();
    assertEquals(3, section(new Markdown(after), "## Заметки").count());
    assertTrue(after.contains("Талия:: ^stable"));
  }

  @Test
  public void jsKeyExceptionsForBlankValuesRemainButTemplatePromptsAreEmpty() {
    for (String key : List.of("ФотоПогоды", "ShortDescr", "ShortDescrWeek"))
      assertTrue(DayContentRules.EMPTY.countable(key + "::"));
    for (String key : List.of("Вес", "ShortDescrMonth", "ShortDescrYear"))
      assertFalse(DayContentRules.EMPTY.countable(key + "::"));
    assertFalse(DayContentRules.EMPTY.countable("Погода:: Заполни тестовое значение!"));
    assertTrue(DayContentRules.EMPTY.countable("- [ ] #task/other action"));
    assertTrue(
        DayContentRules.EMPTY.countable("- [x] #task/dairy/car Ездил на машине ✅ 2026-11-16"));
  }

  @Test
  public void mixedTaskAndFieldBlockCountsOnceOnlyAfterAnyLineHasContent() {
    var doc = new Markdown("## Заметки\n\n*Подсказка*\n- [ ] #task/dairy/fact one\nВес::\n");
    assertEquals(1, section(doc, "## Заметки").items().size());
    assertEquals(0, section(doc, "## Заметки").count());
    assertEquals(1, section(new Markdown(doc.text + "полезная строка\n"), "## Заметки").count());
  }

  @Test
  public void nestedAndSearchCountersAreConsistentWhileEmptyInputsRemainVisible() {
    var doc =
        new Markdown(
            "## Родитель\n\n"
                + "*Подсказка*\n\n"
                + "### Дела\n\n"
                + "- [ ] #task/dairy/fact one\n\n"
                + "Вес::\n\n"
                + "событие\n");
    var roots = DayOutline.sections(doc, TEMPLATE, RULES);
    var parent =
        roots.stream().filter(s -> s.heading().equals("## Родитель")).findFirst().orElseThrow();
    assertEquals(1, parent.count());
    assertEquals(3, parent.children().get(0).items().size());
    assertEquals(1, section(doc, "## Родитель").count());
    assertEquals(1, section(doc, "## Родитель").children().size());
  }

  @Test
  public void projectionsUseTheirVisibleLinesForCountingAndStillPointAtTheOriginalBlock() {
    var doc =
        new Markdown(
            "## Заметки\n\n- [ ] #task/dairy/fact one #crossOneLine/Tasks\nсобытие\n\n"
                + "*Подсказка* #crossPeople/Tasks\n\n## Родитель\n### Дела\n");
    var source = section(doc, "## Заметки");
    var target = section(doc, "### Дела");
    assertEquals(1, source.count());
    assertEquals(0, target.count());
    assertEquals(1, target.items().size());
    assertTrue(target.items().get(0).projection());
    assertSame(doc.blocks.get(0), target.items().get(0).original());
  }

  @Test
  public void
      fencedExamplesAndDuplicateTaskPositionsSurviveFilteringAndGeneratedLinksDoNotAddCounts() {
    var doc =
        new Markdown(
            "## Заметки\r\n\r\n"
                + "*Подсказка*\r\n"
                + "- [ ] #task/dairy/fact same\r\n"
                + "- [ ] #task/dairy/fact same\r\n\r\n"
                + "```text\r\n"
                + "*Подсказка*\r\n"
                + "Вес::\r\n"
                + "```\r\n");
    var section = section(doc, "## Заметки");
    assertEquals(2, section.items().size());
    assertEquals(1, section.count());
    var item = section.items().get(0);
    var tasks = DiaryTasks.in(item.original().text(), item.original().start());
    assertEquals(2, tasks.size());
    for (int i = 0; i < 2; i++)
      assertEquals(
          tasks.get(i).start(), item.original().start() + item.view().lines().get(i).start());
    assertTrue(section.items().get(1).view().text().contains("*Подсказка*"));
    String linked =
        "[[other#^root|root]]\n"
            + "- [ ] #task/dairy/fact one ^leaf\n"
            + "> [!info]- Дополнения к событию\n"
            + "> ```dataviewjs\n"
            + "> script();\n"
            + "> ```";
    assertFalse(RULES.view(linked).counted());
  }

  @Test
  public void nativeChecklistNotesCounterSkipsTheSameHiddenAndUnfilledLines() {
    var doc =
        new Markdown(
            "## Заметки\n\n*Подсказка*\n\nВес::\n\n- [ ] #task/dairy/fact one\n\nсобытие\n");
    var context = new DailyChecklist.Context(1, 2L, false, null, null);
    var row =
        DailyChecklist.rows(doc, context, RULES).stream()
            .filter(r -> r.label().equals("Заметки"))
            .findFirst()
            .orElseThrow();
    assertEquals("1", row.status().get(0).text());
  }

  @Test
  public void promptValuesAreEmptyForAllKeysIncludingSummaryExceptionsAndRemainVisible() {
    for (String key :
        List.of(
            "Погода",
            "Температура",
            "Местоположение",
            "ShortDescr",
            "ShortDescrWeek",
            "ShortDescrMonth",
            "ShortDescrYear",
            "ФотоПогоды")) {
      String line = key + ":: \tЗаполни тестовое значение! #crossPeople/Tasks ^stable";
      assertTrue(key, DayContentRules.emptyDataviewKey(line));
      var view = RULES.view(line);
      assertFalse(key, view.counted());
      assertEquals(line, view.raw());
      assertEquals(1, view.lines().size());
    }
  }

  @Test
  public void groupedPromptsCountOnceOnlyWhenAnActualValueIsEnteredWithoutRewritingOtherFields() {
    var doc =
        new Markdown(
            "## Заметки\r\n\r\n"
                + "Погода:: Заполни тестовое значение!\r\n"
                + "ShortDescr:: Заполни тестовое описание!\r\n\r\n"
                + "## Прочее\r\n"
                + "Tail\r\n");
    var before = section(doc, "## Заметки");
    assertEquals(1, before.items().size());
    assertEquals(0, before.count());
    var editor = EditorState.editing("day.md", doc, TEMPLATE, before.items().get(0).original());
    assertEquals("Заполни тестовое значение!", editor.fieldValue(0));
    editor.editField(0, "ясно");
    var after =
        VaultRepository.plan(
                editor,
                doc,
                doc,
                TEMPLATE,
                LocalDate.of(2026, 11, 18),
                LocalDateTime.of(2026, 11, 18, 12, 0))
            .get(0)
            .after();
    assertEquals(doc.text.replace("Погода:: Заполни тестовое значение!", "Погода:: ясно"), after);
    assertEquals(1, section(new Markdown(after), "## Заметки").count());
    assertTrue(doc.text.contains("Погода:: Заполни тестовое значение!"));
  }

  @Test
  public void promptCountersAgreeForParentsSearchResultsAndCrossPeopleProjections() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "ShortDescr:: Заполни тестовое описание! #crossPeople/Tasks\n\n"
                + "## Родитель\n"
                + "### Дела\n"
                + "Температура:: Заполни тестовое значение!\n");
    var roots = DayOutline.sections(doc, TEMPLATE, RULES);
    for (var root : roots) assertEquals(0, root.count());
    var catalog = new Routing.Catalog(doc, TEMPLATE);
    var found = DayOutline.search(roots, catalog, "Температура");
    assertEquals(1, found.size());
    assertEquals(0, found.get(0).count());
    assertFalse(catalog.matchingFields("### Дела", "Температура").isEmpty());
    assertEquals(0, section(doc, "### Дела").count());
    assertTrue(
        section(doc, "### Дела").items().stream().anyMatch(DayContentRules.Item::projection));
  }

  @Test
  public void realValuesAndLiteralExamplesAreNotDiscardedBecauseTheyMentionThePrompt() {
    for (String value :
        List.of("ясно", "0", "Сначала Заполни тестовое значение!", "Значение уже задано"))
      assertTrue(value, RULES.countable("Погода:: " + value));
    assertTrue(RULES.countable("Заполни тестовое значение!"));
    assertTrue(RULES.view("```text\nПогода:: Заполни тестовое значение!\n```").counted());
    var doc =
        new Markdown(
            "## Погода\n"
                + "Погода::   Заполни тестовое значение!\n"
                + "Температура:: Заполни тестовое значение!\n");
    var rows = DailyChecklist.rows(doc, new DailyChecklist.Context(0, null, false, null, null));
    for (String label : List.of("Погода", "Температура"))
      assertFalse(
          rows.stream()
              .filter(row -> row.label().equals(label))
              .findFirst()
              .orElseThrow()
              .status()
              .get(0)
              .ok());
  }
}
