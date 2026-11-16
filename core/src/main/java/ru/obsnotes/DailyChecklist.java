package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** Native counterpart of dairyCheckList: daily content plus two exact auxiliary files. */
public final class DailyChecklist {
  private DailyChecklist() {}

  public record Part(String text, boolean ok) {}

  public record Row(String label, String heading, List<Part> status) {}

  public record Context(
      long modified, Long operations, boolean workOk, String workError, String financeError) {}

  public static String workPath(LocalDate date) {
    return DiaryLayout.path(date)
        .replace("Дневник/Хроника/", "Работа/Daily/Хроника/")
        .replaceFirst("\\.md$", "-work.md");
  }

  private static Long timestamp(String raw) {
    String value = raw.strip().replaceFirst("^date\\(\"?", "").replaceFirst("\"?\\)$", "");
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    try {
      return OffsetDateTime.parse(value).toInstant().toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    try {
      return LocalDateTime.parse(value.replace(' ', 'T'))
          .atZone(ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();
    } catch (DateTimeException ignored) {
    }
    try {
      return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    } catch (DateTimeException ignored) {
      return null;
    }
  }

  public static Context load(VaultStorage storage, String path, LocalDate date) throws IOException {
    long modified = storage.modifiedMillis(path);
    Long operations = null;
    String financeError = null, workError = null;
    boolean workOk = false;
    try {
      String service = storage.read("Дневник/Служебная_информация.md");
      var m =
          Pattern.compile("(?m)^ДатаПоследнегоЗапускаTOperations(?:::|:)[ \\t]*(.*)$")
              .matcher(service);
      if (m.find()) operations = timestamp(m.group(1));
      if (operations == null) financeError = "Нет даты";
    } catch (FileNotFoundException missing) {
      financeError = "Нет данных";
    } catch (IOException failure) {
      financeError = "Недоступно";
    }
    try {
      var work = new Markdown(storage.read(workPath(date)));
      var events = work.section("## Основные события");
      if (events != null)
        workOk =
            work.text
                .substring(events.body(), events.end())
                .lines()
                .anyMatch(line -> !line.isBlank());
    } catch (FileNotFoundException missing) {
      workOk = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    } catch (IOException failure) {
      workError = "Недоступно";
    }
    return new Context(modified, operations, workOk, workError, financeError);
  }

  private static Row row(String label, String heading, boolean ok) {
    return new Row(label, heading, List.of(new Part(ok ? "OK" : "NOT OK", ok)));
  }

  private static boolean filled(Map<String, List<String>> values, String key) {
    return values.getOrDefault(key, List.of()).stream()
        .anyMatch(v -> !v.isBlank() && !v.startsWith("Заполни"));
  }

  private static boolean tag(String line, String id) {
    var m =
        Pattern.compile("#(?:crossPeople|crossOneLine|person|personOneLine)/([\\p{L}]+)")
            .matcher(line);
    while (m.find())
      if (m.group(1).toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  private static boolean content(String line) {
    return !line.isEmpty()
        && !line.contains("В чеклист")
        && !line.contains("|Работа]]")
        && !line.contains("```")
        && !line.contains("Скрипты/Dataview/crossPeople")
        && !line.contains("- [ ] ");
  }

  private static boolean pair(
      Markdown doc,
      String group,
      String yes,
      String no,
      boolean diet,
      Map<String, List<String>> values) {
    var tasks = DiaryTasks.in(doc.text, 0);
    var a =
        tasks.stream()
            .filter(
                t ->
                    t.source()
                        .matches(
                            ".*#task/(?:dairy|diary)/"
                                + group
                                + "\\s+"
                                + Pattern.quote(yes)
                                + ".*"))
            .collect(java.util.stream.Collectors.toList());
    var b =
        tasks.stream()
            .filter(
                t ->
                    t.source()
                        .matches(
                            ".*#task/(?:dairy|diary)/" + group + "\\s+" + Pattern.quote(no) + ".*"))
            .collect(java.util.stream.Collectors.toList());
    if (diet ? a.isEmpty() && b.isEmpty() : a.isEmpty() || b.isEmpty()) return true;
    boolean x = a.stream().anyMatch(DiaryTasks.Task::checked),
        y = b.stream().anyMatch(DiaryTasks.Task::checked);
    if (x == y) return false;
    if (!diet || y) return true;
    var calories = values.get("СъеденоКалорий");
    return calories == null
        || calories.stream().anyMatch(v -> !v.isBlank() && !v.matches("[+-]?0+(?:[.,]0+)?"));
  }

  public static List<Row> rows(Markdown doc, Context context) {
    Map<String, List<String>> fields = new HashMap<>();
    for (var f : InlineFields.all(doc))
      fields.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(f.value().strip());
    Set<String> completed = new HashSet<>();
    String section = "";
    int notes = 0, photos = 0;
    Map<String, String> headings =
        Map.ofEntries(
            Map.entry("## Заметки", "notes"),
            Map.entry("### Что было с утра?", "Утро"),
            Map.entry("### Что было днем?", "День"),
            Map.entry("### Что было вечером?", "Вечер"),
            Map.entry("## Мама", "Мама"),
            Map.entry("## Настроение", "Настроение"),
            Map.entry("### Завтрак", "Завтрак"),
            Map.entry("### Обед", "Обед"),
            Map.entry("### Ужин", "Ужин"),
            Map.entry("### Перекусы", "Перекусы"));
    for (String raw : doc.text.split("\\r?\\n")) {
      if (raw.contains("![[") && raw.contains(".webp") && !raw.contains("line")) photos++;
      String line = raw.strip();
      if (headings.containsKey(line)) {
        section = headings.get(line);
        continue;
      }
      if (line.startsWith("## ")) {
        section = "";
        continue;
      }
      if (!content(line)) continue;
      for (String id : List.of("Мама", "Утро", "День", "Вечер", "Перекусы"))
        if (tag(line, id)) completed.add(id);
      if (section.equals("notes")) notes++;
      else if (!section.isEmpty()) completed.add(section);
    }
    List<Row> rows = new ArrayList<>();
    rows.add(
        new Row("Заметки", "## Заметки", List.of(new Part(Integer.toString(notes), notes == 0))));
    rows.add(row("Краткое описание дня", "## Краткое описание дня", filled(fields, "ShortDescr")));
    rows.add(
        new Row(
            "Основные события дня",
            "## Основные события дня",
            List.of(
                new Part("Утро", completed.contains("Утро")),
                new Part("День", completed.contains("День")),
                new Part("Вечер", completed.contains("Вечер")))));
    String finance = context.financeError();
    boolean financeOk = false;
    if (finance == null && context.operations() != null && context.modified() > 0) {
      long diff = context.modified() - context.operations();
      financeOk = diff < 0;
      if (financeOk) finance = "OK";
      else {
        long seconds = diff / 1000;
        finance =
            seconds >= 86400
                ? "отстаёт на " + seconds / 86400 + " дн."
                : seconds >= 3600
                    ? "отстаёт на " + seconds / 3600 + " ч."
                    : seconds >= 60
                        ? "отстаёт на " + seconds / 60 + " мин."
                        : seconds > 0 ? "отстаёт на " + seconds + " сек." : "NOT OK";
      }
    }
    rows.add(
        new Row(
            "Доходы и расходы",
            "",
            List.of(new Part(finance == null ? "Нет данных" : finance, financeOk))));
    rows.add(row("Местоположение", "## Местоположение", filled(fields, "Местоположение")));
    rows.add(row("Погода", "## Погода", filled(fields, "Погода")));
    rows.add(row("Температура", "## Погода", filled(fields, "Температура")));
    rows.add(row("Во что одевался", "## Погода", filled(fields, "ВоЧтоОдевалсяНаУлицу")));
    rows.add(row("Настроение", "## Настроение", completed.contains("Настроение")));
    rows.add(
        new Row(
            "Питание",
            "## Питание",
            List.of(
                new Part("Завтрак", completed.contains("Завтрак")),
                new Part("Обед", completed.contains("Обед")),
                new Part("Ужин", completed.contains("Ужин")),
                new Part("Перекусы", completed.contains("Перекусы")))));
    rows.add(new Row("Фотографии", "", List.of(new Part(Integer.toString(photos), photos > 0))));
    rows.add(
        new Row(
            "Работа",
            "",
            List.of(
                new Part(
                    context.workError() != null
                        ? context.workError()
                        : context.workOk() ? "OK" : "NOT OK",
                    context.workOk()))));
    rows.add(row("Мама", "## Мама", completed.contains("Мама")));
    rows.add(
        row(
            "Машина",
            "### Машина",
            pair(doc, "car", "Ездил на машине", "Не ездил на машине", false, fields)));
    rows.add(
        row(
            "Спорт и диета",
            "## Спорт и диета",
            pair(doc, "diet", "Соблюдал диету", "не соблюдал диету", true, fields)));
    return List.copyOf(rows);
  }
}
