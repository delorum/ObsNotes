package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DiaryTaskSearchTest {
  @Test
  public void mainSearchFindsBothDiarySpellingsTaskNamesAndAppendedDetailsInNestedSections() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + "## Факты\n"
                + "- [ ] #task/dairy/car Заправил машину\n\n"
                + "## Родитель\n"
                + "### Дела\n"
                + "- [x] #task/diary/fact Прогулка; по набережной ✅ 2026-11-16 ^stable\n");
    assertEquals("## Факты", DayOutline.search(doc, doc, "зпрвлмшн").get(0).heading());
    assertEquals("### Дела", DayOutline.search(doc, doc, "набережной").get(0).heading());
    assertEquals("### Дела", DayOutline.search(doc, doc, "прогулка").get(0).heading());
  }

  @Test
  public void exactTaskTitlePrecedesLiteralAndFuzzySectionMatches() {
    var doc =
        new Markdown(
            "## Длинная прогулка\n\n## По району гулял кто-то\n\n"
                + "## Факты\n- [ ] #task/dairy/fact Прогулка; вечером\n");
    var results = DayOutline.search(doc, doc, "прогулка");
    assertEquals("## Факты", results.get(0).heading());
    assertEquals("## Длинная прогулка", results.get(1).heading());
  }

  @Test
  public void taskSearchDoesNotInventMissingTemplateTasksOrIndexCodeFrontmatterAndOtherNotes() {
    var template = new Markdown("## Заметки\n## Факты\n- [ ] #task/dairy/fact TemplateOnly\n");
    var doc =
        new Markdown(
            "---\n"
                + "example: '- [ ] #task/dairy/fact FrontmatterOnly'\n"
                + "---\n"
                + "## Заметки\n"
                + "OrdinaryOnly\n\n"
                + "## Факты\n"
                + "```text\n"
                + "- [ ] #task/dairy/fact CodeOnly\n"
                + "```\n"
                + "- [ ] #task/other OtherOnly\n");
    for (String query :
        List.of("TemplateOnly", "FrontmatterOnly", "OrdinaryOnly", "CodeOnly", "OtherOnly"))
      assertTrue(query, DayOutline.search(doc, template, query).isEmpty());
  }

  @Test
  public void clearingTheQueryRestoresAllSectionsAndTheOriginalHierarchyWithoutChangingNotes() {
    var doc =
        new Markdown(
            "## Заметки\n\n## Родитель\n### Факты\n- [ ] #task/dairy/fact Unique\n\n## Other\n");
    String before = doc.text;
    var roots = DayOutline.sections(doc, doc);
    var catalog = new Routing.Catalog(doc, doc);
    assertEquals(1, DayOutline.search(roots, catalog, "uniq").size());
    assertSame(roots, DayOutline.search(roots, catalog, ""));
    assertEquals(3, roots.size());
    assertEquals(1, roots.get(1).children().size());
    assertEquals(before, doc.text);
  }
}
