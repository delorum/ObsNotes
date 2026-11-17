package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DataviewValueSearchTest {
  @Test
  public void keysAndValuesFindNestedFieldsAndExposeTheirCurrentValuesAndOriginalBlock() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "## Родитель\n"
                + "### Показатели\n"
                + "Температура:: +17 утром\n"
                + "Самочувствие:: Отлично\n\n"
                + "Tail\n");
    var catalog = new Routing.Catalog(doc, doc);
    for (String query : List.of("температура", "+17", "тмпртр")) {
      assertEquals(
          "### Показатели",
          DayOutline.search(DayOutline.sections(doc, doc), catalog, query).get(0).heading());
      var hits = catalog.matchingFields("### Показатели", query);
      assertEquals(1, hits.size());
      assertEquals("Температура:: +17 утром", hits.get(0).label());
      assertEquals(doc.blocks.get(0), hits.get(0).block());
    }
    assertEquals(
        "Самочувствие:: Отлично", catalog.matchingFields("### Показатели", "отлчн").get(0).label());
  }

  @Test
  public void
      currentExactValuePrecedesLiteralAndFuzzySectionMatchesAndPickerSearchesKeysAndValues() {
    var doc =
        new Markdown(
            "## BlueSky today\n\n## BrightLongueStarKindYard\n\n## Погода\nCondition:: BlueSky\n");
    assertEquals("## Погода", DayOutline.search(doc, doc, "bluesky").get(0).heading());
    var catalog = new Routing.Catalog(doc, doc);
    for (String query : List.of("Condition", "BlueSky", "BlSk")) {
      var found = FuzzySearch.ranked(catalog.choices, query, catalog::searchLabel);
      assertTrue(found.stream().anyMatch(c -> c.target().kind() == Markdown.Kind.FIELD));
    }
    var field =
        catalog.choices.stream()
            .filter(c -> c.target().kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    assertEquals("Погода / Condition:: BlueSky", catalog.pickerLabel(field));
    assertEquals("Condition", field.label());
  }

  @Test
  public void indexesOnlyLiveValuesNotTemplateValuesLiteralExamplesFrontmatterOrOtherNotes() {
    var template =
        new Markdown("## Заметки\n## Факты\nKey:: TemplateOnlyValue\nMissing:: MissingValue\n");
    var doc =
        new Markdown(
            "---\n"
                + "Key:: FrontmatterOnly\n"
                + "---\n"
                + "## Заметки\n"
                + "OrdinaryOnly\n\n"
                + "## Факты\n"
                + "Key:: CurrentOnly\n"
                + "```text\n"
                + "Example:: CodeOnly\n"
                + "```\n");
    for (String query :
        List.of("TemplateOnlyValue", "MissingValue", "FrontmatterOnly", "OrdinaryOnly", "CodeOnly"))
      assertTrue(query, DayOutline.search(doc, template, query).isEmpty());
    var catalog = new Routing.Catalog(doc, template);
    assertEquals("## Факты", DayOutline.search(doc, template, "CurrentOnly").get(0).heading());
    assertEquals(1, DayOutline.search(doc, template, "Missing").size());
    assertTrue(catalog.matchingFields("## Факты", "Missing").isEmpty());
  }

  @Test
  public void masksCiphertextAndRemovesMetadataFromLabelsAndSearchTerms() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "## Факты\n"
                + "Closed:: `secret QUJDREVGR0hJSktMTU5P`\n"
                + "Value:: visible #crossPeople/Work ^stable\n"
                + "Empty:: ^empty\n");
    var catalog = new Routing.Catalog(doc, doc);
    assertTrue(DayOutline.search(doc, doc, "QUJDREVGR0hJSktMTU5P").isEmpty());
    assertEquals(
        "Closed:: " + SecretText.PLACEHOLDER,
        catalog.matchingFields("## Факты", "Closed").get(0).label());
    assertEquals("Value:: visible", catalog.matchingFields("## Факты", "visible").get(0).label());
    var empty =
        catalog.choices.stream().filter(c -> c.label().equals("Empty")).findFirst().orElseThrow();
    assertEquals("Факты / Empty::", catalog.pickerLabel(empty));
  }

  @Test
  public void refreshingSnapshotUpdatesValuesAndClearingSearchRestoresHierarchyWithoutMutation() {
    var old = new Markdown("## Заметки\n## Родитель\n### Факты\nKey:: Before\n");
    var current = new Markdown(old.text.replace("Before", "After"));
    var catalog = new Routing.Catalog(current, current);
    assertTrue(DayOutline.search(current, current, "Before").isEmpty());
    assertEquals("### Факты", DayOutline.search(current, current, "After").get(0).heading());
    var roots = DayOutline.sections(current, current);
    assertSame(roots, DayOutline.search(roots, catalog, ""));
    assertTrue(catalog.matchingFields("### Факты", "").isEmpty());
    assertEquals("## Заметки\n## Родитель\n### Факты\nKey:: Before\n", old.text);
  }
}
