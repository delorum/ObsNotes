package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RootPlaceholderTest {
  private static final String HINT =
      "*Добавьте тестовое фото [здесь](https://example.test/photos)*";
  private static final DayContentRules RULES =
      new DayContentRules(List.of(HINT, "[[Служебное]]"), List.of("#TestChecklist|Назад]]"));
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n");

  @Test
  public void standalonePlaceholdersAreNotOfferedButRealEventsRemainInOrder() {
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + HINT
                + "\n\nПервое событие\n\n"
                + "[[Служебное]]\n\n[[note#TestChecklist|Назад]]\n\nВторое событие\n");
    var roots = new DiaryIndex().roots("day.md", doc, -1, RULES);
    assertEquals(
        List.of("Первое событие", "Второе событие"),
        roots.stream().map(r -> r.block().text()).toList());
    assertEquals(5, doc.blocks.size());
    assertEquals(5, new DiaryIndex().roots("day.md", doc, -1).size());
  }

  @Test
  public void mixedBlockUsesVisibleCaptionAndKeepsSourceIdentityAndOffsets() {
    var doc = new Markdown("## Заметки\r\n\r\n" + HINT + "\r\nРеальное событие\r\n");
    var root = new DiaryIndex().roots("day.md", doc, -1, RULES).get(0);
    assertSame(doc.blocks.get(0), root.block());
    assertEquals(doc.text.indexOf(HINT), root.block().start());
    assertEquals(HINT + "\r\nРеальное событие", root.block().text());
    assertEquals("day / Заметки / Реальное событие", root.label(RULES));
    assertFalse(FuzzySearch.matches(root.label(RULES), "example.test"));
    assertTrue(FuzzySearch.matches(root.label(RULES), "реальное"));
    assertTrue(new DiaryIndex().roots("day.md", doc, root.block().start(), RULES).isEmpty());
  }

  @Test
  public void rootAnchorCalloutAndParentLinkCannotKeepHiddenOnlyEventInTheList() {
    String hiddenRoot = Events.root(HINT + " ^hidden");
    String realRoot = Events.root(HINT + "\nРеальный корень ^real");
    var doc =
        new Markdown(
            "## Заметки\n\n"
                + hiddenRoot
                + "\n\n"
                + realRoot
                + "\n\n"
                + "[[day#^real|Родитель]]\n"
                + HINT
                + "\n");
    var roots = new DiaryIndex().roots("day.md", doc, -1, RULES);
    assertEquals(1, roots.size());
    var root = roots.get(0);
    assertEquals("real", root.id());
    assertEquals("Реальный корень", root.visibleText(RULES));
    assertTrue(root.label(RULES).endsWith(" / Реальный корень"));
    assertTrue(root.block().text().contains("outLinkInfo"));
  }

  @Test
  public void filteringDoesNotExcludeTasksEmptyFieldsOrUnlistedSimilarText() {
    var doc =
        new Markdown(
            "## Заметки\n\n- [ ] #task/diary Пример\n\nПоле::\n\n"
                + "Поле:: Заполни пример\n\n"
                + HINT
                + " дополнительная запись\n");
    var roots = new DiaryIndex().roots("day.md", doc, -1, RULES);
    assertEquals(4, roots.size());
    assertEquals(doc.blocks, roots.stream().map(Events.Root::block).toList());
  }

  @Test
  public void selectedDaysFilteringPreservesSnapshotsAndUsesOnlyThatFile() throws Exception {
    var storage = new RootDestinationTest.Storage();
    var source = new Markdown("## Заметки\n\nРедактируемое\n");
    var target = new Markdown("## Заметки\n\n" + HINT + "\nНастоящее\n\n[[Служебное]]\n");
    storage.files.put(RootDestinationTest.PAST, target.text);
    var form =
        EditorState.editing(RootDestinationTest.TODAY, source, TEMPLATE, source.blocks.get(0));
    form.chooseDay(RootDestinationTest.PAST, target.text);
    var index = new DiaryIndex();
    var roots = index.roots(storage, form, RULES);
    assertEquals(1, roots.size());
    var root = roots.get(0);
    assertEquals(target.blocks.get(0), root.block());
    assertEquals(target.text, index.rootSnapshot(storage, form, root));
    assertEquals(List.of(RootDestinationTest.PAST, RootDestinationTest.PAST), storage.reads);
    form.addRootLink(root, target.text, null);
    var edit = form.rootEdits.get(0);
    assertEquals(root.block().text(), edit.original());
    assertTrue(edit.replacement().contains(HINT));
  }

  @Test
  public void literalPlaceholderLinesInCodeAreNotFilteredFromRealRoots() {
    String text = Events.root("```text\n" + HINT + "\n```\nСобытие ^literal");
    var doc = new Markdown("## Заметки\n\n" + text + "\n");
    var roots = new DiaryIndex().roots("day.md", doc, -1, RULES);
    assertEquals(1, roots.size());
    assertTrue(roots.get(0).visibleText(RULES).contains(HINT));
    assertEquals(doc.blocks.get(0), roots.get(0).block());
  }
}
