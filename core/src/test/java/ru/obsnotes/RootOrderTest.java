package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RootOrderTest {
  @Test
  public void existingRootsComeFirstWithSourceOrderPreservedWithinBothGroups() {
    String first = Events.root("Корень один ^first");
    String second = Events.root("Корень два ^second");
    var doc =
        new Markdown(
            "## Заметки\r\n\r\nКандидат один\r\n\r\n"
                + first.replace("\n", "\r\n")
                + "\r\n\r\nКандидат два\r\n\r\n## Другое\r\n\r\n"
                + second.replace("\n", "\r\n")
                + "\r\n\r\nКандидат три\r\n");
    var roots = new DiaryIndex().roots("day.md", doc, -1);
    assertEquals(
        List.of("first", "second", "", "", ""), roots.stream().map(Events.Root::id).toList());
    assertEquals(
        List.of(
            doc.blocks.get(1),
            doc.blocks.get(3),
            doc.blocks.get(0),
            doc.blocks.get(2),
            doc.blocks.get(4)),
        roots.stream().map(Events.Root::block).toList());
    for (var root : roots) {
      assertSame(
          doc.blocks.stream()
              .filter(b -> b.start() == root.block().start())
              .findFirst()
              .orElseThrow(),
          root.block());
      assertEquals(
          root.block().text(), doc.text.substring(root.block().start(), root.block().end()));
    }
  }

  @Test
  public void anchorAloneIsStillACandidateAndHiddenRootsAndEditedBlockAreExcluded() {
    String hidden = Events.root("*Скрыто* ^hidden");
    var doc =
        new Markdown(
            "## Заметки\n\nЯкорь без callout ^plain\n\n"
                + Events.root("Редактируемый ^edited")
                + "\n\n"
                + hidden
                + "\n\n"
                + "Кандидат\n\n"
                + Events.root("Готовый ^ready")
                + "\n");
    var rules = new DayContentRules(List.of("*Скрыто*"), List.of());
    var roots = new DiaryIndex().roots("day.md", doc, doc.blocks.get(1).start(), rules);
    assertEquals(List.of("ready", "", ""), roots.stream().map(Events.Root::id).toList());
    assertEquals(
        List.of("Готовый", "Якорь без callout", "Кандидат"),
        roots.stream().map(r -> r.visibleText(rules)).toList());
  }

  @Test
  public void rootsPriorityAlsoAppliesInChosenDayWhileSearchKeepsRelevance() throws Exception {
    var storage = new RootDestinationTest.Storage();
    var doc =
        new Markdown(
            "## Заметки\n\nТочное\n\nКандидат общий\n\n"
                + Events.root("Корень общий ^root")
                + "\n");
    storage.files.put(RootDestinationTest.PAST, doc.text);
    var form =
        EditorState.newNote(
            RootDestinationTest.TODAY,
            new Markdown("## Заметки\n"),
            RootDestinationTest.TEMPLATE,
            "## Заметки");
    form.chooseDay(RootDestinationTest.PAST, doc.text);
    var index = new DiaryIndex();
    var roots = index.roots(storage, form);
    assertEquals(List.of("root", "", ""), roots.stream().map(Events.Root::id).toList());
    assertEquals("root", FuzzySearch.ranked(roots, "общий", Events.Root::label).get(0).id());
    assertEquals(
        "Точное", FuzzySearch.ranked(roots, "Точное", Events.Root::label).get(0).block().text());
    assertEquals(doc.text, index.rootSnapshot(storage, form, roots.get(0)));
    assertEquals(List.of(RootDestinationTest.PAST, RootDestinationTest.PAST), storage.reads);
  }
}
