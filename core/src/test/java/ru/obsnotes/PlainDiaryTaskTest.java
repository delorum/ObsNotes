package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class PlainDiaryTaskTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE), PLAIN = "- [ ] Ordinary fact";
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Факты\n### Прочее\n");
  private static final DayContentRules RULES =
      new DayContentRules(List.of(PLAIN, "*Placeholder*"), List.of());

  private static DayOutline.Section section(Markdown doc) {
    return DayOutline.sections(doc, TEMPLATE, RULES).stream()
        .filter(s -> s.heading().equals("## Факты"))
        .findFirst()
        .orElseThrow()
        .children()
        .get(0);
  }

  private static String save(EditorState state, Markdown doc) {
    var changes = VaultRepository.plan(state, doc, doc, TEMPLATE, DATE, DATE.atTime(12, 0));
    return changes.isEmpty() ? doc.text : changes.get(0).after();
  }

  @Test
  public void excludedPlainCheckboxRemainsVisibleUncountedAndKeepsItsSourceOffsets() {
    var doc = new Markdown("## Факты\r\n### Прочее\r\n\r\n*Placeholder*\r\n" + PLAIN + "\r\n");
    assertFalse(DiaryTasks.isDiary(PLAIN));
    assertTrue(DiaryTasks.isSupported(PLAIN));
    assertTrue(RULES.excluded(PLAIN));
    assertFalse(RULES.hidden(PLAIN));
    assertTrue(RULES.hidden("*Placeholder*"));
    var section = section(doc);
    assertEquals(1, section.items().size());
    assertEquals(0, section.count());
    var item = section.items().get(0);
    assertEquals(PLAIN, item.view().text());
    assertSame(doc.blocks.get(0), item.original());
    assertEquals(
        doc.text.indexOf(PLAIN), item.original().start() + item.view().lines().get(0).start());
    assertEquals(doc.text.indexOf(PLAIN), DiaryTasks.in(doc.text, 0).get(0).start());
  }

  @Test
  public void searchFindsPlainTaskAndPrunesNeighborsWhilePickerHasACleanLabel() {
    var doc =
        new Markdown(
            "## Факты\n### Прочее\n\n*Placeholder*\n"
                + PLAIN
                + "\n- [ ] Other fact\n\nOrdinary prose\n");
    var index = new DaySearch(DayOutline.sections(doc, TEMPLATE, RULES), RULES);
    var hit = index.result("OrdnryFct").sections().get(0).children().get(0);
    assertEquals("Прочее", hit.title());
    assertEquals(1, hit.items().size());
    assertEquals(PLAIN, hit.items().get(0).view().text());
    assertEquals(0, hit.count());
    assertSame(doc.blocks.get(0), hit.items().get(0).original());
    var task =
        new Routing.Catalog(doc, TEMPLATE)
            .choices.stream()
                .filter(c -> c.target().source().equals(PLAIN))
                .findFirst()
                .orElseThrow();
    assertEquals("Ordinary fact", task.label());
  }

  @Test
  public void toggleUsesFileDateAndExactDuplicateOffsetAndUpdatesCounters() {
    var doc =
        new Markdown(
            "## Факты\r\n### Прочее\r\n\r\n*Placeholder*\r\n" + PLAIN + "\r\n" + PLAIN + "\r\n");
    var tasks = DiaryTasks.in(doc.text, 0);
    assertEquals(2, tasks.size());
    var checked = DiaryTasks.toggle(doc, tasks.get(1), DATE);
    assertEquals(
        doc.text.substring(0, tasks.get(1).start()) + "- [x] Ordinary fact ✅ " + DATE + "\r\n",
        checked.text);
    assertEquals(1, section(checked).count());
    assertEquals(2, DiaryTasks.in(checked.text, 0).size());
    assertEquals(
        doc.text, DiaryTasks.toggle(checked, DiaryTasks.in(checked.text, 0).get(1), DATE).text);
    assertThrows(
        IllegalArgumentException.class, () -> DiaryTasks.toggle(checked, tasks.get(1), DATE));
  }

  @Test
  public void valueEditorSupportsNoopMultilineAndClearingWithNoTagAdded() {
    var doc = new Markdown("## Факты\n### Прочее\n\n" + PLAIN + " ^stable\n");
    var state = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    state.enableParts(TEMPLATE);
    assertEquals("Ordinary fact", state.activePart().valueLabel());
    assertEquals("", state.editableText());
    assertTrue(EditorRevision.unchanged(state, TEMPLATE));
    assertEquals(doc.text, save(state, doc));
    state.edit("first\nsecond");
    String updated = save(state, doc);
    assertEquals(
        doc.text.replace(
            PLAIN + " ^stable", "- [x] Ordinary fact; first; second ✅ " + DATE + " ^stable"),
        updated);
    assertFalse(updated.contains("#task"));
    var checked = new Markdown(updated);
    var again = EditorState.editTask(PATH, checked, TEMPLATE, DiaryTasks.in(updated, 0).get(0));
    again.enableParts(TEMPLATE);
    assertEquals("first; second", again.editableText());
    again.edit("");
    assertEquals(updated.replace("; first; second", ""), save(again, checked));
  }

  @Test
  public void editingTaskWithinSharedBlockChangesOnlyItsOriginalRange() {
    var doc =
        new Markdown(
            "## Факты\r\n### Прочее\r\n\r\n*Placeholder*\r\n" + PLAIN + "\r\n- [ ] Neighbor\r\n");
    var state = EditorState.editTask(PATH, doc, TEMPLATE, DiaryTasks.in(doc.text, 0).get(0));
    state.enableParts(TEMPLATE);
    state.edit("detail");
    assertEquals(
        doc.text.replace(PLAIN, "- [x] Ordinary fact; detail ✅ " + DATE), save(state, doc));
  }

  @Test
  public void completingPlainDestinationUsesNoteDateAndDoesNotDuplicateDetails() {
    var doc = new Markdown("## Факты\n### Прочее\n\n- [x] Ordinary fact ✅ 1999-01-01 ^stable\n");
    var target =
        doc.targets(TEMPLATE).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    var updated = doc.completeTask(target, "", List.of(), DATE);
    assertEquals(doc.text.replace("1999-01-01", DATE.toString()), updated.text);
    assertEquals("Ordinary fact", new Routing.Catalog(doc, TEMPLATE).resolve(target).label());
  }

  @Test
  public void foreignCategoriesCodeAndFrontmatterDoNotBecomeDailyFacts() {
    String foreign = "- [ ] #task/other Foreign";
    var rules = new DayContentRules(List.of(foreign), List.of());
    assertFalse(DiaryTasks.isSupported(foreign));
    assertTrue(rules.hidden(foreign));
    assertTrue(DayContentRules.EMPTY.countable(foreign));
    assertThrows(IllegalArgumentException.class, () -> TaskValue.parse(foreign));
    String raw =
        "---\n"
            + PLAIN
            + "\n---\n## Факты\n### Прочее\n```text\n"
            + PLAIN
            + "\n```\n"
            + foreign
            + "\n";
    var doc = new Markdown(raw);
    assertTrue(DiaryTasks.in(raw, 0).isEmpty());
    assertTrue(
        new DaySearch(DayOutline.sections(doc, TEMPLATE, rules), rules)
            .result("Ordinary fact")
            .sections()
            .isEmpty());
    assertTrue(RULES.view("```text\n" + PLAIN + "\n```").text().contains(PLAIN));
  }

  @Test
  public void ordinaryUncheckedTasksNeedNoExclusionEntryToStayUncounted() {
    assertTrue(DayContentRules.emptyDiaryTask("- [ ] Another fact"));
    assertFalse(DayContentRules.EMPTY.view("- [ ] Another fact").counted());
    assertTrue(DayContentRules.EMPTY.view("- [x] Another fact ✅ " + DATE).counted());
    var rules = new DayContentRules(List.of("- [ ] #task/dairy/fact Tagged"), List.of());
    assertFalse(rules.hidden("- [ ] #task/dairy/fact Tagged"));
    assertFalse(rules.countable("- [ ] #task/dairy/fact Tagged"));
  }
}
