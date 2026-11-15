package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DayOutlineTest {
  private static final Markdown TEMPLATE =
      new Markdown(
          "## План\n\n"
              + "## Заметки\n\n"
              + "## Семья\n\n"
              + "### Здоровье\n\n"
              + "#### Лекарства\n\n"
              + "### Прогулки\n\n"
              + "## Погода\n");

  @Test
  public void topLevelContainsNoNestedHeadingsAndNotesRemainFirst() {
    var roots = DayOutline.sections(new Markdown("## Заметки\n"), TEMPLATE);
    assertEquals(
        List.of("Заметки", "План", "Семья", "Погода"),
        roots.stream()
            .map(DayOutline.Section::title)
            .collect(java.util.stream.Collectors.toList()));
    var family = roots.get(2);
    assertEquals(2, family.children().size());
    assertEquals("Лекарства", family.children().get(0).children().get(0).title());
  }

  @Test
  public void parentCountIncludesOwnBlocksAndDescendants() {
    Markdown m =
        new Markdown(
            "## Семья\n\n"
                + "свой\n\n"
                + "### Здоровье\n\n"
                + "один\n\n"
                + "два\n\n"
                + "#### Лекарства\n\n"
                + "три\n\n"
                + "### Прогулки\n\n"
                + "четыре\n");
    var family =
        DayOutline.sections(m, TEMPLATE).stream()
            .filter(s -> s.title().equals("Семья"))
            .findFirst()
            .orElseThrow();
    assertEquals(5, family.count());
    assertEquals(1, family.blocks().size());
    assertEquals(3, family.children().get(0).count());
  }

  @Test
  public void customNestedSectionKeepsItsActualParent() {
    Markdown m =
        new Markdown("## Заметки\n\n## Семья\n\n### Свой подраздел\n\nтекст\n\n## Погода\n");
    var family =
        DayOutline.sections(m, TEMPLATE).stream()
            .filter(s -> s.title().equals("Семья"))
            .findFirst()
            .orElseThrow();
    assertEquals("Свой подраздел", family.children().get(0).title());
    assertEquals(1, family.count());
  }

  @Test
  public void hierarchyDoesNotHideNestedDestinationsFromPicker() {
    Markdown m = new Markdown("## Заметки\n");
    DayOutline.sections(m, TEMPLATE);
    assertEquals(TEMPLATE.sections.size(), m.targets(TEMPLATE).size());
    assertTrue(m.targets(TEMPLATE).stream().anyMatch(t -> t.heading().equals("#### Лекарства")));
  }

  @Test
  public void skipsInHeadingLevelsStillAttachToNearestParent() {
    Markdown m = new Markdown("## Заметки\n\n#### Глубоко\n\nодин\n");
    var roots = DayOutline.sections(m, m);
    assertEquals(1, roots.size());
    assertEquals("Глубоко", roots.get(0).children().get(0).title());
    assertEquals(1, roots.get(0).count());
  }
}
