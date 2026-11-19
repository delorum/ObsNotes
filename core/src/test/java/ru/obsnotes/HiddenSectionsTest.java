package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class HiddenSectionsTest {
  private static String cross(String heading, String ids) {
    return heading
        + "\n```dataviewjs\nawait dv.view(\"Скрипты/Dataview/crossPeople\", {person: \""
        + ids
        + "\"});\n```\n\n";
  }

  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + cross("## Семья", "Family")
              + cross("### Дела", "Tasks,TaskAlias")
              + cross("#### Подробности", "Details")
              + cross("### Прогулки", "Walks")
              + cross("## Работа", "Work")
              + "Поле::\n");
  private static final Markdown DOC =
      new Markdown(
          "## Заметки\n\n"
              + "исходный\n\n"
              + "## Семья\n\n"
              + "своё\n\n"
              + "### Дела\n\n"
              + "дело\n\n"
              + "#### Подробности\n\n"
              + "подробности\n\n"
              + "### Прогулки\n\n"
              + "прогулка\n\n"
              + "## Работа\n"
              + "Поле:: значение\n"
              + "- [ ] #task/diary Работа\n");

  @Test
  public void hidingParentHidesItsSubtreeFromDisplayAndEveryDestinationKind() {
    var hidden = new HiddenSections(DOC, TEMPLATE, Set.of("## Семья", "## Работа"));
    var outline = hidden.outline(DayOutline.sections(DOC, TEMPLATE));
    assertEquals(List.of("## Заметки"), outline.stream().map(DayOutline.Section::heading).toList());
    assertTrue(hidden.hidden("#### Подробности"));
    var catalog = new Routing.Catalog(DOC, TEMPLATE);
    var offered = hidden.offered(catalog.choices, c -> c.target().heading());
    assertEquals(List.of("## Заметки"), offered.stream().map(c -> c.target().heading()).toList());
    assertTrue(
        hidden
            .offered(
                CrossReferences.selectable(TEMPLATE, false), CrossReferences.Destination::heading)
            .isEmpty());
  }

  @Test
  public void hiddenChildrenDoNotCountAndNamesAliasesFieldsOrTaskTextCannotFindThem() {
    var hidden = new HiddenSections(DOC, TEMPLATE, Set.of("### Дела", "## Работа"));
    var visible = hidden.outline(DayOutline.sections(DOC, TEMPLATE));
    var family =
        visible.stream().filter(s -> s.heading().equals("## Семья")).findFirst().orElseThrow();
    assertEquals(2, family.count());
    assertEquals(
        List.of("### Прогулки"),
        family.children().stream().map(DayOutline.Section::heading).toList());
    var catalog = new Routing.Catalog(DOC, TEMPLATE);
    var routes = hidden.offered(catalog.choices, c -> c.target().heading());
    var tags =
        hidden.offered(
            CrossReferences.selectable(TEMPLATE, false), CrossReferences.Destination::heading);
    for (String query :
        List.of("Дела", "TaskAlias", "tsals", "Подробности", "Поле", "значение", "Работа")) {
      assertTrue(query, DayOutline.search(visible, catalog, query).isEmpty());
      assertTrue(query, FuzzySearch.ranked(routes, query, catalog::searchLabel).isEmpty());
      assertTrue(query, FuzzySearch.ranked(tags, query, catalog::tagSearchLabel).isEmpty());
    }
    assertEquals("### Прогулки", DayOutline.search(visible, catalog, "пглк").get(0).heading());
  }

  @Test
  public void
      restoringParentKeepsIndependentlyHiddenChildAndRestoringInheritedChildRevealsParents() {
    var hidden = new HiddenSections(DOC, TEMPLATE, Set.of("## Семья", "### Прогулки"));
    var restored = hidden.show("## Семья", true);
    assertFalse(restored.hidden("#### Подробности"));
    assertTrue(restored.hidden("### Прогулки"));
    restored = hidden.show("#### Подробности", true);
    assertFalse(restored.hidden("## Семья"));
    assertFalse(restored.hidden("### Дела"));
    assertFalse(restored.hidden("#### Подробности"));
    assertEquals(Set.of("### Прогулки"), restored.explicit());
    assertEquals(Set.of("## Семья", "### Прогулки"), hidden.explicit());
  }

  @Test
  public void settingsOfferHiddenFirstIncludingTemporarilyMissingSavedSections() {
    var hidden = new HiddenSections(DOC, TEMPLATE, Set.of("### Дела", "## Старое"));
    assertEquals(
        List.of("### Дела", "#### Подробности", "## Старое"),
        hidden.entries().stream()
            .filter(HiddenSections.Entry::hidden)
            .map(HiddenSections.Entry::heading)
            .toList());
    assertTrue(
        hidden.entries().stream()
            .filter(e -> e.heading().equals("#### Подробности"))
            .findFirst()
            .orElseThrow()
            .inherited());
    assertEquals(
        "Старое",
        hidden.entries().stream()
            .filter(e -> e.heading().equals("## Старое"))
            .findFirst()
            .orElseThrow()
            .title());
    assertFalse(hidden.show("## Старое", true).explicit().contains("## Старое"));
    assertEquals(TEMPLATE.sections.size() + 1, hidden.entries().size());
  }

  @Test
  public void customNestedSectionsFollowTheSameHierarchyAsMainScreen() {
    var custom =
        new Markdown("## Семья\n### Свой раздел\n#### Своя деталь\n\nтекст\n\n## Работа\n");
    var hidden = new HiddenSections(custom, TEMPLATE, Set.of("### Свой раздел"));
    assertTrue(hidden.hidden("#### Своя деталь"));
    assertFalse(hidden.hidden("## Работа"));
    var outline = hidden.outline(DayOutline.sections(custom, TEMPLATE));
    assertNull(DayOutline.resolveHeading(outline, "### Свой раздел"));
    assertNull(DayOutline.resolveHeading(outline, "#### Своя деталь"));
    assertEquals("### Дела", DayOutline.resolveHeading(outline, "### Дела"));
  }

  @Test
  public void existingAssignmentsAreNotRemovedByFilteringOrOpeningUnchangedEditor() {
    var source = new Markdown("## Заметки\n\nтекст #crossPeople/Tasks #crossOneLine/Details\n");
    var form = EditorState.editing("day.md", source, TEMPLATE, source.blocks.get(0));
    var catalog = form.routingCatalog(TEMPLATE);
    var originalRoutes = form.pickerRoutes();
    String originalText = form.savedText();
    var hidden = new HiddenSections(source, TEMPLATE, Set.of("## Семья"));
    assertFalse(
        hidden.offered(catalog.choices, c -> c.target().heading()).stream()
            .anyMatch(c -> c.crossId().equals("Tasks")));
    var choices = TagChoices.of(TEMPLATE, false, "Details");
    assertFalse(
        hidden
            .offered(choices.items(), CrossReferences.Destination::heading)
            .contains(choices.selected().get(0)));
    var selection = new PickerSelection<>(originalRoutes);
    assertEquals(originalRoutes, selection.selected());
    assertEquals(originalText, form.savedText());
    assertEquals(originalRoutes, form.pickerRoutes());
    assertTrue(EditorRevision.unchanged(form, TEMPLATE));
  }

  @Test
  public void restoreAllPersistsAnExplicitEmptySelectionWithoutReapplyingDefaults() {
    var template = new Markdown(String.join("\n", HiddenSections.DEFAULTS) + "\n");
    var hidden = new HiddenSections(template, template, HiddenSections.DEFAULTS);
    assertEquals(3, hidden.entries().stream().filter(HiddenSections.Entry::hidden).count());
    for (var heading : HiddenSections.DEFAULTS) hidden = hidden.show(heading, true);
    assertTrue(hidden.explicit().isEmpty());
    var loaded = new HiddenSections(template, template, hidden.explicit());
    assertEquals(3, loaded.outline(DayOutline.sections(template, template)).size());
    assertTrue(loaded.entries().stream().noneMatch(HiddenSections.Entry::hidden));
  }

  @Test
  public void displayedBlocksKeepOriginalOffsetsAndRestoringReturnsOriginalCounts() {
    var all = DayOutline.sections(DOC, TEMPLATE);
    var hidden = new HiddenSections(DOC, TEMPLATE, Set.of("### Дела"));
    var visible = hidden.outline(all);
    var originalFamily =
        all.stream().filter(s -> s.heading().equals("## Семья")).findFirst().orElseThrow();
    var family =
        visible.stream().filter(s -> s.heading().equals("## Семья")).findFirst().orElseThrow();
    assertSame(originalFamily.items().get(0).original(), family.items().get(0).original());
    assertEquals(4, originalFamily.count());
    assertEquals(2, family.count());
    assertEquals(all, hidden.show("### Дела", true).outline(all));
  }
}
