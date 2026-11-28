package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.util.*;

/** Optimistic concurrency plus a durable, destination-first two-file transaction. */
public final class VaultRepository {
  public record Change(String path, String before, String after) implements Serializable {}

  public interface Journal {
    void prepare(List<Change> changes) throws IOException;

    List<Change> pending() throws IOException;

    void complete() throws IOException;
  }

  public static final class Conflict extends IOException {
    public Conflict(String path) {
      super("Файл изменён извне: " + DiaryLayout.fileName(path) + ". Текст остался в редакторе.");
    }
  }

  private final VaultStorage storage;
  private final Journal journal;

  public VaultRepository(VaultStorage storage, Journal journal) {
    this.storage = storage;
    this.journal = journal;
  }

  public synchronized void recover() throws IOException {
    List<Change> changes = journal.pending();
    if (changes.isEmpty()) return;
    // Check all documents before continuing any interrupted transaction.
    for (Change change : changes) {
      String actual = storage.read(change.path);
      if (!actual.equals(change.before) && !actual.equals(change.after))
        throw new Conflict(change.path);
    }
    for (Change change : changes) {
      String actual = storage.read(change.path);
      if (actual.equals(change.before)) {
        storage.write(change.path, change.after);
        if (!storage.read(change.path).equals(change.after))
          throw new IOException("Не удалось проверить запись: " + change.path);
      }
    }
    journal.complete();
  }

  public synchronized void commit(List<Change> changes) throws IOException {
    recover();
    List<Change> active =
        changes.stream()
            .filter(c -> !c.before.equals(c.after))
            .collect(java.util.stream.Collectors.toList());
    for (Change change : active)
      if (!storage.read(change.path).equals(change.before)) throw new Conflict(change.path);
    if (active.isEmpty()) return;
    journal.prepare(active);
    // Recheck immediately before each write; SAF cannot offer an inter-process compare-and-swap.
    for (Change change : active) {
      if (!storage.read(change.path).equals(change.before)) throw new Conflict(change.path);
      storage.write(change.path, change.after);
      if (!storage.read(change.path).equals(change.after))
        throw new IOException("Не удалось проверить запись: " + change.path);
    }
    journal.complete();
  }

  /** Explicit conflict resolution: retain backups and accept the current external files as-is. */
  public synchronized void keepCurrentFiles() throws IOException {
    journal.complete();
  }

  public static List<Change> addRoot(List<Change> changes, EditorState state) {
    return state.taskOnly() ? changes : addRoots(changes, state.pendingRoots());
  }

  public static List<Change> addRoots(List<Change> changes, List<EventLink.RootEdit> edits) {
    List<Change> result = new ArrayList<>(changes);
    Map<String, EventLink.RootEdit> unique = new LinkedHashMap<>();
    for (var edit : edits) {
      String key = edit.path() + "\n" + edit.original();
      var prior = unique.putIfAbsent(key, edit);
      if (prior != null && !prior.replacement().equals(edit.replacement()))
        throw new IllegalArgumentException("Корень выбран неоднозначно");
    }
    for (var edit : unique.values()) {
      int existing = -1;
      for (int i = 0; i < result.size(); i++)
        if (result.get(i).path.equals(edit.path())) existing = i;
      String before = existing >= 0 ? result.get(existing).before : edit.snapshot();
      if (!edit.snapshot().isEmpty() && !before.equals(edit.snapshot()))
        throw new IllegalArgumentException("Файл корня изменился извне");
      String after = existing >= 0 ? result.get(existing).after : before;
      Markdown current = new Markdown(after);
      var roots =
          current.blocks.stream().filter(block -> block.text().equals(edit.original())).toList();
      if (roots.size() != 1)
        throw new IllegalArgumentException("Корневой блок изменился или неоднозначен");
      after = current.replace(roots.get(0), edit.replacement()).text;
      Change change = new Change(edit.path(), before, after);
      if (existing >= 0) result.set(existing, change);
      else result.add(0, change);
    }
    return List.copyOf(result);
  }

  public List<Change> planEditor(
      EditorState state,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now,
      CleanupRules rules)
      throws IOException {
    return EventMoves.rewrite(
        storage, state, plan(state, source, destination, template, date, now, rules));
  }

  static LocalDate destinationDate(String path, LocalDate fallback) {
    try {
      return RelativeDates.date(path);
    } catch (DateTimeException | IndexOutOfBoundsException invalid) {
      return fallback;
    }
  }

  public static List<Change> plan(
      EditorState state,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now) {
    return plan(state, source, destination, template, date, now, CleanupRules.EMPTY);
  }

  public static List<Change> plan(
      EditorState state,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now,
      CleanupRules rules) {
    if (EditorRevision.unchanged(state, template)) return List.of();
    List<Change> changes = planContents(state, source, destination, template, date, now, rules);
    if (state.deferRootCleanup || state.removedRoots.isEmpty()) return changes;
    List<Change> result = new ArrayList<>();
    for (Change change : changes)
      result.add(
          new Change(
              change.path,
              change.before,
              Events.detach(new Markdown(change.after), change.path, state.removedRoots).text));
    return List.copyOf(result);
  }

