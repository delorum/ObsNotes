package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DaySearchTreeTest {
  private static final Markdown DOC =
      new Markdown(
          "## Заметки\n"
              + "OrdinaryOnly\n\n"
              + "## Родитель\n"
              + "ParentOnly:: skip\n"
              + "### Вложенный\n"
              + "Key:: UniqueValue\n"
              + "Other:: unrelated\n"
              + "- [x] #task/diary/fact Routine; RiverWalk ✅ 2001-02-03\n"
              + "- [ ] #task/dairy/fact SiblingTask\n\n"
              + "### Сосед\n"
              + "Sibling:: ignored\n"
              + "## Другое\n"
              + "Other:: elsewhere\n");

  private static List<DayOutline.Section> find(Markdown doc, String query) {
    return new DaySearch(DayOutline.sections(doc, doc)).find(query);
  }

  @Test
  public void valueMatchKeepsOnlyItsLineAndAncestorPath() {
    var hits = find(DOC, "UniqueValue");
    assertEquals(1, hits.size());
    assertEquals("## Родитель", hits.get(0).heading());
    assertTrue(hits.get(0).items().isEmpty());
    var children = hits.get(0).children();
    assertEquals(1, children.size());
    assertEquals("### Вложенный", children.get(0).heading());
    assertEquals("Key:: UniqueValue", children.get(0).items().get(0).view().raw());
    assertEquals(1, children.get(0).items().get(0).view().lines().size());
  }

  @Test
  public void matchingTaskShowsItsCurrentValueAndPreservesExactOriginalOffsets() {
    var s = find(DOC, "rvrwlk").get(0).children().get(0);
    assertEquals(1, s.items().size());
    var item = s.items().get(0);
    assertEquals(1, item.view().lines().size());
    var line = item.view().lines().get(0);
    assertTrue(line.text().contains("RiverWalk"));
    assertEquals(DOC.text.indexOf("- [x]"), item.original().start() + line.start());
    assertTrue(item.original().text().contains("SiblingTask"));
    assertFalse(item.view().text().contains("SiblingTask"));
  }

  @Test
  public void keySearchDoesNotKeepUnmatchedSiblingFieldOrTask() {
    var s = find(DOC, "Key").get(0).children().get(0);
    assertEquals("Key:: UniqueValue", s.items().get(0).view().text());
  }

  @Test
  public void exactLeafRanksItsAncestorAheadOfLiteralAndFuzzyRoots() {
    var doc =
        new Markdown(
            "## RiverWalk today\n"
                + "## ReallyInvolvedVeryEarlyRoadWithALongKind\n"
                + "## Parent\n"
                + "### Leaf\n"
                + "Key:: RiverWalk\n");
    var hits = find(doc, "RiverWalk");
    assertEquals("## Parent", hits.get(0).heading());
    assertEquals("## RiverWalk today", hits.get(1).heading());
  }

  @Test
  public void clearingRestoresOriginalTreeAndCountsWithoutMutatingIt() {
    var roots = DayOutline.sections(DOC, DOC);
    var search = new DaySearch(roots);
    search.find("RiverWalk");
    assertSame(roots, search.find(""));
    assertEquals(2, roots.get(1).children().size());
    assertTrue(roots.get(1).children().get(0).items().get(0).view().text().contains("SiblingTask"));
  }

  @Test
  public void codeFrontmatterCiphertextAndHiddenHintsDoNotMatch() {
    var doc =
        new Markdown(
            "---\n"
                + "Key:: YamlOnly\n"
                + "---\n"
                + "## Parent\n"
                + "```text\n"
                + "Key:: CodeOnly\n"
                + "```\n\n"
                + "OrdinaryOnly\n\n"
                + "Hint:: HiddenOnly\n\n"
                + "Closed:: `secret QUJDREVGR0hJSktMTU5P`\n");
    var rules = new DayContentRules(List.of("Hint:: HiddenOnly"), List.of());
    var search = new DaySearch(DayOutline.sections(doc, doc, rules), rules);
    for (String query : List.of("YamlOnly", "CodeOnly", "HiddenOnly", "QUJDREVGR0hJSktMTU5P"))
      assertTrue(query, search.find(query).isEmpty());
  }

  @Test
  public void namesKeepCorrectNestedBranchAndAliasesAreNotSearchTerms() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "### One\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"SpecificAlias\"});\n"
                + "```\n\n"
                + "### Two\n"
                + "Key:: foo\n");
    assertTrue(find(doc, "spcfcls").isEmpty());
    var s = find(doc, "One").get(0);
    assertEquals(1, s.children().size());
    assertEquals("### One", s.children().get(0).heading());
    assertTrue(s.children().get(0).items().isEmpty());
  }

  @Test
  public void emptyMatchingFieldDoesNotCountContentOfItsNonmatchingSibling() {
    var doc = new Markdown("## Parent\nKey::\nOther:: filled\n");
    var s = find(doc, "Key").get(0);
    assertEquals(0, s.count());
    assertEquals(1, s.items().size());
  }

  @Test
  public void hiddenDuplicateFieldDoesNotLeakThroughSharedBlockAndTaskStaysVisible() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "Key:: HiddenNeedle\n"
                + "Key:: VisibleNeedle\n"
                + "- [ ] #task/diary/fact VisibleNeedle task\n"
                + "- [ ] #task/diary/fact OtherTask\n");
    var rules = new DayContentRules(List.of("Key:: HiddenNeedle"), List.of());
    var catalog = new Routing.Catalog(doc, doc);
    var section = new DaySearch(DayOutline.sections(doc, doc, rules), rules).find("Key").get(0);
    var fields = catalog.matchingFields(section, "Key");
    assertEquals(1, fields.size());
    assertEquals("VisibleNeedle", fields.get(0).value());
    section =
        new DaySearch(DayOutline.sections(doc, doc, rules), rules).find("VisibleNeedle").get(0);
    fields = catalog.matchingFields(section, "VisibleNeedle");
    var items = DaySearch.withoutFields(section.items(), fields);
    assertEquals(1, items.size());
    assertEquals(1, items.get(0).view().lines().size());
    assertTrue(items.get(0).view().raw().contains("VisibleNeedle task"));
    assertFalse(items.get(0).view().raw().contains("Key::"));
    assertEquals(
        doc.text.indexOf("- [ ]"),
        items.get(0).original().start() + items.get(0).view().lines().get(0).start());
  }

  @Test
  public void repeatedTaskTitlesAndDifferentFieldValuesCannotInventFuzzyMatches() {
    var doc =
        new Markdown(
            "## Inbox\n- [ ] #task/diary/fact Арбуз\n"
                + "- [x] #task/dairy/fact Барабан; осмотрен ✅ 2001-02-03\n"
                + "Metric:: ab\nOther:: cd\n## Заказ\nSummary:: Filled\n");
    var catalog = new Routing.Catalog(doc, doc);
    var search = new DaySearch(DayOutline.sections(doc, doc));
    var hits = search.find("за ");
    assertEquals(1, hits.size());
    assertEquals("## Заказ", hits.get(0).heading());
    assertEquals("Summary:: Filled", hits.get(0).items().get(0).view().raw());
    assertTrue(search.find("acbd").isEmpty());
    assertTrue(catalog.matchingFields("## Inbox", "tcb").isEmpty());
    assertEquals("## Inbox", search.find("барабан").get(0).heading());
    assertEquals(1, search.find("барабан").get(0).items().get(0).view().lines().size());
    assertEquals("## Inbox", search.find("осмтрн").get(0).heading());
  }

  @Test
  public void exactLiteralAndFuzzyNameMatchesKeepAllContentAndDescendants() {
    var doc =
        new Markdown(
            "## Inbox\n"
                + "Ignored\n\n"
                + "## Лада\n"
                + "First entry\n\n"
                + "Second entry\n\n"
                + "Value:: unrelated\n"
                + "- [ ] #task/diary/fact Unrelated task\n"
                + "### Details\n"
                + "Nested entry\n\n"
                + "#### Deep\n"
                + "Deep entry\n\n"
                + "## Other\n"
                + "Hidden by search\n");
    var outline = DayOutline.sections(doc, doc);
    var search = new DaySearch(outline);
    var original =
        outline.stream().filter(x -> x.heading().equals("## Лада")).findFirst().orElseThrow();
    for (String query : List.of("Лада", "лад", "лд ")) {
      var result = search.result(query);
      assertEquals(1, result.sections().size());
      assertSame(original, result.sections().get(0));
      assertEquals(original.count(), result.sections().get(0).count());
      assertEquals(Set.of("## Лада", "### Details", "#### Deep"), result.fullHeadings());
      assertTrue(
          result.sections().get(0).items().stream()
              .anyMatch(i -> i.view().text().equals("Second entry")));
      assertEquals(
          "Deep entry",
          result
              .sections()
              .get(0)
              .children()
              .get(0)
              .children()
              .get(0)
              .items()
              .get(0)
              .view()
              .text());
    }
  }

  @Test
  public void nestedNameMatchRetainsOnlyAncestorPathButKeepsWholeMatchingBranch() {
    var doc =
        new Markdown(
            "## Family\nParent entry\n\n### Лада\nPersonal entry\n\n"
                + "#### Details\nChild entry\n\n### Sibling\nSibling entry\n");
    var search = new DaySearch(DayOutline.sections(doc, doc));
    var result = search.result("лд");
    var parent = result.sections().get(0);
    assertEquals("## Family", parent.heading());
    assertTrue(parent.items().isEmpty());
    assertEquals(1, parent.children().size());
    assertEquals("Personal entry", parent.children().get(0).items().get(0).view().text());
    assertEquals(
        "Child entry", parent.children().get(0).children().get(0).items().get(0).view().text());
    assertEquals(Set.of("### Лада", "#### Details"), result.fullHeadings());
  }

  @Test
  public void contentOnlyHitsStayPrunedBesideAWholeNameMatch() {
    var doc =
        new Markdown(
            "## River\nOrdinary entry\n\nField:: unrelated\n"
                + "## Parent\nUnmatched parent entry\n\n### Leaf\nField:: River\nOther:: ignore\n"
                + "- [ ] #task/diary/fact River task\n- [ ] #task/diary/fact Ignore task\n");
    var search = new DaySearch(DayOutline.sections(doc, doc));
    var result = search.result("River");
    assertEquals(2, result.sections().size());
    assertEquals(Set.of("## River"), result.fullHeadings());
    var whole =
        result.sections().stream()
            .filter(x -> x.heading().equals("## River"))
            .findFirst()
            .orElseThrow();
    assertTrue(whole.items().stream().anyMatch(i -> i.view().text().equals("Ordinary entry")));
    var path =
        result.sections().stream()
            .filter(x -> x.heading().equals("## Parent"))
            .findFirst()
            .orElseThrow();
    assertTrue(path.items().isEmpty());
    String lines = path.children().get(0).items().get(0).view().raw();
    assertTrue(lines.contains("Field:: River"));
    assertTrue(lines.contains("River task"));
    assertFalse(lines.contains("ignore"));
    assertFalse(lines.contains("Ignore task"));
  }

  @Test
  public void fullNameMatchRespectsHiddenSectionsAndExcludedPlaceholders() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "### Visible\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"SpecialAlias\"});\n"
                + "```\n\n"
                + "Visible entry\n"
                + "HiddenPlaceholder\n\n"
                + "#### Hidden\n"
                + "Hidden child\n");
    var rules = new DayContentRules(List.of("HiddenPlaceholder"), List.of());
    var visibility = new HiddenSections(doc, doc, Set.of("#### Hidden"));
    var outline = visibility.outline(DayOutline.sections(doc, doc, rules));
    var search = new DaySearch(outline, rules);
    assertTrue(search.find("spclls").isEmpty());
    var result = search.result("vsbl");
    var section = result.sections().get(0).children().get(0);
    assertEquals("Visible entry", section.items().get(0).view().text());
    assertTrue(section.children().isEmpty());
    assertEquals(Set.of("### Visible"), result.fullHeadings());
    assertTrue(search.result("Hidden child").sections().isEmpty());
    assertSame(outline, search.result("").sections());
    assertEquals(Set.of("## Parent", "### Visible"), search.result("").fullHeadings());
  }

  @Test
  public void fullSectionKeepsCrossReferencesAndClosedSecretsAsNormalVisibleItems() {
    var template =
        new Markdown(
            "## Inbox\n"
                + "## Target\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"TargetAlias\"});\n"
                + "```\n");
    var doc =
        new Markdown(
            "## Inbox\nShared entry #crossPeople/TargetAlias\n\n"
                + "## Target\nLocal entry\n\n`secret QUJDREVGR0hJSktMTU5P`\n");
    var outline = DayOutline.sections(doc, template);
    var search = new DaySearch(outline);
    assertTrue(search.find("trgtls").isEmpty());
    var result = search.result("trgt");
    assertEquals(1, result.sections().size());
    var full = result.sections().get(0);
    assertEquals("## Target", full.heading());
    assertEquals(3, full.items().size());
    assertEquals(1, full.items().stream().filter(DayContentRules.Item::projection).count());
    assertTrue(full.items().stream().anyMatch(i -> i.view().text().equals("Local entry")));
    assertTrue(
        full.items().stream().anyMatch(i -> i.view().text().contains(SecretText.PLACEHOLDER)));
    assertFalse(
        full.items().stream().anyMatch(i -> i.view().text().contains("QUJDREVGR0hJSktMTU5P")));
    assertTrue(search.find("QUJDREVGR0hJSktMTU5P").isEmpty());
    assertEquals(Set.of("## Target"), result.fullHeadings());
  }
}
