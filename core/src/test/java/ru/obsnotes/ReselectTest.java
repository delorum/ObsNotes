package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ReselectTest {
  @Test
  public void wholePersonReplacementKeepsWholeBlockScopeWithoutHighlightingAnyLine() {
    var t = TaggedText.parse("#person/Лена пришла\nАня тоже");
    var next = t.replace(0, List.of("Аня"));
    assertEquals(t.clean, next.clean);
    assertEquals("Лена пришла\n#person/Аня тоже", next.render());
    assertTrue(LineMarks.of(next.clean, next.tags).isEmpty());
  }

  @Test
  public void replacingLegacyPartWithAlreadyAssignedIdDoesNotDuplicateIt() {
    var s = state("текст #crossPeople/FirstUnknown");
    s.replaceTag(0, List.of("First"), s.routingCatalog(TEMPLATE));
    assertEquals("текст #crossPeople/First", s.savedText());
    assertEquals(List.of("First"), s.crossIds());
  }

  @Test
  public void rootLinkReplacementReplacesOldLeadingLinkAndKeepsBody() {
    var s = state("[[day#^first|Старый]]\nтекст #crossOneLine/First");
    var m = new Markdown("## Заметки\n\n" + Events.root("новый ^second") + "\n");
    s.attach(new Events.Root("day.md", m.blocks.get(0), "second"));
    assertFalse(s.text.contains("#^first"));
    assertTrue(s.text.contains("#^second"));
    assertEquals("текст", s.editableText());
    assertTrue(s.savedText().contains("#crossOneLine/First"));
  }

  private static final Markdown TEMPLATE =
      new Markdown(
          "## Заметки\n\n"
              + "## Один\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"First,FirstLong\"});\n"
              + "```\n\n"
              + "## Два\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Second\"});\n"
              + "```\n\n"
              + "## Три\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Third\"});\n"
              + "```\n\n"
              + "## Лена\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Лена\"});\n"
              + "```\n\n"
              + "## Аня\n"
              + "```dataviewjs\n"
              + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Аня\"});\n"
              + "```\n");

  private static EditorState state(String raw) {
    var s = new EditorState();
    s.destinationSnapshot = TEMPLATE.text;
    s.text = raw;
    s.initializeRoutes(TEMPLATE);
    return s;
  }

  private static Routing.Choice choice(Routing.Catalog c, String id) {
    return c.choices.stream().filter(x -> x.crossId().equals(id)).findFirst().orElseThrow();
  }

  @Test
  public void primaryReplacementRetainsExtraOrderAndReencodesOnlyExtraDestinations() {
    var s = state("текст");
    var c = s.routingCatalog(TEMPLATE);
    s.applyRoutes(List.of(choice(c, "First"), choice(c, "Second")), c);
    s.replaceRoute(0, choice(c, "Third"), c);
    assertEquals("## Три", s.heading);
    assertEquals(List.of("Second"), s.crossIds());
    assertEquals("текст #crossPeople/Second", s.savedText());
  }

  @Test
  public void extraReplacementKeepsPrimaryAndOtherExtraPositions() {
    var s = state("текст");
    var c = s.routingCatalog(TEMPLATE);
    s.applyRoutes(List.of(choice(c, "First"), choice(c, "Second"), choice(c, "Third")), c);
    s.replaceRoute(1, choice(c, "Лена"), c);
    assertEquals("## Один", s.heading);
    assertEquals(List.of("Лена", "Third"), s.crossIds());
    assertTrue(s.savedText().contains("#crossPeople/ЛенаThird"));
  }

  @Test
  public void duplicateReplacementRejectsWithoutChangingState() {
    var s = state("текст");
    var c = s.routingCatalog(TEMPLATE);
    s.applyRoutes(List.of(choice(c, "First"), choice(c, "Second")), c);
    String before = s.text;
    assertNotNull(s.validateRoutes(s.replacingRoute(0, choice(c, "Second"))));
    assertThrows(IllegalArgumentException.class, () -> s.replaceRoute(0, choice(c, "Second"), c));
    assertEquals(before, s.text);
    assertEquals("## Один", s.heading);
  }

  @Test
  public void crossOneLineReplacementKeepsLineColorAndAllOtherAssignments() {
    var t =
        TaggedText.parse(
            "одна #crossPeople/First\nдругая #crossOneLine/Second\nтретья #crossOneLine/First");
    var before = LineMarks.of(t.clean, t.tags);
    var next = t.replace(1, List.of("Third", "Лена"));
    assertEquals(t.clean, next.clean);
    assertEquals(before, LineMarks.of(next.clean, next.tags));
    assertEquals(
        "одна #crossPeople/First\nдругая #crossOneLine/ThirdЛена\nтретья #crossOneLine/First",
        next.render());
  }

  @Test
  public void replacingPersonUsesExistingNewNameWithoutRenamingOriginalText() {
    var t = TaggedText.parse("видел #personOneLine/Лена и Аня\nАня пришла");
    var next = t.replace(0, List.of("Аня"));
    assertEquals(t.clean, next.clean);
    assertEquals("видел Лена и #personOneLine/Аня\nАня пришла", next.render());
    assertEquals(1, next.tags.get(0).line(next.clean));
  }

  @Test
  public void absentPersonIsAddedOnOriginalLineAndLaterTagsRemainAttached() {
    var t = TaggedText.parse("видел #personOneLine/Лена\nниже #crossOneLine/First");
    var next = t.replace(0, List.of("Аня"));
    assertEquals("видел Лена #personOneLine/Аня\nниже #crossOneLine/First", next.render());
    assertEquals(
        2,
        next.tags.stream()
            .filter(x -> x.kind().equals("crossOneLine"))
            .findFirst()
            .orElseThrow()
            .line(next.clean));
  }

  @Test
  public void replacementPreservesHiddenRootAndOriginalLineRegardlessOfCursor() {
    var s = state(Events.root("раз\nдва #crossOneLine/First\nтри"));
    s.cursor = 0;
    String id = Events.id(s.text);
    s.replaceTag(0, List.of("Second"), s.routingCatalog(TEMPLATE));
    assertEquals("раз\nдва\nтри", s.editableText());
    assertTrue(s.savedText().contains("два #crossOneLine/Second\nтри ^" + id));
    assertTrue(s.savedText().contains("outLinkInfo"));
  }

  @Test
  public void legacyWholeTagReplacementPreservesManagedIdsAndUpdatesDisplayedRoutes() {
    var s = state("текст #crossPeople/FirstUnknown");
    var c = s.routingCatalog(TEMPLATE);
    s.replaceTag(0, List.of("Second"), c);
    assertEquals(List.of("First", "Second"), s.crossIds());
    assertEquals("текст #crossPeople/FirstSecond", s.savedText());
    assertTrue(s.tags().stream().allMatch(t -> s.unmanagedCrossIds(t, c).isEmpty()));
  }

  @Test
  public void tagChoicesPreselectAliasesByLongestMatchAndRetainUnknownIds() {
    var choices = TagChoices.of(TEMPLATE, false, "SecondFirstLongOld7");
    assertEquals(
        List.of("Second", "FirstLong", "Old7"),
        choices.selected().stream().map(CrossReferences.Destination::id).toList());
    assertTrue(choices.items().contains(choices.selected().get(2)));
    assertEquals(
        List.of("Аня", "Лена"),
        TagChoices.of(TEMPLATE, true, "АняЛена").selected().stream()
            .map(CrossReferences.Destination::id)
            .toList());
  }

  @Test
  public void emptyReplacementRemovesOnlySelectedTagAndQuickChoiceDoesNotMutateStagedState() {
    var t = TaggedText.parse("a #crossPeople/First\nb #crossOneLine/Second");
    assertEquals("a #crossPeople/First\nb", t.replace(1, List.of()).render());
    var selection = new PickerSelection<>(List.of("First", "Second"));
    assertEquals(List.of("Third"), selection.quick("Third", true));
    assertEquals(List.of("First", "Second", "Third"), selection.quick("Third", false));
    assertEquals(List.of("First", "Second"), selection.selected());
  }

  @Test
  public void valueOnlyFieldsKeepTheirKeysWhenLineAssignmentIsChanged() {
    var s = state("Поле:: значение #crossOneLine/First\nДругое:: ещё");
    s.fresh = false;
    s.routesInitialized = false;
    s.initializeRoutes(TEMPLATE);
    s.replaceTag(0, List.of("Second"), s.routingCatalog(TEMPLATE));
    assertEquals("значение\nещё", s.editableText());
    assertEquals("Поле:: значение #crossOneLine/Second\nДругое:: ещё", s.savedText());
  }
}
