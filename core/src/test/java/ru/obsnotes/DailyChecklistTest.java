package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class DailyChecklistTest {
  private static final DailyChecklist.Context EMPTY =
      new DailyChecklist.Context(1000L, 2000L, false, null, null);

  private List<DailyChecklist.Row> rows(String source) {
    return DailyChecklist.rows(new Markdown(source), EMPTY);
  }

  private DailyChecklist.Row row(List<DailyChecklist.Row> rows, String name) {
    return rows.stream().filter(r -> r.label().equals(name)).findFirst().orElseThrow();
  }

  private boolean ok(List<DailyChecklist.Row> rows, String name) {
    return row(rows, name).status().get(0).ok();
  }

  @Test
  public void tableHasAllFifteenSectionsAndEmptyValuesAreNotComplete() {
    var rows = rows("## Заметки\n\n## Погода\nПогода:: Заполни погоду!\nТемпература::\n");
    assertEquals(15, rows.size());
    assertTrue(ok(rows, "Заметки"));
    assertFalse(ok(rows, "Погода"));
    assertFalse(ok(rows, "Температура"));
    assertFalse(ok(rows, "Краткое описание дня"));
    assertFalse(ok(rows, "Местоположение"));
  }

  @Test
  public void everyInternalChecklistLinkResolvesIncludingNestedSportAndCar() {
    var doc =
        new Markdown(
            "## Заметки\n## Краткое описание дня\n## Основные события дня\n"
                + "## Местоположение\n## Погода\n## Настроение\n## Питание\n## Мама\n"
                + "## Короткая часть\n### Машина\n### Спорт и диета\n");
    var outline = DayOutline.sections(doc, doc);
    int linked = 0;
    for (var row : DailyChecklist.rows(doc, EMPTY)) {
      if (row.heading().isEmpty()) continue;
      linked++;
      assertNotNull(row.label(), DayOutline.resolveHeading(outline, row.heading()));
    }
    assertEquals(12, linked);
  }

  @Test
  public void populatedFieldsMoodPhotosAndCrossReferencesSetTheirStatuses() {
    var rows =
        rows(
            "## Заметки\n\n"
                + "событие #crossOneLine/Утро #person/Мама #crossPeople/ВечерПерекусы\n\n"
                + "## Данные\n"
                + "ShortDescr:: день\n"
                + "Погода:: ясно\n"
                + "Температура:: +5\n"
                + "ВоЧтоОдевалсяНаУлицу:: куртка\n"
                + "Местоположение:: дом\n"
                + "![[photo.webp]]\n\n"
                + "## Настроение\n"
                + "хорошо\n");
    assertFalse(ok(rows, "Заметки"));
    for (String name :
        List.of(
            "Краткое описание дня",
            "Погода",
            "Температура",
            "Местоположение",
            "Настроение",
            "Во что одевался",
            "Мама",
            "Фотографии")) assertTrue(name, ok(rows, name));
    assertTrue(row(rows, "Основные события дня").status().get(0).ok());
    assertFalse(row(rows, "Основные события дня").status().get(1).ok());
    assertTrue(row(rows, "Основные события дня").status().get(2).ok());
    assertTrue(row(rows, "Питание").status().get(3).ok());
  }

  @Test
  public void uncheckedTasksAndCrossPeopleScriptsDoNotFillNotesOrMood() {
    var rows =
        rows(
            "## Заметки\n\n"
                + "- [ ] #task/dairy/fact action\n\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\");\n"
                + "```\n\n"
                + "## Настроение\n\n"
                + "```dataviewjs\n"
                + "await dv.view(\"Скрипты/Dataview/crossPeople\");\n"
                + "```\n");
    assertTrue(ok(rows, "Заметки"));
    assertFalse(ok(rows, "Настроение"));
  }

  @Test
  public void carPairRequiresExactlyOneAnswerWhenBothTasksExist() {
    String base =
        "## Машина\n"
            + "- [ ] #task/dairy/car Ездил на машине\n"
            + "- [ ] #task/dairy/car Не ездил на машине\n";
    assertFalse(ok(rows(base), "Машина"));
    assertTrue(ok(rows(base.replaceFirst("\\[ \\]", "[x]")), "Машина"));
    assertFalse(ok(rows(base.replace("[ ]", "[x]")), "Машина"));
    assertTrue(ok(rows("## Машина\n- [ ] #task/dairy/car Ездил на машине\n"), "Машина"));
  }

  @Test
  public void dietFactNeedsCaloriesOnlyWhenDietWasFollowed() {
    String base =
        "## Спорт и диета\n"
            + "- [x] #task/diary/diet Соблюдал диету\n"
            + "- [ ] #task/diary/diet не соблюдал диету\n\n"
            + "СъеденоКалорий:: 0\n";
    assertFalse(ok(rows(base), "Спорт и диета"));
    assertTrue(ok(rows(base.replace(":: 0", ":: 1200")), "Спорт и диета"));
    assertTrue(
        ok(
            rows(
                base.replace("- [x]", "- [ ]")
                    .replace("- [ ] #task/diary/diet не", "- [x] #task/diary/diet не")),
            "Спорт и диета"));
  }

  private static class Storage implements VaultStorage {
    Map<String, String> files = new HashMap<>();
    List<String> reads = new ArrayList<>();

    public String read(String path) throws IOException {
      reads.add(path);
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      return files.get(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("Checklist must not write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Checklist must not scan");
    }

    public long modifiedMillis(String path) {
      return Instant.parse("2026-11-16T12:00:00Z").toEpochMilli();
    }
  }

  @Test
  public void auxiliaryDataUsesOnlyExactServiceAndWorkPathsAndWeekendMissingWorkIsOk()
      throws Exception {
    var storage = new Storage();
    LocalDate sunday = LocalDate.of(2026, 11, 15);
    var context = DailyChecklist.load(storage, DiaryLayout.path(sunday), sunday);
    assertEquals(
        List.of("Дневник/Служебная_информация.md", DailyChecklist.workPath(sunday)), storage.reads);
    assertTrue(context.workOk());
    assertEquals("Нет данных", context.financeError());
  }

  @Test
  public void weekdayWorkNeedsMainEventsAndFinanceStatusUsesTheLastRunTimestamp() throws Exception {
    var storage = new Storage();
    LocalDate day = LocalDate.of(2026, 11, 16);
    storage.files.put(DailyChecklist.workPath(day), "## Основные события\n\nсобытие\n\n## Идеи\n");
    storage.files.put(
        "Дневник/Служебная_информация.md",
        "ДатаПоследнегоЗапускаTOperations:: 2026-11-16T13:00:00Z\n");
    var context = DailyChecklist.load(storage, DiaryLayout.path(day), day);
    assertTrue(context.workOk());
    assertTrue(ok(DailyChecklist.rows(new Markdown("## Заметки\n"), context), "Доходы и расходы"));
    storage.files.put(
        "Дневник/Служебная_информация.md",
        "ДатаПоследнегоЗапускаTOperations:: 2026-11-16T10:00:00Z\n");
    var stale = DailyChecklist.load(storage, DiaryLayout.path(day), day);
    var rows = DailyChecklist.rows(new Markdown("## Заметки\n"), stale);
    assertFalse(ok(rows, "Доходы и расходы"));
    assertTrue(row(rows, "Доходы и расходы").status().get(0).text().contains("2 ч."));
  }

  @Test
  public void missingWeekdayWorkIsNotOkAndCodeExamplesDoNotFillFields() throws Exception {
    var storage = new Storage();
    LocalDate day = LocalDate.of(2026, 11, 16);
    assertFalse(DailyChecklist.load(storage, DiaryLayout.path(day), day).workOk());
    assertFalse(ok(rows("## Погода\n```text\nПогода:: fake\n```\n"), "Погода"));
  }

  @Test
  public void anyOfTheFourNumberedSnackSectionsCompletesSnacks() {
    for (int number = 1; number <= 4; number++) {
      var table = rows("## Питание\n### Перекус " + number + "\nяблоко\n### Ужин\n");
      assertTrue("Snack " + number, row(table, "Питание").status().get(3).ok());
    }
  }

  @Test
  public void emptySnackSectionsAndTheirSiblingHeadingsDoNotCompleteSnacks() {
    for (int number = 1; number <= 4; number++) {
      var table =
          rows("## Питание\n### Перекус " + number + "\n\n### Перекус 5\nother\n### Ужин\nmeal\n");
      assertFalse("Snack " + number, row(table, "Питание").status().get(3).ok());
    }
    assertFalse(
        row(
                rows(
                    "## Питание\n"
                        + "### Перекус 3\n"
                        + "- [ ] #task/diary Fact\n"
                        + "```dataviewjs\n"
                        + "await dv.view(\"Скрипты/Dataview/crossPeople\");\n"
                        + "```\n"),
                "Питание")
            .status()
            .get(3)
            .ok());
  }

  @Test
  public void nestedSnackHeadingAloneIsEmptyButItsContentCounts() {
    String base = "## Питание\n### Перекус 2\n#### Дополнения\n";
    assertFalse(row(rows(base), "Питание").status().get(3).ok());
    assertTrue(row(rows(base + "яблоко\n"), "Питание").status().get(3).ok());
  }
}
