package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class TaskAppendEditorTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Факты\n\n## Другое\n");
  private static final String LINE = "- [ ] #task/dairy/fact Routine #crossPeople/Extra ^stable-id";
  private static final Markdown SOURCE =
      new Markdown(
          "## Заметки\n\nобычный  текст\n\n\nвчера\n\n## Факты\n\n" + LINE + "\n\nWeight:: 75\n");

  private EditorState open(Markdown doc, int index) {
    return EditorState.appendTask(PATH, doc, TEMPLATE, DiaryTasks.in(doc.text, 0).get(index));
  }

  private List<VaultRepository.Change> plan(EditorState state, Markdown doc) {
    return VaultRepository.plan(state, doc, doc, TEMPLATE, DATE, NOW);
  }

  @Test
  public void openingProtectsTheTaskLabelAndEmptyExitDoesNotMarkOrWriteAnything() {
    var state = open(SOURCE, 0);
    assertTrue(state.taskAppend);
    assertTrue(state.fresh);
    assertEquals(Markdown.Kind.TASK, state.kind);
    assertEquals("", state.editableText());
    assertEquals("Routine", state.routes.get(0).label());
    assertEquals(LINE, state.targetSource);
    assertEquals(SOURCE.text, state.sourceSnapshot);
    assertEquals(SOURCE.text, state.destinationSnapshot);
    assertEquals(new EditorState.InputCaret(0, 0), state.inputCaret());
    assertTrue(plan(state, SOURCE).isEmpty());
  }

  @Test
  public void savingAdditionAppendsAndCompletesWithTheNoteDateWithoutReformattingOtherText() {
    var state = open(SOURCE, 0);
    state.edit("new detail");
    var result = plan(state, SOURCE);
    assertEquals(1, result.size());
    assertEquals(PATH, result.get(0).path());
    String line =
        "- [x] #task/dairy/fact Routine #crossPeople/Extra; new detail ✅ 2001-02-03 ^stable-id";
    assertEquals(SOURCE.text.replace(LINE, line), result.get(0).after());
    assertTrue(result.get(0).after().contains("\n\n\nвчера\n"));
  }

  @Test
  public void alreadyCompletedTasksKeepTheirDetailsCorrectTheDateAndAvoidDuplicateAddition() {
    var doc =
        new Markdown(
            SOURCE.text.replace(
                LINE, "- [x] #task/diary/fact Routine; first ✅ 1999-01-01 ^stable-id"));
    var state = open(doc, 0);
    state.edit("second");
    var after = new Markdown(plan(state, doc).get(0).after());
    assertTrue(after.text.contains("Routine; first; second ✅ 2001-02-03 ^stable-id"));
    var again = open(after, 0);
    again.edit("second");
    assertEquals(after.text, plan(again, after).get(0).after());
    assertTrue(SectionUsage.planned(again, TEMPLATE, plan(again, after)).isEmpty());
  }

  @Test
  public void identicalTaskLabelsUseTheChosenSourceOffsetAndPreserveSiblingLines() {
    String task = "- [ ] #task/dairy/fact Duplicate";
    var doc = new Markdown("## Факты\r\n\r\nHint\r\n" + task + "\r\n" + task + "\r\n");
    var state = open(doc, 1);
    state.edit("second only");
    String result = plan(state, doc).get(0).after();
    assertTrue(
        result.contains(
            "Hint\r\n" + task + "\r\n- [x] #task/dairy/fact Duplicate; second only ✅ 2001-02-03"));
    assertEquals(1, result.split("second only", -1).length - 1);
  }

  @Test
  public void staleOrLiteralTaskReferencesCannotBeOpenedAsLiveTaskForms() {
    var old = DiaryTasks.in(SOURCE.text, 0).get(0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EditorState.appendTask(
                PATH, new Markdown(SOURCE.text.replace("Routine", "External")), TEMPLATE, old));
    var code = new Markdown("## Факты\n```text\n- [ ] #task/dairy/fact Example\n```\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EditorState.appendTask(
                PATH,
                code,
                TEMPLATE,
                new DiaryTasks.Task(
                    code.text.indexOf("- [ ]"), "- [ ] #task/dairy/fact Example", false)));
  }

  @Test
  public void pastedLineBreaksBecomeSemicolonSeparatedDetailsOnTheSameTaskLine() {
    var state = open(SOURCE, 0);
    state.edit("one\r\ntwo\rthree\nfour");
    assertEquals("one\ntwo\nthree\nfour", state.editableText());
    assertTrue(
        plan(state, SOURCE)
            .get(0)
            .after()
            .contains("; one; two; three; four ✅ 2001-02-03 ^stable-id"));
    assertEquals("plain", DiaryTasks.addition("plain"));
  }

  @Test
  public void choosingAnotherDestinationOrDayLeavesTheDirectTaskFormMode() {
    var state = open(SOURCE, 0);
    state.chooseTarget(new Markdown.Target("Другое", "## Другое", -1, "", Markdown.Kind.SECTION));
    assertFalse(state.taskAppend);
    state.edit("one\ntwo");
    assertEquals("one\ntwo", state.editableText());
    state = open(SOURCE, 0);
    state.chooseDay(DiaryLayout.path(DATE.minusDays(1)), "## Факты\n");
    assertFalse(state.taskAppend);
    assertEquals(Markdown.Kind.SECTION, state.kind);
    state = open(SOURCE, 0);
    state.chooseTarget(
        new Markdown.Target("Other", "## Факты", 0, "- [ ] #task/other Other", Markdown.Kind.TASK));
    assertFalse(state.taskAppend);
  }

  @Test
  public void linkedRootMetadataAndOtherTaskFamiliesRemainUntouched() {
    String task = "- [ ] #task/diary/fact Linked ^leaf";
    var doc =
        new Markdown(
            "## Факты\n\n[[other#^root|root]]\n"
                + task
                + "\n"
                + "> [!info]- Дополнения к событию\n"
                + "> ```dataviewjs\n"
                + "> script();\n"
                + "> ```\n\n"
                + "- [ ] #task/other Keep\n");
    var state = open(doc, 0);
    state.edit("detail");
    assertEquals(
        doc.text.replace(task, "- [x] #task/diary/fact Linked; detail ✅ 2001-02-03 ^leaf"),
        plan(state, doc).get(0).after());
  }
}
