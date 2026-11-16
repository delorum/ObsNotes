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
}
