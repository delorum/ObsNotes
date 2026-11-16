package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class SearchOrderTest {
  @Test
  public void exactThenContiguousThenSubsequenceAndTiesKeepOriginalOrder() {
    var source = List.of("Порог", "Погода за окном", "Пог", "Пароходгруз", "никак");
    assertEquals(
        List.of("Пог", "Погода за окном", "Порог", "Пароходгруз"),
        FuzzySearch.ranked(source, "пог", s -> s));
  }

  @Test
  public void exactAliasesAreFirstWithoutChangingSelectionOrder() {
    var source = List.of("LongAlias value", "Heading\nShort, Alias", "Alien glass");
    assertEquals("Heading\nShort, Alias", FuzzySearch.ranked(source, "alias", s -> s).get(0));
    assertEquals(source, FuzzySearch.ranked(source, "", s -> s));
  }

  @Test
  public void exactWholeTextBeatsSubstringWithCaseAndYoNormalization() {
    assertEquals(
        List.of("Ёж", "Ёжик", "Елена ждёт"),
        FuzzySearch.ranked(List.of("Елена ждёт", "Ёжик", "Ёж"), "еж", s -> s));
  }

  @Test
  public void nestedSectionSearchIncludesAliasesWhileDefaultOutlineStaysHierarchical() {
    var template =
        new Markdown(
            "## Заметки\n\n"
                + "## Родитель\n\n"
                + "### Первый\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"AB,AlphaBeta\"});\n"
                + "```\n\n"
                + "### Пятый\n");
    var source = new Markdown("## Заметки\n\n## Родитель\n\n### Первый\ntext\n");
    assertEquals(2, DayOutline.search(source, template, "").size());
    var found = DayOutline.search(source, template, "albt");
    assertEquals(1, found.size());
    assertEquals("### Первый", found.get(0).heading());
    assertTrue(found.get(0).children().isEmpty());
    assertEquals(
        List.of("## Родитель", "### Первый"), DayOutline.ancestors(source, template, "### Первый"));
  }

  @Test
  public void missingMatchesReturnEmptyWithoutMutatingInput() {
    List<String> input = new ArrayList<>(List.of("abc", "def"));
    assertTrue(FuzzySearch.ranked(input, "xyz", s -> s).isEmpty());
    assertEquals(List.of("abc", "def"), input);
  }

  @Test
  public void sectionSearchFindsFieldKeysInTheNoteAndTemplateWithExactFirstRanking() {
    var template =
        new Markdown("## Погода\nТемпература::\n\n## Родитель\n### Самочувствие\nДавление::\n");
    var doc =
        new Markdown(
            "## Температура воздуха\n\n"
                + "## Погода\n"
                + "Погода:: ясно\n\n"
                + "## Особое\n"
                + "ТеМпеРаXYZтура:: 1\n");
    assertEquals(
        List.of("## Погода", "## Температура воздуха", "## Особое"),
        DayOutline.search(doc, template, "температура").stream()
            .map(DayOutline.Section::heading)
            .toList());
    assertEquals("### Самочувствие", DayOutline.search(doc, template, "двле").get(0).heading());
  }

  @Test
  public void fieldSearchDoesNotIndexValuesCodeFrontmatterOrUnrelatedSections() {
    var doc =
        new Markdown(
            "---\n"
                + "Секрет:: value\n"
                + "---\n"
                + "## Погода\n"
                + "Температура:: уникально\n"
                + "```text\n"
                + "Пример:: 2\n"
                + "```\n"
                + "## Прочее\n"
                + "Другое:: 3\n");
    for (String query : List.of("уникально", "Пример", "Секрет"))
      assertTrue(query, DayOutline.search(doc, doc, query).isEmpty());
    var found = DayOutline.search(doc, doc, "температура");
    assertEquals(1, found.size());
    assertEquals("## Погода", found.get(0).heading());
    assertEquals("## Прочее", DayOutline.search(doc, doc, "другое").get(0).heading());
  }
}
