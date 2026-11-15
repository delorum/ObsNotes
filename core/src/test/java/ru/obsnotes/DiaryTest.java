package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class DiaryTest {
  static final String TEMPLATE =
      "## Заметки\n\n"
          + "## Утро\n\n"
          + "```dataviewjs\n"
          + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Утро\", heading: \"##"
          + " Утро\"});\n"
          + "```\n\n"
          + "## Лена\n\n"
          + "```dataviewjs\n"
          + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Лена\", heading: \"##"
          + " Лена\"});\n"
          + "```\n\n"
          + "## Аня\n\n"
          + "```dataviewjs\n"
          + "await dv.view(\"Скрипты/Dataview/crossPeople\", {person: \"Аня,Дети\", heading: \"##"
          + " Аня\", include_sections: [\"## Дети вместе\"]});\n"
          + "```\n\n"
          + "## Дети вместе\n\n"
          + "## Погода\n\n"
          + "Погода:: Заполни погоду!\n";
  static final Markdown CATALOG = new Markdown(TEMPLATE);

  @Test
  public void calendarMatchesRussianMomentAndIsoBoundary() {
    assertEquals(
        "Дневник/Хроника/2026/4/11-ноябрь/2026-46-неделя/2026-11-15-вс.md",
        DiaryLayout.path(LocalDate.of(2026, 11, 15)));
    assertTrue(DiaryLayout.path(LocalDate.of(2021, 1, 1)).contains("2020-53-неделя"));
    assertTrue(DiaryLayout.path(LocalDate.of(2024, 12, 30)).contains("2025-1-неделя"));
  }

  @Test
  public void parsesTemplateStructureWithoutRunningTemplater() {
    Markdown m =
        TemplateCatalog.parse(
            "<%*\nconst x = 3;\n%>\n## Заметки\n<%*if (a) {-%>\n## День\n<%*}-%>");
    assertEquals(2, m.sections.size());
    assertFalse(m.text.contains("<%"));
  }

  @Test
  public void fencesAndTimestampsDoNotBecomeBlocksOrSections() {
    Markdown m =
        new Markdown(
            "---\n"
                + "title: test\n"
                + "---\n"
                + "## Заметки\n\n"
                + "2026-11-15 12:00:00\n"
                + "Первый\n"
                + "Продолжение\n\n"
                + "Второй\n\n"
                + "```dataviewjs\n"
                + "## fake\n"
                + "x();\n"
                + "```\n\n"
                + "### Другое\n"
                + "Третий\n");
    assertEquals(2, m.sections.size());
    assertEquals(3, m.blocks.size());
    assertEquals("Первый\nПродолжение", m.blocks.get(0).text());
  }

  @Test
  public void editingPreservesCrLfFrontmatterAndUntouchedContent() {
    String text =
        "---\r\n"
            + "key: value\r\n"
            + "---\r\n"
            + "## Заметки\r\n\r\n"
            + "текст\r\n\r\n"
            + "## Утро\r\n\r\n"
            + "```dataviewjs\r\n"
            + "foo()\r\n"
            + "```\r\n";
    Markdown m = new Markdown(text);
    assertEquals(
        text.replace("текст", "новый\r\nтекст"), m.replace(m.blocks.get(0), "новый\nтекст").text);
  }

  @Test
  public void timestampIsSharedUntilMoreThanTenWholeMinutes() {
    Markdown m = new Markdown("## Заметки\n\n2026-11-15 10:00:00\nпервый\n\nвторой\n");
    assertEquals("", m.timestamp(LocalDateTime.of(2026, 11, 15, 10, 10, 59)));
    assertEquals("2026-11-15 10:11:00\n", m.timestamp(LocalDateTime.of(2026, 11, 15, 10, 11)));
  }

  @Test
  public void restoresCleanedHeadingAndItsCrossPeopleViewInTemplateOrder() {
    Markdown m = new Markdown("## Заметки\n\nзаметка\n\n## Погода\n\nясно\n");
    Markdown result = m.append("## Утро", "прогулка", CATALOG);
    assertTrue(result.text.indexOf("## Утро") < result.text.indexOf("## Погода"));
    assertTrue(result.text.contains("person: \"Утро\""));
    assertTrue(result.text.contains("прогулка"));
  }

  @Test
  public void rejectsAmbiguousDuplicateHeadings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Markdown("## Заметки\nx\n## Заметки\ny").append("## Заметки", "a", CATALOG));
  }

  @Test
  public void taskCompletionPreservesDoneDateAndAvoidsDuplicateNotes() {
    Markdown m = new Markdown("## Утро\n\n- [ ] #task/car Заправился\n");
    Markdown.Target t =
        m.targets(CATALOG).stream()
            .filter(a -> a.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    Markdown result = m.insert(t, "2000р", LocalDate.of(2026, 11, 15), CATALOG);
    assertTrue(result.text.contains("- [x] #task/car Заправился; 2000р ✅ 2026-11-15"));
    t =
        result.targets(CATALOG).stream()
            .filter(a -> a.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    assertEquals(result.text, result.insert(t, "2000р", LocalDate.of(2026, 11, 16), CATALOG).text);
  }

  @Test
  public void fieldReplacesPromptAndThenAppends() {
    Markdown m = new Markdown("## Погода\n\nПогода:: Заполни погоду!\n");
    Markdown.Target t =
        m.targets(CATALOG).stream()
            .filter(a -> a.kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    m = m.insert(t, "снег", LocalDate.now(), CATALOG);
    assertTrue(m.text.contains("Погода:: снег"));
    t =
        m.targets(CATALOG).stream()
            .filter(a -> a.kind() == Markdown.Kind.FIELD)
            .findFirst()
            .orElseThrow();
    assertTrue(
        m.insert(t, "ветер", LocalDate.now(), CATALOG).text.contains("Погода:: снег; ветер"));
  }

  @Test
  public void taskRejectsMultilineBlock() {
    Markdown m = new Markdown("## Утро\n- [ ] test\n");
    var target =
        m.targets(CATALOG).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> m.insert(target, "one\ntwo", LocalDate.now(), CATALOG));
  }

  @Test
  public void crossTagsConcatenateIdsAndPreserveTrailingBlockId() {
    assertEquals(
        "привет #crossPeople/ЛенаУтро ^event-abc",
        CrossReferences.tag("привет ^event-abc", 0, List.of("Лена", "Утро"), false, false));
    assertEquals(
        "раз\nдва #personOneLine/Лена\nтри",
        CrossReferences.tag("раз\nдва\nтри", 5, List.of("Лена"), true, true));
  }

  @Test
  public void projectionsChooseOneLinePerDestinationAndWholeBlockForOthers() {
    Markdown m =
        new Markdown("## Заметки\n\nстрока #crossPeople/Лена\nдругая #crossOneLine/Утро\nтретья\n");
    assertEquals("другая", CrossReferences.project(m, CATALOG, "## Утро").get(0).text());
    assertTrue(CrossReferences.project(m, CATALOG, "## Лена").get(0).text().contains("третья"));
  }

  @Test
  public void personPrefixKeepsNameAndIncludedSectionsAppear() {
    Markdown m = new Markdown("## Заметки\n\n#person/Лена пришла\n\n## Дети вместе\n\nгуляли\n");
    assertEquals("Лена пришла", CrossReferences.project(m, CATALOG, "## Лена").get(0).text());
    assertEquals("гуляли", CrossReferences.project(m, CATALOG, "## Аня").get(0).text());
  }

  @Test
  public void ownSectionAndDescendantsDoNotDuplicateIntoProjection() {
    Markdown m =
        new Markdown("## Лена\n\nдело #crossPeople/Лена\n\n### Здоровье\nещё #crossPeople/Лена\n");
    assertTrue(CrossReferences.project(m, CATALOG, "## Лена").isEmpty());
  }

  @Test
  public void rootCalloutAndLinksAreCompatibleAndRootIsIdempotent() {
    String text = Events.root("событие\nпродолжение");
    String id = Events.id(text);
    assertTrue(id.startsWith("event-"));
    assertEquals(text, Events.root(text));
    assertTrue(text.contains("Скрипты/Dataview/outLinkInfo"));
    Markdown m = new Markdown("## Утро\n" + text + "\n");
    assertEquals(1, m.blocks.size());
    Events.Root root = new Events.Root("Дневник/day.md", m.blocks.get(0), id);
    String link = Events.link("дополнение", root);
    assertTrue(Events.references(link, "Дневник/day.md", id));
    assertFalse(Events.references(link, "Дневник/other.md", id));
  }

  @Test
  public void movePlansDestinationFirstAndCleansOrphanTimestamp() {
    Markdown from = new Markdown("## Заметки\n\n2026-11-15 10:00:00\nперенести\n\nоставить\n");
    Markdown to = new Markdown("## Заметки\n\nпрошлое\n");
    EditorState s = editing(from);
    s.destinationPath = "past.md";
    s.heading = "## Утро";
    var changes =
        VaultRepository.plan(s, from, to, CATALOG, LocalDate.of(2026, 11, 14), LocalDateTime.now());
    assertEquals("past.md", changes.get(0).path());
    assertTrue(changes.get(0).after().contains("перенести"));
    assertEquals("## Заметки\n\nоставить\n", changes.get(1).after());
  }

  @Test
  public void sameFileMoveToTaskAdjustsDestinationOffsetAfterRemoval() {
    Markdown m = new Markdown("## Заметки\n\nперенести\n\n## Утро\n\n- [ ] test\n");
    EditorState s = editing(m);
    var t =
        m.targets(CATALOG).stream()
            .filter(a -> a.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow();
    s.heading = t.heading();
    s.kind = t.kind();
    s.targetLine = t.line();
    s.targetSource = t.source();
    String result =
        VaultRepository.plan(s, m, m, CATALOG, LocalDate.of(2026, 11, 15), LocalDateTime.now())
            .get(0)
            .after();
    assertTrue(result.contains("- [x] test; перенести ✅ 2026-11-15"));
  }

  @Test
  public void linkedBlocksCannotMoveAcrossDays() {
    Markdown m = new Markdown("## Заметки\n\n" + Events.root("событие") + "\n");
    EditorState s = editing(m);
    s.destinationPath = "past.md";
    assertThrows(
        IllegalArgumentException.class,
        () -> VaultRepository.plan(s, m, m, CATALOG, LocalDate.now(), LocalDateTime.now()));
  }

  @Test
  public void restoringNestedHeadingRestoresParentsWithoutTemplateTasks() {
    Markdown template =
        new Markdown(
            "## Заметки\n\n"
                + "## Семья\n\n"
                + "- [ ] шаблонная задача\n\n"
                + "### Здоровье\n\n"
                + "#### Лекарства\n\n"
                + "Поле:: Заполни!\n\n"
                + "## Погода\n");
    Markdown m =
        new Markdown("## Заметки\n\n## Погода\n").append("#### Лекарства", "принял", template);
    assertTrue(m.text.contains("## Семья\n\n### Здоровье\n\n#### Лекарства"));
    assertFalse(m.text.contains("шаблонная"));
    assertFalse(m.text.contains("Поле::"));
  }

  @Test
  public void userCodeFencesRemainEditableIncludingBlankLinesAndFakeHeadings() {
    String block = "пример\n````java\n## это код\n\n```\nx();\n````\nконец";
    Markdown m = new Markdown("## Заметки\n\n" + block + "\n\n## Утро\n");
    assertEquals(2, m.sections.size());
    assertEquals(block, m.blocks.get(0).text());
    assertEquals(m.text, m.replace(m.blocks.get(0), block).text);
  }

  @Test
  public void tagsOnRootGoBeforeAnchorAndCallout() {
    String root = Events.root("корень");
    String result = CrossReferences.tag(root, 0, List.of("Утро"), false, false);
    assertTrue(result.startsWith("корень #crossPeople/Утро ^event-"));
    assertEquals(Events.id(root), Events.id(result));
    assertEquals("> цитата", CrossReferences.clean("> цитата"));
  }

  @Test
  public void personTagPrefixesExistingName() {
    assertEquals(
        "видел #personOneLine/Лена сегодня",
        CrossReferences.tag("видел Лена сегодня", 8, List.of("Лена"), true, true));
  }

  @Test
  public void selectingOrdinaryRootAndLinkingAreOneTransaction() {
    Markdown m = new Markdown("## Заметки\n\nперенести\n\n## Утро\n\nкорень\n");
    EditorState s = editing(m);
    var root = m.blocks.get(1);
    s.rootPath = s.sourcePath;
    s.rootOriginal = root.text();
    s.rootReplacement = Events.root(root.text());
    s.rootSnapshot = m.text;
    s.text = Events.link(s.text, new Events.Root(s.sourcePath, root, Events.id(s.rootReplacement)));
    var changes = VaultRepository.plan(s, m, m, CATALOG, LocalDate.now(), LocalDateTime.now());
    assertEquals(1, changes.size());
    String result = changes.get(0).after();
    assertTrue(result.contains("#^" + Events.id(s.rootReplacement)));
    assertTrue(result.contains("outLinkInfo"));
  }

  @Test
  public void emptyEventIsRejectedAndExistingAnchorGetsCallout() {
    assertThrows(IllegalArgumentException.class, () -> Events.root(" "));
    String result = Events.root("событие ^old-id");
    assertEquals("old-id", Events.id(result));
    assertTrue(result.contains("outLinkInfo\", \"old-id\""));
  }

  @Test
  public void editingRootHidesGeneratedMetadataAndRetainsStableAnchor() {
    EditorState s = new EditorState();
    s.text = Events.root("событие");
    String id = Events.id(s.text);
    assertEquals("событие", s.editableText());
    s.edit("новый текст #crossPeople/Утро");
    assertEquals(id, Events.id(s.text));
    assertTrue(s.text.contains("outLinkInfo"));
    assertEquals("новый текст", s.editableText());
  }

  static EditorState editing(Markdown doc) {
    EditorState state = new EditorState();
    Markdown.Block b = doc.blocks.get(0);
    state.fresh = false;
    state.start = b.start();
    state.end = b.end();
    state.original = b.text();
    state.text = b.text();
    state.sourcePath = "today.md";
    state.destinationPath = "today.md";
    return state;
  }
}