  private static List<Change> planContents(
      EditorState state,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now,
      CleanupRules rules) {
    if (EditorRevision.unchanged(state, template)) return List.of();
    if (state.batch != null) return state.batch.plan(state, template, date, now, rules);
    if (state.parts == null && WorkDaily.selected(state)) {
      EditorState physical = WorkDaily.physical(state);
      return plan(
          physical,
          source,
          new Markdown(physical.destinationSnapshot),
          WorkDaily.TEMPLATE,
          date,
          now,
          rules);
    }
    if (WorkDaily.isPath(state.destinationPath)) template = WorkDaily.TEMPLATE;
    if (state.parts != null)
      return state.parts.plan(state, source, destination, template, date, now, rules);
    if (state.weatherForm)
      return DayEdits.change(state.sourcePath, source, state.savedWeather(source, template));
    boolean same = state.sourcePath.equals(state.destinationPath);
    Markdown.Block block = state.fresh ? null : state.originalBlock(source);
    if (!state.taskOnly() && state.emptyNote()) {
      if (block == null) return List.of();
      if (!Events.id(block.text()).isEmpty()
          && !state.removedRoots.contains(
              new Events.Parent(state.sourcePath, Events.id(block.text()))))
        throw new IllegalArgumentException("Блок с якорем нельзя удалить");
      return clean(
          List.of(new Change(state.sourcePath, source.text, source.replace(block, "").text)),
          rules);
    }
    String text = state.taskOnly() ? "" : state.savedText();
    if (!same) text = Events.bindLocalReferences(state.sourcePath, text);
    if (state.taskOnly()
        && block != null
        && !Events.id(block.text()).isEmpty()
        && !state.removedRoots.contains(
            new Events.Parent(state.sourcePath, Events.id(block.text()))))
      throw new IllegalArgumentException("Блок с якорем нельзя убрать при отметке задачи");
    if ((state.kind != Markdown.Kind.SECTION || WorkDaily.isPath(state.destinationPath))
        && !Events.id(text).isEmpty())
      throw new IllegalArgumentException("Блок с якорем можно переносить только в раздел дневника");
    if (!state.fresh
        && same
        && state.kind == Markdown.Kind.SECTION
        && block.section().heading().equals(state.heading))
      return finish(
          List.of(new Change(state.sourcePath, source.text, source.replace(block, text).text)),
          state,
          false,
          rules);
    Markdown from = state.fresh ? source : source.replace(block, "");
    Markdown to = same ? from : destination;
    Markdown.Target target = state.target();
    if (same
        && state.kind != Markdown.Kind.SECTION
        && block != null
        && block.start() < target.line())
      target =
          new Markdown.Target(
              target.label(),
              target.heading(),
              target.line() - (block.end() - block.start()),
              target.source(),
              target.kind());
    String payload = text;
    if (state.fresh
        && state.origins.isEmpty()
        && state.heading.equals("## Заметки")
        && state.kind == Markdown.Kind.SECTION) {
      payload = withTimestamp(to, state.destinationPath, text, now);
    }
    if (state.heading.equals("## Сделано сегодня (или отмечено сделанным сегодня)")
        && state.kind == Markdown.Kind.SECTION)
      payload =
          String.join(
              "\n",
              text.lines()
                  .map(line -> "~~" + line + "~~")
                  .collect(java.util.stream.Collectors.toList()));
    Markdown result =
        state.taskOnly()
            ? to.completeTask(target, "", state.crossIds(), date)
            : to.insert(target, payload, date, template);
    if (same)
      return finish(
          List.of(new Change(state.sourcePath, source.text, result.text)),
          state,
          !state.taskAppend,
          rules);
    return finish(
        List.of(
            new Change(state.destinationPath, destination.text, result.text),
            new Change(state.sourcePath, source.text, from.text)),
        state,
        true,
        rules);
  }

  static String withTimestamp(Markdown note, String path, String text, LocalDateTime now) {
    Events.Prefix prefix = Events.prefix(path, text);
    return prefix.raw() + note.timestamp(now) + prefix.body();
  }

  private static List<Change> finish(
      List<Change> changes, EditorState state, boolean compact, CleanupRules rules) {
    List<Change> result = addRoot(changes, state);
    return compact ? clean(result, rules) : result;
  }

  private static List<Change> clean(List<Change> changes, CleanupRules rules) {
    return changes.stream()
        .map(
            c ->
                c.before.equals(c.after)
                    ? c
                    : new Change(c.path, c.before, DiaryCleanup.compress(c.after, rules)))
        .collect(java.util.stream.Collectors.toList());
  }
}
