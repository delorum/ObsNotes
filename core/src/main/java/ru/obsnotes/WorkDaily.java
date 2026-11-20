package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.util.*;

/** The work companion is resolved by one exact path; it is never generated or searched for. */
public final class WorkDaily {
  private WorkDaily() {}

  public static final String ROOT = "## Работа",
      ROUTE = "### Работа / Основные события",
      EVENTS = "## Основные события";
  public static final Markdown TEMPLATE = new Markdown(EVENTS + "\n");

  public record Note(String path, Markdown document) {}

  public static String path(LocalDate date) {
    return DiaryLayout.path(date)
        .replace("Дневник/Хроника/", "Работа/Daily/Хроника/")
        .replaceFirst("\\.md$", "-work.md");
  }

  public static boolean isPath(String path) {
    return path.startsWith("Работа/Daily/Хроника/") && path.endsWith("-work.md");
  }

  public static LocalDate date(String path) {
    return LocalDate.parse(DiaryLayout.fileName(path).substring(0, 10));
  }

  public static Note load(VaultStorage storage, LocalDate date) throws IOException {
    String path = path(date);
    if (!storage.exists(path)) return null;
    try {
      return new Note(path, new Markdown(storage.read(path)));
    } catch (IOException missing) {
      if (DayNavigation.missing(missing)) return null;
      throw missing;
    }
  }

  public static Routing.Choice choice() {
    return new Routing.Choice(
        new Markdown.Target("Работа / Основные события", ROUTE, -1, "", Markdown.Kind.SECTION), "");
  }

  public static boolean selected(EditorState form) {
    return form.heading.equals(ROUTE);
  }

  public static boolean selected(List<Routing.Choice> choices) {
    return !choices.isEmpty() && choices.get(0).target().heading().equals(ROUTE);
  }

  public static void snapshot(EditorState owner, Note note) {
    owner.workSnapshot = note == null ? null : note.document().text;
    if (owner.parts != null)
      for (var part : owner.parts.parts) part.form().workSnapshot = owner.workSnapshot;
  }

  public static EditorState editor(
      String dailyPath, Markdown daily, Note note, Markdown.Block block, Markdown template) {
    if (note == null) throw new IllegalArgumentException("Рабочая заметка не найдена");
    if (block != null
        && (!note.document().blocks.contains(block) || !block.section().heading().equals(EVENTS)))
      throw new IllegalArgumentException("Рабочая запись изменилась");
    EditorState form = new EditorState();
    form.sourcePath = note.path();
    form.sourceSnapshot = note.document().text;
    form.fresh = block == null;
    if (block != null) {
      form.original = form.text = block.text();
      form.start = block.start();
      form.end = block.end();
    }
    form.destinationPath = dailyPath;
    form.destinationSnapshot = daily.text;
    form.heading = form.fallbackHeading = ROUTE;
    form.kind = Markdown.Kind.SECTION;
    form.editFields = false;
    form.removeValueTarget();
    snapshot(form, note);
    form.routes = List.of(choice());
    form.routesInitialized = form.routeChosen = true;
    form.cursor = form.editableText().length();
    EditorRevision.remember(form, template);
    return form;
  }

  public static void requireTarget(EditorState owner, Note note) {
    boolean needed =
        owner.parts == null
            ? selected(owner)
            : owner.parts.parts.stream().anyMatch(part -> selected(part.form()));
    if (needed && note == null) throw new IllegalArgumentException("Рабочая заметка не найдена");
  }

  static EditorState physical(EditorState source) {
    if (!selected(source)) return source;
    if (source.workSnapshot == null)
      throw new IllegalArgumentException("Рабочая заметка не найдена");
    EditorState form = source.copy();
    form.destinationPath = path(date(source.destinationPath));
    form.destinationSnapshot = source.workSnapshot;
    form.heading = EVENTS;
    form.kind = Markdown.Kind.SECTION;
    form.targetLine = -1;
    form.targetSource = "";
    form.removeValueTarget();
    for (int i = form.tags().size() - 1; i >= 0; i--) form.removeTag(i);
    form.routes =
        List.of(
            new Routing.Choice(
                new Markdown.Target(EVENTS, EVENTS, -1, "", Markdown.Kind.SECTION), ""));
    form.parts = null;
    return form;
  }

  public static List<DayOutline.Section> outline(
      List<DayOutline.Section> original, Note note, DayContentRules rules) {
    if (note == null) return original;
    var section = note.document().section(EVENTS);
    List<DayContentRules.Item> items = new ArrayList<>();
    if (section != null)
      for (var block : note.document().blocks)
        if (block.section().heading().equals(EVENTS)) {
          var view = rules.view(block.text());
          if (!view.raw().isBlank())
            items.add(new DayContentRules.Item(block, false, view, note.path()));
        }
    var events = new DayOutline.Section(ROUTE, "Основные события", List.copyOf(items), List.of());
    var work = new DayOutline.Section(ROOT, "Работа", List.of(), List.of(events));
    List<DayOutline.Section> result = new ArrayList<>(original);
    int at = result.size();
    for (int i = 0; i < result.size(); i++)
      if (result.get(i).heading().equals("## Новости")) {
        at = i + 1;
        break;
      }
    result.add(at, work);
    return List.copyOf(result);
  }
}
