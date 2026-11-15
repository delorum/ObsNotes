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
    if (state.taskOnly() || state.rootPath.isEmpty()) return changes;
    List<Change> result = new ArrayList<>(changes);
    int existing = -1;
    for (int i = 0; i < result.size(); i++)
      if (result.get(i).path.equals(state.rootPath)) existing = i;
    String before = existing >= 0 ? result.get(existing).before : state.rootSnapshot;
    String after = existing >= 0 ? result.get(existing).after : before;
    Markdown current = new Markdown(after);
    List<Markdown.Block> roots =
        current.blocks.stream()
            .filter(b -> b.text().equals(state.rootOriginal))
            .collect(java.util.stream.Collectors.toList());
    if (roots.size() != 1)
      throw new IllegalArgumentException("Корневой блок изменился или неоднозначен");
    after = current.replace(roots.get(0), state.rootReplacement).text;
    Change root = new Change(state.rootPath, before, after);
    if (existing >= 0) result.set(existing, root);
    else result.add(0, root);
    return List.copyOf(result);
  }

  public static List<Change> plan(
      EditorState state,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now) {
    boolean same = state.sourcePath.equals(state.destinationPath);
    Markdown.Block block = state.fresh ? null : state.originalBlock(source);
    if (!state.taskOnly() && state.emptyNote()) {
      if (block == null) return List.of();
      if (!Events.id(block.text()).isEmpty())
        throw new IllegalArgumentException("Блок с якорем нельзя удалить");
      return clean(
          List.of(new Change(state.sourcePath, source.text, source.replace(block, "").text)));
    }
    String text = state.taskOnly() ? "" : state.savedText();
    if (state.taskOnly() && block != null && !Events.id(block.text()).isEmpty())
      throw new IllegalArgumentException("Блок с якорем нельзя убрать при отметке задачи");
    if (!same && (!Events.id(text).isEmpty() || text.contains("[[#^")))
      throw new IllegalArgumentException(
          "Корневой блок или локальную ссылку нельзя переносить между днями: ссылки должны"
              + " сохранить адрес.");
    if (!state.fresh
        && same
        && state.kind == Markdown.Kind.SECTION
        && block.section().heading().equals(state.heading))
      return finish(
          List.of(new Change(state.sourcePath, source.text, source.replace(block, text).text)),
          state,
          false);
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
    if (state.fresh && state.heading.equals("## Заметки") && state.kind == Markdown.Kind.SECTION)
      payload = to.timestamp(now) + text;
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
      return finish(List.of(new Change(state.sourcePath, source.text, result.text)), state, true);
    return finish(
        List.of(
            new Change(state.destinationPath, destination.text, result.text),
            new Change(state.sourcePath, source.text, from.text)),
        state,
        true);
  }

  private static List<Change> finish(List<Change> changes, EditorState state, boolean compact) {
    List<Change> result = addRoot(changes, state);
    return compact ? clean(result) : result;
  }

  private static List<Change> clean(List<Change> changes) {
    return changes.stream()
        .map(
            c ->
                c.before.equals(c.after)
                    ? c
                    : new Change(c.path, c.before, DiaryCleanup.compress(c.after)))
        .collect(java.util.stream.Collectors.toList());
  }
}
