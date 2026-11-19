package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class LineDestinationsTest {
  private static String section(String heading, String ids) {
    return heading
        + "\n```dataviewjs\n"
        + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \""
        + ids
        + "\"});\n```\n\n";
  }

  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + section("## Первый", "First,Children,FirstLong")
              + section("## Второй", "Second,Children")
              + section("## Общий", "Children")
              + section("### Лекарства первого", "MedFirst,MedChildren,Medicine")
              + section("### Лекарства второго", "MedSecond,MedChildren,Medicine")
              + section("### Общие лекарства", "MedChildren,Medicine")
              + section("## Перекус", "Snack1")
              + section("## Другое", "Other1,Other")
              + "## Без скрипта\n");

  @Test
  public void sharedAliasesDoNotHideTheirOwnSectionsOrDuplicateOtherRows() {
    var choices = CrossReferences.selectable(TEMPLATE, false);
    assertEquals(
        List.of("First", "Second", "Children", "MedFirst", "MedSecond", "MedChildren", "Other"),
        choices.stream().map(CrossReferences.Destination::id).toList());
    assertEquals(
        choices.size(),
        choices.stream().map(CrossReferences.Destination::heading).distinct().count());
    var catalog = new Routing.Catalog(new Markdown("## Заметки\n"), TEMPLATE);
    assertEquals(
        choices.stream().map(d -> d.heading() + "/" + d.id()).toList(),
        catalog.choices.stream()
            .filter(c -> !c.crossId().isEmpty())
            .map(c -> c.target().heading() + "/" + c.crossId())
            .toList());
    assertTrue(
        catalog.choices.stream().anyMatch(c -> c.target().heading().equals("## Без скрипта")));
    assertFalse(choices.stream().anyMatch(d -> d.heading().equals("## Без скрипта")));
  }

  @Test
  public void searchesFindAllAliasesAndTitlesWithoutExtraRows() {
    var catalog = new Routing.Catalog(TEMPLATE, TEMPLATE);
    var choices = CrossReferences.selectable(TEMPLATE, false);
    assertEquals(0, FuzzySearch.rank(catalog.tagSearchLabel(choices.get(0)), "Первый"));
    assertEquals(0, FuzzySearch.rank(catalog.tagSearchLabel(choices.get(0)), "FirstLong"));
    assertEquals(
        List.of("First"),
        FuzzySearch.ranked(choices, "firstlong", catalog::tagSearchLabel).stream()
            .map(CrossReferences.Destination::id)
            .toList());
    assertEquals(
        List.of("MedFirst", "MedSecond", "MedChildren"),
        FuzzySearch.ranked(choices, "medicine", catalog::tagSearchLabel).stream()
            .map(CrossReferences.Destination::id)
            .toList());
    assertEquals(
        "## Общий", FuzzySearch.ranked(choices, "общий", catalog::tagSearchLabel).get(0).heading());
    assertEquals(
        "First", FuzzySearch.ranked(choices, "frtlg", catalog::tagSearchLabel).get(0).id());
  }

  @Test
  public void existingSharedTagsSelectTheirCanonicalOwner() {
    var choices = TagChoices.of(TEMPLATE, false, "childrenMedChildren");
    assertEquals(
        List.of("## Общий", "### Общие лекарства"),
        choices.selected().stream().map(CrossReferences.Destination::heading).toList());
    assertEquals(7, choices.items().size());
  }

  @Test
  public void editingLegacyAliasKeepsItsIdAndLongestMatchWithoutAddingAllAliases() {
    var choices = TagChoices.of(TEMPLATE, false, "FirstLongUnknown7Children");
    assertEquals(
        List.of("FirstLong", "Unknown7", "Children"),
        choices.selected().stream().map(CrossReferences.Destination::id).toList());
    assertTrue(choices.items().containsAll(choices.selected()));
    assertFalse(choices.items().stream().anyMatch(d -> d.id().equals("Medicine")));
    assertEquals(9, choices.items().size());
    var tagged = TaggedText.parse("one #crossOneLine/FirstLong\ntwo");
    assertEquals(
        tagged.render(),
        tagged
            .replace(
                0,
                TagChoices.of(TEMPLATE, false, "FirstLong").selected().stream()
                    .map(CrossReferences.Destination::id)
                    .toList())
            .render());
  }

  @Test
  public void personPickerRemainsRestrictedAndCanonicalOwnersBeatEarlierAliases() {
    var template =
        new Markdown(
            section("## Группа", "Group,Аня,Лена")
                + section("## Первая", "Аня")
                + section("## Вторая", "Вика")
                + section("## Третья", "Лена")
                + section("## Четвертая", "Мама")
                + section("## Другой", "Other"));
    var choices = CrossReferences.selectable(template, true);
    assertEquals(
        List.of("Аня", "Вика", "Лена", "Мама"),
        choices.stream().map(CrossReferences.Destination::id).toList());
    assertEquals("## Первая", TagChoices.of(template, true, "Аня").selected().get(0).heading());
  }

  @Test
  public void newLineTagProjectsOnlySelectedLineToEveryAliasSection() {
    var state = new EditorState();
    state.destinationSnapshot = TEMPLATE.text;
    state.text = "one\ntwo\nthree";
    state.initializeRoutes(TEMPLATE);
    state.addTags(5, List.of("Children"), true, false);
    assertEquals("one\ntwo #crossOneLine/Children\nthree", state.savedText());
    var document =
        new Markdown(
            "## Заметки\n\n" + state.savedText() + "\n\n" + "## Первый\n\n## Второй\n\n## Общий\n");
    for (var heading : List.of("## Первый", "## Второй", "## Общий")) {
      var projections = CrossReferences.project(document, TEMPLATE, heading);
      assertEquals(1, projections.size());
      assertEquals("two", projections.get(0).text());
    }
    assertTrue(CrossReferences.project(document, TEMPLATE, "### Общие лекарства").isEmpty());
  }
}
