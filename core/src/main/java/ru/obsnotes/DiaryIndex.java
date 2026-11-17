package ru.obsnotes;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** Reuses parsed daily notes while retaining explicit freshness checks for every write. */
public final class DiaryIndex {
  public record Opened(Markdown template, Markdown document, String path) {}

  public record Day(String path, Markdown document) {}

  public Day day(VaultStorage storage, LocalDate date) throws IOException {
    String path = DiaryLayout.path(date);
    return new Day(path, read(storage, path));
  }

  /** Opening a day must not scan the archive. */
  public Opened open(VaultStorage storage, LocalDate date) throws IOException {
    Markdown template = TemplateCatalog.parse(storage.read(TemplateCatalog.PATH));
    if (template.section("## Заметки") == null)
      throw new IOException("В шаблоне не найден раздел Заметки");
    String path = DiaryLayout.path(date);
    Markdown document;
    try {
      document = day(storage, date).document();
    } catch (FileNotFoundException missing) {
      document = null;
    }
    return new Opened(template, document, path);
  }

  private record Cached(String source, Markdown document) {}

  private final Map<String, Cached> cache = new HashMap<>();

  public Markdown read(VaultStorage storage, String path) throws IOException {
    String source = storage.read(path);
    Cached previous = cache.get(path);
    if (previous != null && previous.source.equals(source)) return previous.document;
    Markdown document = new Markdown(source);
    cache.put(path, new Cached(source, document));
    return document;
  }

  public List<Events.Root> roots(VaultStorage storage, EditorState form) throws IOException {
    String selected = form.destinationPath;
    Markdown current = read(storage, selected);
    int except = !form.fresh && selected.equals(form.sourcePath) ? form.start : -1;
    return roots(selected, current, except);
  }

  public String rootSnapshot(VaultStorage storage, EditorState form, Events.Root root)
      throws IOException {
    if (!root.path().equals(form.destinationPath))
      throw new IllegalArgumentException("Выбранная дата изменилась");
    String snapshot = read(storage, root.path()).text;
    if (root.path().equals(form.sourcePath) && !snapshot.equals(form.sourceSnapshot)
        || !snapshot.equals(form.destinationSnapshot))
      throw new VaultRepository.Conflict(root.path());
    if (new Markdown(snapshot)
        .blocks.stream()
            .noneMatch(
                block ->
                    block.start() == root.block().start()
                        && block.text().equals(root.block().text())))
      throw new VaultRepository.Conflict(root.path());
    return snapshot;
  }

  public List<Events.Root> roots(String path, Markdown document, int exceptStart) {
    List<Events.Root> list = new ArrayList<>();
    for (var b : document.blocks) {
      if (b.start() == exceptStart) continue;
      String id = Events.rootId(b.text());
      if (!id.isEmpty() || !b.text().contains("#^") && !b.text().contains("```"))
        list.add(new Events.Root(path, b, id));
    }
    return List.copyOf(list);
  }
}
