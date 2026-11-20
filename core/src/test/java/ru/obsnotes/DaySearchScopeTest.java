package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DaySearchScopeTest {
  private static DaySearch search(Markdown doc) {
    return new DaySearch(DayOutline.sections(doc, doc));
  }

  private static String tagged(String heading, String alias) {
    return heading
        + "\n```dataviewjs\nawait dv.view(\"Скрипты/Dataview/crossPeople\", {person: \""
        + alias
        + "\"});\n```\n\n";
  }

  @Test
  public void sectionNamesMatchWithoutSharedIdsAndUseNormalRelevanceOrder() {
    var doc =
        new Markdown(
            tagged("## RedPerson", "ChildrenShared")
                + "Red entry\n\n"
                + tagged("## BluePerson", "ChildrenShared")
                + "Blue entry\n\n"
                + "## Children together\nTogether entry\n\n## Children\nGroup entry\n");
    var search = search(doc);
    var result = search.result("children");
    assertEquals(
        List.of("## Children", "## Children together"),
        result.sections().stream().map(DayOutline.Section::heading).toList());
    assertTrue(search.find("ChildrenShared").isEmpty());
    assertEquals(2, search.find("chldrn").size());
    assertTrue(
        result.sections().stream().allMatch(s -> result.fullHeadings().contains(s.heading())));
  }

  @Test
  public void exactLiteralAndFuzzyProseQueriesDoNotMatchButTitleStillShowsItsContent() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "Opening context\n"
                + "OrdinaryNeedle in middle\n"
                + "Ending context\n\n"
                + "Second entry\n");
    var search = search(doc);
    for (String query :
        List.of(
            "OrdinaryNeedle in middle",
            "OrdinaryNeedle",
            "ordndle",
            "Opening context",
            "Opening context OrdinaryNeedle")) assertTrue(query, search.find(query).isEmpty());
    var result = search.result("prnt");
    assertEquals(Set.of("## Parent"), result.fullHeadings());
    assertEquals(2, result.sections().get(0).items().size());
    assertEquals(
        "Opening context\nOrdinaryNeedle in middle\nEnding context",
        result.sections().get(0).items().get(0).view().text());
  }

  @Test
  public void dataviewValuesDoNotMatchButNamesStillShowTheirCurrentValues() {
    var doc = new Markdown("## Parent\n### Leaf\nWeight:: UniqueValue\nOther:: unrelated\n");
    var search = search(doc);
    for (String query : List.of("UniqueValue", "Unique", "unqvl", "unrelated"))
      assertTrue(query, search.find(query).isEmpty());
    for (String query : List.of("Weight", "Weigh", "wght")) {
      var parent = search.find(query).get(0);
      assertTrue(parent.items().isEmpty());
      assertEquals(1, parent.children().size());
      var item = parent.children().get(0).items().get(0);
      assertEquals("Weight:: UniqueValue", item.view().raw());
      assertEquals(
          doc.text.indexOf("Weight::"),
          item.original().start() + item.view().lines().get(0).start());
    }
  }

  @Test
  public void keyRelevanceCannotBePromotedByItsValueAndPickerValueSearchRemainsSeparate() {
    var doc = new Markdown("## Parent\nWinterEight:: Weight\nWeightEstimate:: 73\nWeight:: 72\n");
    var section = search(doc).find("Weight").get(0);
    var catalog = new Routing.Catalog(doc, doc);
    assertEquals(
        List.of("Weight", "WeightEstimate", "WinterEight"),
        catalog.matchingFields(section, "Weight").stream().map(Routing.FieldHit::key).toList());
    assertTrue(catalog.matchingFields(section, "72").isEmpty());
    assertEquals(1, catalog.matchingFields("## Parent", "72").size());
  }

  @Test
  public void aValueEqualToAnotherKeyDoesNotBringItsSiblingOrProseIntoResults() {
    var doc =
        new Markdown("## Parent\nTargetKey prose\nFirst:: TargetKey\nTargetKey:: ActualValue\n");
    var result = search(doc).result("TargetKey");
    var section = result.sections().get(0);
    assertTrue(result.fullHeadings().isEmpty());
    assertEquals("TargetKey:: ActualValue", section.items().get(0).view().raw());
    var fields = new Routing.Catalog(doc, doc).matchingFields(section, "TargetKey");
    assertEquals(1, fields.size());
    assertEquals("TargetKey", fields.get(0).key());
  }

  @Test
  public void taskTitlesAndAppendedDetailsStillMatchWithoutUnrelatedBlockLines() {
    var doc =
        new Markdown(
            "## Parent\n### Leaf\nOrdinaryOnly\nMetric:: ValueOnly\n"
                + "- [x] #task/dairy/fact Routine; RiverWalk ✅ 2001-02-03\n"
                + "- [ ] #task/diary/fact IgnoreTask\n");
    var search = search(doc);
    for (String query : List.of("Routine", "rtn", "rvrwlk")) {
      var section = search.find(query).get(0).children().get(0);
      assertEquals(1, section.items().get(0).view().lines().size());
      assertTrue(section.items().get(0).view().raw().contains("Routine; RiverWalk"));
    }
    assertTrue(search.find("OrdinaryOnly").isEmpty());
    assertTrue(search.find("ValueOnly").isEmpty());
  }

  @Test
  public void emptyAndSecretFieldNamesStayAvailableWithoutIndexingTheirValues() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "EmptyKey::\n"
                + "PromptKey:: Заполни тестовое значение!\n"
                + "SecretKey:: `secret QUJDREVGR0hJSktMTU5P`\n");
    var search = search(doc);
    for (String query : List.of("EmptyKey", "PromptKey", "SecretKey"))
      assertEquals(1, search.find(query).get(0).items().get(0).view().lines().size());
    assertEquals(0, search.find("EmptyKey").get(0).count());
    assertEquals(0, search.find("PromptKey").get(0).count());
    for (String query : List.of("Заполни", "тестовое значение", "QUJDREVGR0hJSktMTU5P"))
      assertTrue(query, search.find(query).isEmpty());
    assertTrue(
        search
            .find("SecretKey")
            .get(0)
            .items()
            .get(0)
            .view()
            .text()
            .contains(SecretText.PLACEHOLDER));
  }

  @Test
  public void hiddenHintsCodeAndFrontmatterDoNotBecomeKeyOrTaskResults() {
    var doc =
        new Markdown(
            "---\nYamlKey:: example\n---\n## Parent\nHiddenKey:: value\n"
                + "```text\nLiteralKey:: example\n- [ ] #task/diary/fact CodeTask\n```\n");
    var rules = new DayContentRules(List.of("HiddenKey:: value"), List.of());
    var search = new DaySearch(DayOutline.sections(doc, doc, rules), rules);
    for (String query : List.of("YamlKey", "HiddenKey", "LiteralKey", "CodeTask"))
      assertTrue(query, search.find(query).isEmpty());
  }

  @Test
  public void projectedProseDoesNotMatchButRemainsVisibleInsideItsNamedSection() {
    var template = new Markdown("## Inbox\n" + tagged("## Target", "TargetAlias"));
    var doc =
        new Markdown(
            "## Inbox\nOpening\nOrdinaryNeedle #crossPeople/TargetAlias\nEnding\n\n## Target\n");
    var search = new DaySearch(DayOutline.sections(doc, template));
    assertTrue(search.find("OrdinaryNeedle").isEmpty());
    var result = search.result("Target");
    assertEquals(1, result.sections().size());
    var projection = result.sections().get(0).items().get(0);
    assertTrue(projection.projection());
    assertSame(doc.blocks.get(0), projection.original());
    assertTrue(projection.view().text().contains("OrdinaryNeedle"));
  }

  @Test
  public void hiddenBranchesStayHiddenAndClearingRestoresOriginalOrdinaryBlocksAndValues() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "### Visible\n"
                + "ProseOnly\n\n"
                + "VisibleKey:: ValueOnly\n"
                + "### Hidden\n"
                + "HiddenKey:: value\n");
    var roots =
        new HiddenSections(doc, doc, Set.of("### Hidden")).outline(DayOutline.sections(doc, doc));
    var search = new DaySearch(roots);
    assertTrue(search.find("HiddenKey").isEmpty());
    assertTrue(search.find("ProseOnly").isEmpty());
    assertTrue(search.find("ValueOnly").isEmpty());
    assertEquals(1, search.find("VisibleKey").get(0).children().get(0).items().size());
    assertSame(roots, search.result("").sections());
    assertEquals(Set.of("## Parent", "### Visible"), search.result("").fullHeadings());
    assertEquals(2, search.result("").sections().get(0).children().get(0).items().size());
  }
}
