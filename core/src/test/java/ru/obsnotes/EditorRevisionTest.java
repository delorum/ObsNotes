package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class EditorRevisionTest {
  private static final String PATH = DiaryLayout.path(LocalDate.of(2026, 11, 16));
  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Работа\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Work\"});\n"
              + "```\n\n"
              + "## Дом\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Home\"});\n"
              + "```\n");

  private EditorState edit(String body) {
    Markdown doc = new Markdown("## Заметки\n\n" + body + "\n\n## Работа\n\n");
    var s = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    s.enableParts(TEMPLATE);
    return s;
  }

  private boolean unchanged(EditorState s) {
    return EditorRevision.unchanged(s, TEMPLATE);
  }

  private List<VaultRepository.Change> plan(EditorState s) {
    return VaultRepository.plan(
        s,
        new Markdown(s.sourceSnapshot),
        new Markdown(s.destinationSnapshot),
        TEMPLATE,
        LocalDate.of(2026, 11, 16),
        LocalDateTime.of(2026, 11, 16, 15, 0));
  }

  @Test
  public void untouchedUncanonicalTagsAndWhitespaceNeverProduceAWritePlan() {
    var s = edit("Alpha  #crossPeople/Work  ");
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
    assertEquals("## Заметки\n\nAlpha  #crossPeople/Work  \n\n## Работа\n\n", s.sourceSnapshot);
    s.cursor = 0;
    s.parts.selected = 0;
    s.parts.parts.get(0).form().routeChosen = true;
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void typingThenRestoringTheOriginalTextDoesNotSave() {
    var s = edit("Alpha #crossPeople/Work");
    s.edit("changed");
    assertFalse(unchanged(s));
    s.edit("Alpha");
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void splittingAndRemovingExtraPartsBackToOriginalDoesNotSave() {
    var s = edit("Alpha");
    s.edit("Alpha\n\nBeta\n\nGamma");
    assertFalse(unchanged(s));
    s.edit("Alpha");
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void routeRoundTripAndEquivalentManagedCrossTagsDoNotRewriteSource() {
    var s = edit("Alpha #crossPeople/Work #crossPeople/Home");
    var f = s.activePart();
    var before = List.copyOf(f.routes);
    var catalog = f.routingCatalog(TEMPLATE);
    var work =
        catalog.choices.stream()
            .filter(c -> c.target().heading().equals("## Работа"))
            .findFirst()
            .orElseThrow();
    f.applyRoutes(List.of(work), catalog);
    s.parts.sync();
    assertFalse(unchanged(s));
    f.applyRoutes(before, catalog);
    s.parts.sync();
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void tagChangesAreDetectedEvenWithoutVisibleTextChangesAndCanBeReverted() {
    var s = edit("Alpha\nBeta #crossOneLine/Work");
    var f = s.activePart();
    f.removeTag(0);
    assertFalse(unchanged(s));
    f.addTags(0, List.of("Work"), true, false);
    assertFalse(unchanged(s));
    f.removeTag(0);
    f.addTags(f.editableText().length(), List.of("Work"), true, false);
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void wholePersonAndUnknownTagsAreNotLostByChangeDetection() {
    var s = edit("Лена #person/Лена #crossPeople/Unknown");
    var f = s.activePart();
    int unknown = 0;
    for (int i = 0; i < f.tags().size(); i++)
      if (f.tags().get(i).kind().equals("crossPeople")) unknown = i;
    f.removeTag(unknown);
    assertFalse(unchanged(s));
    f.addTags(0, List.of("Unknown"), false, false);
    assertTrue(unchanged(s));
  }

  @Test
  public void rootToggleRestoredToIndependentDoesNotSave() {
    var s = edit("Alpha");
    s.parts.makeRoot(0);
    assertFalse(unchanged(s));
    s.parts.independent(0);
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void choosingExistingRootAgainIsNotAChangeButDifferentParentIs() {
    String rootText = Events.root("Root");
    String id = Events.rootId(rootText);
    String other = DiaryLayout.path(LocalDate.of(2026, 11, 15));
    var s = edit("[[" + other.replace(".md", "") + "#^" + id + "|Custom alias]]\nAlpha");
    var doc = new Markdown("## Заметки\n" + rootText + "\n");
    var root = new Events.Root(other, doc.blocks.get(0), id);
    s.parts.attachExternal(0, root);
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
    s.parts.independent(0);
    assertFalse(unchanged(s));
    s.parts.attachExternal(0, root);
    assertTrue(unchanged(s));
  }

  @Test
  public void anExistingRootIsUnchangedWhenMarkedAsRootAgain() {
    var s = edit(Events.root("Alpha"));
    s.parts.makeRoot(0);
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void dateRoundTripLeavesNoSaveAndNoChangesToOtherFile() {
    var s = edit("Alpha #crossPeople/Home");
    s.chooseDay(DiaryLayout.path(LocalDate.of(2026, 11, 15)), TEMPLATE.text);
    assertFalse(unchanged(s));
    s.chooseDay(PATH, s.sourceSnapshot);
    for (var p : s.parts.parts) p.form().initializeRoutes(TEMPLATE);
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void fieldAndWeatherValuesReturnedToOriginalDoNotSave() {
    var source = new Markdown("## Погода\nПогода:: ясно\nТемпература:: +5\n");
    var s = EditorState.editing(PATH, source, TEMPLATE, source.blocks.get(0));
    s.editField(0, "дождь");
    assertFalse(unchanged(s));
    s.editField(0, "ясно");
    assertTrue(unchanged(s));
    var weather = EditorState.weather(PATH, source);
    weather.editField(1, "+7");
    assertFalse(unchanged(weather));
    weather.editField(1, "+5");
    assertTrue(unchanged(weather));
  }

  @Test
  public void taskAdditionTypedAndClearedIsNotCompleted() {
    var source = new Markdown("## Заметки\n- [ ] #task/diary Fact\n");
    var s = EditorState.appendTask(PATH, source, TEMPLATE, DiaryTasks.in(source.text, 0).get(0));
    s.edit("done");
    assertFalse(unchanged(s));
    s.edit("");
    assertTrue(unchanged(s));
  }

  @Test
  public void legacyRecoveryUsesOriginalSourceInsteadOfCurrentEditedText() {
    var s = edit("Alpha");
    s.initialRevision = "";
    s.edit("changed");
    assertFalse(unchanged(s));
    s.edit("Alpha");
    assertTrue(unchanged(s));
    assertTrue(s.initialRevision.startsWith("v1:"));
    assertFalse(s.initialRevision.contains("Alpha"));
  }

  @Test
  public void localGraphIdentityIsIgnoredButRelationshipChangesAreNot() {
    var s = edit("Alpha");
    s.edit("Alpha\n\nBeta\n\nGamma");
    s.parts.linkTo(1, 0);
    s.parts.linkTo(2, 1);
    EditorRevision.remember(s, TEMPLATE);
    s.parts.linkTo(2, 0);
    assertFalse(unchanged(s));
    s.parts.linkTo(2, 1);
    assertTrue(unchanged(s));
    s.parts.selected = 0;
    s.cursor = 0;
    assertTrue(unchanged(s));
  }

  @Test
  public void partLookupDoesNotChangePickerSelection() {
    var s = edit("Alpha");
    s.edit("Alpha\n\nBeta");
    s.parts.selected = 0;
    assertEquals(1, s.parts.partAt(s.editableText().length()));
    assertEquals(0, s.parts.selected);
  }

  @Test
  public void trailingSpacesSurviveHiddenTagEditingAndUndo() {
    var s = edit("Alpha  #crossPeople/Work  ");
    String visible = s.editableText();
    s.edit(visible + "changed");
    s.parts.sync();
    assertFalse(unchanged(s));
    s.edit(visible);
    s.parts.sync();
    assertEquals(visible, s.editableText());
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }

  @Test
  public void emptyNewContentDoesNotHideAnExplicitTaskOnlyAction() {
    var source = new Markdown("## Заметки\n- [ ] #task/diary Fact\n");
    var s = EditorState.newNote(PATH, source, TEMPLATE, "## Заметки");
    s.enableParts(TEMPLATE);
    var form = s.activePart();
    var catalog = form.routingCatalog(TEMPLATE);
    var target =
        catalog.choices.stream()
            .filter(c -> c.target().kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    form.applyRoutes(List.of(target), catalog);
    assertTrue(unchanged(s));
    form.appendTaskText = false;
    assertFalse(unchanged(s));
    assertFalse(plan(s).isEmpty());
  }

  @Test
  public void repairingTheExistingParentsCalloutIsARealChange() {
    var doc =
        new Markdown(
            "## Заметки\n[["
                + PATH.replace(".md", "")
                + "#^stable|Root]]\nAlpha\n\n## Корень\nRoot ^stable\n");
    var s = EditorState.editing(PATH, doc, TEMPLATE, doc.blocks.get(0));
    s.enableParts(TEMPLATE);
    s.parts.attachExternal(0, new Events.Root(PATH, doc.blocks.get(1), ""));
    assertFalse(unchanged(s));
    assertTrue(plan(s).get(0).after().contains("Дополнения к событию"));
  }

  @Test
  public void choosingADifferentLineAliasIsARealTagChange() {
    var s = edit("Alpha #crossOneLine/Work");
    var aliases =
        new Markdown(
            TEMPLATE.text.replace(
                "## Дом",
                "```dataviewjs\n"
                    + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Job\"});\n"
                    + "```\n"
                    + "## Дом"));
    EditorRevision.remember(s, aliases);
    var f = s.activePart();
    f.removeTag(0);
    f.addTags(0, List.of("Job"), true, false);
    assertFalse(EditorRevision.unchanged(s, aliases));
  }

  @Test
  public void addingAndRemovingLineTagKeepsTrailingSpacesAndRestoresInitialRevision() {
    var s = edit("Alpha  #crossPeople/Work  ");
    String original = s.editableText();
    var f = s.activePart();
    f.addTags(original.length(), List.of("Home"), true, false);
    s.parts.sync();
    assertEquals(original, s.editableText());
    assertFalse(unchanged(s));
    int line = -1;
    for (int i = 0; i < f.tags().size(); i++) if (f.tags().get(i).oneLine()) line = i;
    f.removeTag(line);
    s.parts.sync();
    assertEquals(original, s.editableText());
    assertTrue(unchanged(s));
    assertTrue(plan(s).isEmpty());
  }
}
