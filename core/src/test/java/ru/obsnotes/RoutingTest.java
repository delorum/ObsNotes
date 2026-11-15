package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class RoutingTest {
  @Test
  public void singleLineAppendsToDataviewValueAndMultilineCannotSelectIt() {
    var s = state("добавление");
    var c = s.routingCatalog(TEMPLATE);
    var field =
        c.choices.stream()
            .filter(x -> x.target().kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    s.applyRoutes(List.of(field), c);
    assertTrue(
        VaultRepository.plan(s, SOURCE, SOURCE, TEMPLATE, LocalDate.now(), LocalDateTime.now())
            .get(0)
            .after()
            .contains("Поле:: значение; добавление"));
    var multi = state("строка\nдругая");
    String before = multi.text;
    assertNotNull(multi.validateRoutes(List.of(field)));
    assertThrows(IllegalArgumentException.class, () -> multi.applyRoutes(List.of(field), c));
    assertEquals(before, multi.text);
    assertEquals("## Заметки", multi.heading);
    multi.chooseTarget(field.target());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            VaultRepository.plan(
                multi, SOURCE, SOURCE, TEMPLATE, LocalDate.now(), LocalDateTime.now()));
  }

  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Один\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person:"
              + " \"First,AliasOne,Пер1\"});\n"
              + "```\n\n"
              + "### Два\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Second\"});\n"
              + "```\n\n"
              + "## Три\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Third\"});\n"
              + "```\n");
  private static final Markdown SOURCE =
      new Markdown("## Заметки\n\nзаметка\n\n## Один\n\n- [ ] дело\nПоле:: значение\n");

  private static EditorState state(String text) {
    var s = new EditorState();
    s.sourcePath = s.destinationPath = "2026-11-15-вс.md";
    s.sourceSnapshot = s.destinationSnapshot = SOURCE.text;
    s.text = text;
    s.initializeRoutes(TEMPLATE);
    return s;
  }

  private static Routing.Choice choice(Routing.Catalog c, String heading) {
    return c.choices.stream()
        .filter(
            x -> x.target().heading().equals(heading) && x.target().kind() == Markdown.Kind.SECTION)
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void firstChoiceReceivesTextAndFollowingChoicesBecomeCrossPeopleInOrder() {
    var s = state("пример");
    var c = s.routingCatalog(TEMPLATE);
    s.applyRoutes(List.of(choice(c, "### Два"), choice(c, "## Три"), choice(c, "## Один")), c);
    assertEquals("### Два", s.heading);
    assertEquals("пример", s.editableText());
    assertEquals(List.of("Third", "First"), s.crossIds());
    assertTrue(s.savedText().contains("#crossPeople/ThirdFirst"));
    var result =
        VaultRepository.plan(
                s, SOURCE, SOURCE, TEMPLATE, LocalDate.of(2026, 11, 15), LocalDateTime.now())
            .get(0)
            .after();
    var m = new Markdown(result);
    assertTrue(
        m.blocks.stream()
            .anyMatch(
                b ->
                    b.section().heading().equals("### Два")
                        && b.text().contains("пример #crossPeople/ThirdFirst")));
  }

  @Test
  public void deletingPrimaryPromotesNextAndRemovesItsCrossTag() {
    var s = state("пример #person/Лена #crossOneLine/Second #crossPeople/Unknown");
    var c = s.routingCatalog(TEMPLATE);
    s.applyRoutes(List.of(choice(c, "## Один"), choice(c, "### Два"), choice(c, "## Три")), c);
    s.removeRoute(0, c);
    assertEquals("### Два", s.heading);
    assertEquals(List.of("Third"), s.crossIds());
    assertTrue(s.savedText().contains("#crossPeople/Third"));
    assertTrue(s.savedText().contains("#crossPeople/Unknown"));
    assertTrue(s.savedText().contains("#crossOneLine/Second"));
    assertFalse(s.savedText().contains("#crossPeople/Second"));
    assertTrue(s.savedText().contains("#person/Лена"));
  }

  @Test
  public void openingExistingTagsRetainsRawTextUntilRouteChanged() {
    String raw = "пример #crossPeople/ThirdFirst";
    var s = state(raw);
    assertEquals(raw, s.text);
    assertEquals(List.of("Third", "First"), s.crossIds());
    assertEquals("## Три", s.routes.get(1).target().heading());
  }

  @Test
  public void allAliasesAreSearchableIncludingNestedAndNumericOnes() {
    var c = state("x").routingCatalog(TEMPLATE);
    String label = c.searchLabel(choice(c, "## Один"));
    assertTrue(FuzzySearch.matches(label, "alon"));
    assertTrue(FuzzySearch.matches(label, "пр1"));
    assertEquals("Second", choice(c, "### Два").crossId());
  }

  @Test
  public void taskAndFieldMayBePrimaryButNeverExtras() {
    var s = state("x");
    var c = s.routingCatalog(TEMPLATE);
    var task =
        c.choices.stream()
            .filter(x -> x.target().kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    var field =
        c.choices.stream()
            .filter(x -> x.target().kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    var extra = choice(c, "## Три");
    assertNull(Routing.validate(List.of(task, extra)));
    assertNull(Routing.validate(List.of(field, extra)));
    assertNotNull(Routing.validate(List.of(extra, task)));
    assertNotNull(Routing.validate(List.of(extra, extra)));
    s.applyRoutes(List.of(task, extra), c);
    s.appendTaskText = false;
    String result =
        VaultRepository.plan(
                s, SOURCE, SOURCE, TEMPLATE, LocalDate.of(2026, 11, 15), LocalDateTime.now())
            .get(0)
            .after();
    assertTrue(result.contains("- [x] дело #crossPeople/Third ✅ 2026-11-15"));
    assertFalse(result.contains("; x"));
  }

  @Test
  public void filteredChoiceSequenceIsIndependentOfCatalogOrder() {
    var s = state("x");
    assertTrue(s.pickerRoutes().isEmpty());
    var c = s.routingCatalog(TEMPLATE);
    var pick = new PickerSelection<Routing.Choice>();
    pick.toggle(choice(c, "## Три"));
    pick.toggle(choice(c, "## Один"));
    s.applyRoutes(pick.selected(), c);
    assertEquals("## Три", s.heading);
    assertEquals(List.of("First"), s.crossIds());
  }
}
