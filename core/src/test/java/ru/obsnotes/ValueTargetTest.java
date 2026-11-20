package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class ValueTargetTest {
  private static final LocalDate DATE = LocalDate.of(2001, 2, 3);
  private static final String PATH = DiaryLayout.path(DATE);
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n"
              + "## Факты\n"
              + "- [ ] #task/diary/fact Routine\n"
              + "## Поля\n"
              + "Metric::\n"
              + "Other::\n"
              + "## Работа\n");

  private EditorState task(Markdown doc, int index) {
    var s = EditorState.editTask(PATH, doc, TEMPLATE, DiaryTasks.in(doc.text, 0).get(index));
    s.enableParts(TEMPLATE);
    return s;
  }

  private String save(EditorState s, Markdown source) {
    var changes = VaultRepository.plan(s, source, source, TEMPLATE, DATE, DATE.atTime(12, 0));
    return changes.isEmpty() ? source.text : changes.get(0).after();
  }

  private Routing.Choice choice(EditorState form, Markdown.Kind kind, String name) {
    return form.routingCatalog(TEMPLATE).choices.stream()
        .filter(c -> c.target().kind() == kind && c.label().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private EditorState field(Markdown doc) {
    var s =
        EditorState.editing(
            PATH,
            doc,
            TEMPLATE,
            doc.blocks.stream()
                .filter(b -> b.text().startsWith("Metric::"))
                .findFirst()
                .orElseThrow());
    s.enableParts(TEMPLATE);
    return s;
  }

  @Test
  public void taskOpensStoredValueAndNoopOrRestoredTextDoesNotWrite() {
    var doc =
        new Markdown(
            "## Факты\n- [x] #task/dairy/fact Routine; old; detail ✅ 1999-01-01 ^stable\n");
    var s = task(doc, 0);
    assertEquals("old; detail", s.editableText());
    assertEquals("Routine", s.activePart().valueLabel());
    assertEquals(doc.text, save(s, doc));
    s.edit("changed");
    s.edit("old; detail");
    assertTrue(EditorRevision.unchanged(s, TEMPLATE));
    assertEquals(doc.text, save(s, doc));
  }

  @Test
  public void taskValueCanBeReplacedThenRemovedWithoutLosingTitleStatusTagOrAnchor() {
    var doc = new Markdown("## Факты\n- [x] #task/dairy/fact Routine; old ✅ 1999-01-01 ^stable\n");
    var s = task(doc, 0);
    s.edit("new\nvalue");
    String replaced = save(s, doc);
    assertEquals(doc.text.replace("old ✅ 1999-01-01", "new; value ✅ 2001-02-03"), replaced);
    var updated = new Markdown(replaced);
    s = task(updated, 0);
    s.edit("");
    assertEquals(
        "## Факты\n- [x] #task/dairy/fact Routine ✅ 2001-02-03 ^stable\n", save(s, updated));
  }

  @Test
  public void duplicateTasksUseTheirExactLineAndKeepCrLfAndSiblings() {
    String line = "- [ ] #task/diary/fact Routine";
    var doc = new Markdown("## Факты\r\nHint\r\n" + line + "\r\n" + line + "\r\nTail\r\n");
    var s = task(doc, 1);
    s.edit("detail");
    assertEquals(
        doc.text.substring(0, s.start)
            + "- [x] #task/diary/fact Routine; detail ✅ 2001-02-03"
            + doc.text.substring(s.end),
        save(s, doc));
  }

  @Test
  public void taskValueSupportsIndependentPartsAndRecoveryCopies() {
    var doc = new Markdown("## Заметки\n## Факты\n- [ ] #task/diary/fact Routine\n## Работа\n");
    var s = task(doc, 0);
    s.edit("detail\n\nSide");
    assertEquals(2, s.parts.parts.size());
    assertTrue(s.parts.parts.get(0).form().valueBound());
    assertFalse(s.parts.parts.get(1).form().valueBound());
    var side = s.parts.parts.get(1).form();
    side.applyRoutes(
        List.of(choice(side, Markdown.Kind.SECTION, "Работа")), side.routingCatalog(TEMPLATE));
    String after = save(s, doc);
    assertTrue(after.contains("Routine; detail ✅ 2001-02-03"));
    assertTrue(after.contains("## Работа\n\nSide"));
    assertEquals(
        s.parts.parts.get(0).form().taskShell, s.parts.parts.get(0).form().copy().taskShell);
  }

  @Test
  public void fieldBadgeHasSeparatorAndDetachesIntoOrdinaryTextWithoutDeletingSiblingKey() {
    var doc = new Markdown("## Поля\nMetric:: old\nOther:: keep\n");
    var s = field(doc);
    assertEquals("Metric::", s.parts.parts.get(0).form().valueLabel());
    s.parts.parts.get(0).form().removeValueTarget();
    String after = save(s, doc);
    assertTrue(after.contains("old\n\nOther:: keep"));
    assertFalse(after.contains("Metric::"));
  }

  @Test
  public void valueCanDetachAndMoveToAnotherFieldOrTask() {
    var doc =
        new Markdown(
            "## Поля\nMetric:: old\n\nOther:: keep\n## Факты\n- [ ] #task/diary/fact Routine\n");
    for (var kind : List.of(Markdown.Kind.FIELD, Markdown.Kind.TASK)) {
      var s = field(doc);
      var form = s.activePart();
      form.removeValueTarget();
      var target = choice(form, kind, kind == Markdown.Kind.FIELD ? "Other" : "Routine");
      form.applyRoutes(List.of(target), form.routingCatalog(TEMPLATE));
      String after = save(s, doc);
      assertFalse(after.contains("Metric::"));
      assertTrue(
          after.contains(
              kind == Markdown.Kind.FIELD ? "Other:: keep; old" : "Routine; old ✅ 2001-02-03"));
    }
  }

  @Test
  public void taskBadgeCanDetachAndLeaveOnlyItsValueInThePhysicalSection() {
    var doc = new Markdown("## Факты\n- [x] #task/diary/fact Routine; detail ✅ 2001-02-03\n");
    var s = task(doc, 0);
    s.activePart().removeValueTarget();
    String after = save(s, doc);
    assertEquals("## Факты\ndetail\n", after);
  }

  @Test
  public void staleOrLiteralTasksAreRejectedBeforeEditing() {
    var doc = new Markdown("## Факты\n- [ ] #task/diary/fact Routine\n");
    var old = DiaryTasks.in(doc.text, 0).get(0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EditorState.editTask(
                PATH, new Markdown(doc.text.replace("Routine", "Changed")), TEMPLATE, old));
    var code = new Markdown("## Факты\n```text\n- [ ] #task/diary/fact Routine\n```\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EditorState.editTask(
                PATH,
                code,
                TEMPLATE,
                new DiaryTasks.Task(code.text.indexOf("- [ ]"), old.source(), false)));
  }

  @Test
  public void metadataBeforeTheValueIsPreservedWhenEditing() {
    String line = "- [ ] #task/diary/fact Routine #crossPeople/Extra ^stable";
    var doc = new Markdown("## Факты\n" + line + "\n");
    var s = task(doc, 0);
    s.edit("detail");
    assertTrue(save(s, doc).contains("Routine #crossPeople/Extra; detail ✅ 2001-02-03 ^stable"));
  }

  @Test
  public void detachedTaskPreservesItsExistingAnchorAndEmptyValueCanHaveIndependentParts() {
    var doc = new Markdown("## Факты\n- [ ] #task/diary/fact Routine ^stable\n");
    var s = task(doc, 0);
    s.edit("\n\nSide");
    assertEquals(2, s.parts.parts.size());
    assertTrue(s.parts.parts.get(0).form().valueBound());
    assertEquals("", s.parts.parts.get(0).form().editableText());
    s = task(doc, 0);
    s.edit("detail");
    s.activePart().removeValueTarget();
    assertEquals("## Факты\ndetail ^stable\n", save(s, doc));
  }

  @Test
  public void detachedFieldIsNoLongerEnumeratedAsAProtectedWeatherOrValueSlot() {
    var doc = new Markdown("## Поля\nMetric:: old\nOther:: keep\n");
    var s = field(doc);
    s.parts.parts.get(0).form().removeValueTarget();
    assertEquals(1, s.fieldCount());
    assertEquals("Other", s.fieldName(0));
    assertEquals("keep", s.fieldValue(0));
  }

  @Test
  public void protectedValueCanRetargetEvenAfterAllSectionBadgesHaveBeenRemoved() {
    var doc = new Markdown("## Поля\nMetric:: old\n## Работа\n");
    var s = field(doc);
    var form = s.activePart();
    var catalog = form.routingCatalog(TEMPLATE);
    form.removeRoute(0, catalog);
    assertTrue(form.routes.isEmpty());
    form.removeValueTarget();
    form.replaceRoute(0, choice(form, Markdown.Kind.SECTION, "Работа"), catalog);
    assertTrue(save(s, doc).contains("## Работа\n\nold"));
  }

  @Test
  public void existingTaskAnchorRemainsOneAnchorWhenItsValueBecomesARoot() {
    var doc = new Markdown("## Факты\n- [ ] #task/diary/fact Routine; old ^stable\n");
    var s = task(doc, 0);
    assertEquals("old", s.editableText());
    s.parts.makeRoot(0);
    String after = save(s, doc);
    assertEquals(1, after.split("\\^stable", -1).length - 1);
    assertTrue(after.contains("outLinkInfo"));
    assertEquals("stable", Events.rootId(new Markdown(after).blocks.get(0).text()));
  }

  @Test
  public void reopeningRootedTaskValueKeepsItsCalloutLeadingLinksAndRootRole() {
    String row = "- [x] #task/diary/fact Routine; old ✅ 2001-02-03 ^stable";
    String block = "[[other#^root|Parent]]\n" + Events.root(row);
    var doc = new Markdown("## Факты\n\n" + block + "\n");
    var s = task(doc, 0);
    assertEquals("old", s.editableText());
    assertTrue(s.parts.isRoot(0));
    assertEquals(1, s.parts.references(0).size());
    assertEquals(doc.text, save(s, doc));
    s.edit("new");
    assertEquals(doc.text.replace("; old", "; new"), save(s, doc));
    s.activePart().removeValueTarget();
    String detached = save(s, doc);
    assertTrue(detached.contains("[[other#^root|Parent]]"));
    assertTrue(detached.contains("new ^stable"));
    assertTrue(detached.contains("outLinkInfo"));
    assertFalse(detached.contains("#task/diary"));
  }
}
