package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DayTextSearchTest {
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
  public void ordinaryTextMatchKeepsTheWholeBlockAndOnlyItsAncestorPath() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "Unrelated parent\n\n"
                + "### Leaf\n"
                + "Opening\n"
                + "Needle in middle\n"
                + "Ending\n\n"
                + "Unrelated sibling\n\n"
                + "### Sibling\n"
                + "Other entry\n");
    var result = search(doc).result("ndlmdl");
    assertEquals(1, result.sections().size());
    var parent = result.sections().get(0);
    assertTrue(parent.items().isEmpty());
    assertEquals(1, parent.children().size());
    var item = parent.children().get(0).items().get(0);
    assertEquals("Opening\nNeedle in middle\nEnding", item.view().text());
    assertEquals(1, parent.children().get(0).items().size());
    assertEquals(1, parent.count());
    assertTrue(result.fullHeadings().isEmpty());
    assertEquals(Set.of(item), result.textMatches());
    assertSame(
        doc.blocks.stream().filter(b -> b.text().startsWith("Opening")).findFirst().orElseThrow(),
        item.original());
  }

  @Test
  public void orderedGapsMaySpanVisibleLinesOfOneRealBlockButNeverDifferentBlocks() {
    var doc = new Markdown("## Parent\nAmber\nCobalt\n\nDelta\n\nEcho\n");
    var search = search(doc);
    var result = search.result("AM CB ");
    assertEquals("Amber\nCobalt", result.sections().get(0).items().get(0).view().text());
    assertEquals(1, result.textMatches().size());
    assertTrue(search.find("delta echo").isEmpty());
    assertTrue(search.find("daec").isEmpty());
  }

  @Test
  public void structuredHitsDoNotPullInUnmatchedProseButProseHitsKeepMixedBlocks() {
    var doc =
        new Markdown(
            "## Parent\nUnrelated prose\nField:: TargetNeedle\nOther:: Keep\n"
                + "- [ ] #task/diary/fact TargetNeedleTask\n- [ ] #task/diary/fact IgnoreTask\n");
    var search = search(doc);
    var result = search.result("TargetNeedle");
    assertTrue(result.textMatches().isEmpty());
    String visible = result.sections().get(0).items().get(0).view().raw();
    assertTrue(visible.contains("Field:: TargetNeedle"));
    assertTrue(visible.contains("TargetNeedleTask"));
    assertFalse(visible.contains("Unrelated prose"));
    assertFalse(visible.contains("Other:: Keep"));
    assertFalse(visible.contains("IgnoreTask"));
    result = search.result("Unrelated prose");
    var item = result.sections().get(0).items().get(0);
    assertEquals(doc.blocks.get(0).text(), item.view().raw());
    assertEquals(Set.of(item), result.textMatches());
  }

  @Test
  public void exactLiteralAndFuzzyTextHitsKeepTheirRankingAndOriginalOffsets() {
    var doc =
        new Markdown(
            "## First\nN-e-e-d-l-e\n\n## Second\nLonger Needle text\n\n## Third\nNeedle\n");
    var result = search(doc).result("Needle");
    assertEquals(
        List.of("## Third", "## Second", "## First"),
        result.sections().stream().map(DayOutline.Section::heading).toList());
    for (var section : result.sections()) {
      var item = section.items().get(0);
      assertEquals(
          item.original().text(),
          doc.text.substring(item.original().start(), item.original().end()));
      assertTrue(result.textMatches().contains(item));
    }
  }

  @Test
  public void excludedLinesMetadataGeneratedScriptsLiteralsAndCiphertextAreNotPlainSearchText() {
    String event =
        "[[other#^root|MetadataCaption]]\n"
            + Events.root("Visible sentence\nHiddenPlaceholder #crossPeople/MetaOnlyAlias ^stable");
    var doc =
        new Markdown(
            "---\nYamlOnly: example\n---\n## Parent\n"
                + event
                + "\n\n"
                + "```text\nLiteralOnly\n```\n\n`secret QUJDREVGR0hJSktMTU5P`\n");
    var rules =
        new DayContentRules(
            List.of("HiddenPlaceholder #crossPeople/MetaOnlyAlias ^stable"), List.of());
    var search = new DaySearch(DayOutline.sections(doc, doc, rules), rules);
    for (String q :
        List.of(
            "YamlOnly",
            "MetadataCaption",
            "HiddenPlaceholder",
            "MetaOnlyAlias",
            "stable",
            "outLinkInfo",
            "LiteralOnly",
            "QUJDREVGR0hJSktMTU5P")) assertTrue(q, search.find(q).isEmpty());
    assertEquals(1, search.result("Visible sentence").textMatches().size());
  }

  @Test
  public void matchingVisibleProjectionsRetainsTheirDisplayedBlockAndOriginalForEditing() {
    var template = new Markdown("## Inbox\n" + tagged("## Target", "TargetAlias"));
    var doc =
        new Markdown(
            "## Inbox\nOpening\nNeedle body #crossPeople/TargetAlias\nEnding\n\n## Target\n");
    var search = new DaySearch(DayOutline.sections(doc, template));
    var result = search.result("ndlbdy");
    assertEquals(2, result.sections().size());
    var projection =
        result.sections().stream()
            .filter(s -> s.heading().equals("## Target"))
            .findFirst()
            .orElseThrow()
            .items()
            .get(0);
    assertTrue(projection.projection());
    assertSame(doc.blocks.get(0), projection.original());
    assertTrue(projection.view().text().contains("Opening"));
    assertTrue(projection.view().text().contains("Ending"));
    assertTrue(result.textMatches().contains(projection));
  }

  @Test
  public void hiddenSectionsStayUnsearchableAndClearingRestoresTheOriginalTree() {
    var doc = new Markdown("## Parent\n### Visible\nVisible Needle\n\n### Hidden\nHidden Needle\n");
    var visibility = new HiddenSections(doc, doc, Set.of("### Hidden"));
    var roots = visibility.outline(DayOutline.sections(doc, doc));
    var search = new DaySearch(roots);
    assertTrue(search.find("Hidden Needle").isEmpty());
    assertEquals(1, search.result("Needle").textMatches().size());
    assertSame(roots, search.result("").sections());
    assertTrue(search.result("").textMatches().isEmpty());
    assertEquals(Set.of("## Parent", "### Visible"), search.result("").fullHeadings());
  }

  @Test
  public void plainMatchesKeepTheirOriginalViewWhenSiblingFieldRowsAreSeparated() {
    var doc = new Markdown("## Parent\n  Needle prose  \n\nKey:: Needle\n");
    var result = search(doc).result("Needle");
    var section = result.sections().get(0);
    var fields = new Routing.Catalog(doc, doc).matchingFields(section, "Needle");
    assertEquals(1, fields.size());
    var items = DaySearch.withoutFields(section.items(), fields);
    assertEquals(1, items.size());
    var original = result.textMatches().iterator().next();
    assertSame(original, items.get(0));
    assertEquals("Needle prose", items.get(0).view().raw());
    assertTrue(result.textMatches().contains(items.get(0)));
  }
}
