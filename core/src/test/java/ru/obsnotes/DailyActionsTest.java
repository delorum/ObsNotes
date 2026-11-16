package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class DailyActionsTest {
  @Test
  public void diaryLabelDoesNotStripOtherTagFamiliesWithSimilarNames() {
    var task = DiaryTasks.in("- [ ] #task/diary Fact #task/diaryish", 0).get(0);
    assertEquals("Fact #task/diaryish", task.label());
  }

  @Test
  public void completingAnAnchoredTaskPlacesTextTagsAndDateBeforeItsStableAnchor() {
    var m = new Markdown("## Дела\n- [ ] #task/diary/fact action ^event-stable\n");
    var target =
        m.targets(m).stream().filter(t -> t.kind() == Markdown.Kind.TASK).findFirst().orElseThrow();
    String result = m.completeTask(target, "detail", List.of("First"), NOTE).text;
    assertTrue(result.contains("action; detail #crossPeople/First ✅ 2001-02-03 ^event-stable\n"));
  }

  private static final LocalDate NOTE = LocalDate.of(2001, 2, 3);

  @Test
  public void bothDiarySpellingsToggleWithTheNoteDateAndNeverTheWallClock() {
    for (String tag : List.of("diary", "dairy")) {
      var before = new Markdown("## Дела\n\n- [ ] #task/" + tag + "/fact Действие ✅ 2025-10-10\n");
      var task = DiaryTasks.in(before.text, 0).get(0);
      var done = DiaryTasks.toggle(before, task, NOTE);
      assertTrue(done.text.contains("- [x] #task/" + tag + "/fact Действие ✅ 2001-02-03"));
      assertFalse(done.text.contains("2025-10-10"));
      assertEquals("Действие", DiaryTasks.in(done.text, 0).get(0).label());
      var undone = DiaryTasks.toggle(done, DiaryTasks.in(done.text, 0).get(0), NOTE);
      assertFalse(undone.text.contains("✅"));
      assertTrue(undone.text.contains("- [ ]"));
    }
  }

  @Test
  public void taskTogglePreservesOtherDatesAnchorAndCrLf() {
    String raw =
        "---\r\n"
            + "k: value\r\n"
            + "---\r\n"
            + "## Дела\r\n\r\n"
            + "- [ ] #task/dairy/fact Действие 📅 2001-02-01 #crossPeople/First ^event-id\r\n\r\n"
            + "другое\r\n";
    var m = new Markdown(raw);
    var task = DiaryTasks.in(raw, 0).get(0);
    String result = DiaryTasks.toggle(m, task, NOTE).text;
    assertEquals(
        raw.replace("- [ ]", "- [x]").replace(" ^event-id", " ✅ 2001-02-03 ^event-id"), result);
  }

  @Test
  public void noDiaryTasksInsideCodeFrontmatterOrSimilarTags() {
    String raw =
        "---\n"
            + "- [ ] #task/diary yaml\n"
            + "---\n"
            + "## Дела\n"
            + "````md\n"
            + "- [ ] #task/diary code\n"
            + "```\n"
            + "- [ ] #task/diary code2\n"
            + "````\n"
            + "- [ ] #task/diaryish no\n"
            + "- [ ] #task/dairy/fact yes\n";
    var tasks = DiaryTasks.in(raw, 0);
    assertEquals(1, tasks.size());
    assertEquals("yes", tasks.get(0).label());
    assertEquals(
        1,
        new Markdown(raw)
            .targets(new Markdown("")).stream()
                .filter(t -> t.kind() == Markdown.Kind.TASK && DiaryTasks.isDiary(t.source()))
                .count());
  }

  @Test
  public void staleTaskOrChangedBlockIsRejected() {
    var old = new Markdown("## Дела\n- [ ] #task/diary Test\n");
    var task = DiaryTasks.in(old.text, 0).get(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> DiaryTasks.toggle(new Markdown(old.text.replace("Test", "External")), task, NOTE));
    var source = new Markdown("## Заметки\n\nтекст\n");
    var block = source.blocks.get(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> DayEdits.delete(new Markdown(source.text.replace("текст", "внешнее")), block));
  }

  @Test
  public void deletingRootRemovesItsWholeCalloutButKeepsOtherContentAndCleansOrphans() {
    var root = Events.root("корень");
    var m =
        new Markdown(
            "---\nkey: value\n---\n## Заметки\n\n2001-02-03 12:00:00\n"
                + root
                + "\n\n\n## Дела\n\nоставить\n");
    String result = DayEdits.delete(m, m.blocks.get(0)).text;
    assertEquals("---\nkey: value\n---\n## Заметки\n\n## Дела\n\nоставить\n", result);
  }

  @Test
  public void diaryTaskCompletionThroughRoutingAlsoCorrectsTheDate() {
    var m = new Markdown("## Дела\n- [x] #task/dairy/fact done ✅ 1999-01-01\n");
    var target =
        m.targets(m).stream().filter(t -> t.kind() == Markdown.Kind.TASK).findFirst().orElseThrow();
    assertTrue(m.insert(target, "detail", NOTE, m).text.contains("done; detail ✅ 2001-02-03"));
  }

  @Test
  public void projectedTaskUsesRealSourceOffsetAndAmbiguousDuplicatesStayUneditable() {
    String raw = "- [ ] #task/diary/fact one #crossOneLine/First\n- [ ] #task/diary/fact two";
    var tasks = DiaryTasks.in(raw, 123);
    var projected = DiaryTasks.projectedLine(tasks, "- [ ] #task/diary/fact one").orElseThrow();
    assertEquals(123, projected.start());
    assertTrue(
        DiaryTasks.projectedLine(List.of(tasks.get(0), tasks.get(0)), "- [ ] #task/diary/fact one")
            .isEmpty());
  }
}
