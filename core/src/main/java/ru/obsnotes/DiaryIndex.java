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

  /** Opening a day must not scan the archive; date lists are requested separately. */
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

  public List<Events.Root> roots(String path, Markdown document, int exceptStart) {
    List<Events.Root> list = new ArrayList<>();
    for (var b : document.blocks) {
      if (b.start() == exceptStart) continue;
      String id = Events.id(b.text());
      if (!id.isEmpty() || !b.text().contains("#^") && !b.text().contains("```"))
        list.add(new Events.Root(path, b, id));
    }
    return List.copyOf(list);
  }

  public record Related(String path, Markdown.Block block) {
    public String label() {
      return DiaryLayout.fileName(path)
          + " / "
          + block.section().title()
          + "\n"
          + CrossReferences.clean(block.text());
    }
  }

  public List<Related> related(VaultStorage storage, String source, String text)
      throws IOException {
    String id = Events.id(text);
    List<Related> result = new ArrayList<>();
    for (String path : storage.dailyNotes())
      for (var b : read(storage, path).blocks) {
        String root = Events.id(b.text());
        if (!id.isEmpty() && Events.references(b.text(), source, id)
            || !root.isEmpty() && Events.references(text, path, root))
          result.add(new Related(path, b));
      }
    return List.copyOf(result);
  }
}
