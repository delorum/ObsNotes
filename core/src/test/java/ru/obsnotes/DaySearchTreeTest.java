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
    return new DaySearch(DayOutline.sections(doc, doc), new Routing.Catalog(doc, doc)).find(query);
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
    var search = new DaySearch(roots, new Routing.Catalog(DOC, DOC));
    search.find("RiverWalk");
    assertSame(roots, search.find(""));
    assertEquals(2, roots.get(1).children().size());
    assertTrue(roots.get(1).children().get(0).items().get(0).view().text().contains("SiblingTask"));
  }

  @Test
  public void codeFrontmatterOrdinaryTextCiphertextAndHiddenHintsDoNotMatch() {
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
    var search =
        new DaySearch(DayOutline.sections(doc, doc, rules), new Routing.Catalog(doc, doc), rules);
    for (String query :
        List.of("YamlOnly", "CodeOnly", "OrdinaryOnly", "HiddenOnly", "QUJDREVGR0hJSktMTU5P"))
      assertTrue(query, search.find(query).isEmpty());
  }

  @Test
  public void aliasMatchKeepsCorrectNestedBranchAndSiblingMatchesAreIndependentlyPruned() {
    var doc =
        new Markdown(
            "## Parent\n"
                + "### One\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"SpecificAlias\"});\n"
                + "```\n\n"
                + "### Two\n"
                + "Key:: foo\n");
    var s = find(doc, "spcfcls").get(0);
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
    var section =
        new DaySearch(DayOutline.sections(doc, doc, rules), catalog, rules).find("Key").get(0);
    var fields = catalog.matchingFields(section, "Key");
    assertEquals(1, fields.size());
    assertEquals("VisibleNeedle", fields.get(0).value());
    section =
        new DaySearch(DayOutline.sections(doc, doc, rules), catalog, rules)
            .find("VisibleNeedle")
            .get(0);
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
}
