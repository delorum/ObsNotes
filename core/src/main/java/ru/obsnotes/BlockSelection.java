package ru.obsnotes;

import java.util.*;

/**
 * Temporary selection addresses originals, so projections and search cannot duplicate or hide it.
 */
public final class BlockSelection {
  public record Entry(String path, String snapshot, Markdown.Block block) {}

  private final List<Entry> entries = new ArrayList<>();
  public boolean onlySelected;

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }

  public void clear() {
    entries.clear();
    onlySelected = false;
  }

  public boolean contains(String path, Markdown.Block block) {
    return entries.stream()
        .anyMatch(
            e ->
                e.path().equals(path)
                    && e.block().start() == block.start()
                    && e.block().text().equals(block.text()));
  }

  public void toggle(String path, Markdown document, Markdown.Block block) {
    if (contains(path, block))
      entries.removeIf(e -> e.path().equals(path) && e.block().start() == block.start());
    else {
      if (!document.blocks.contains(block)) throw new IllegalArgumentException("Запись изменилась");
      entries.add(new Entry(path, document.text, block));
    }
    if (entries.isEmpty()) onlySelected = false;
  }

  public List<Entry> ordered(String dailyPath, Map<String, Markdown> current) {
    for (var e : entries) {
      var document = current.get(e.path());
      if (document == null || !document.text.equals(e.snapshot()))
        throw new IllegalArgumentException(
            "Выбранные записи изменились. Сбросьте выбор и выберите их заново");
    }
    return entries.stream()
        .sorted(
            Comparator.<Entry>comparingInt(e -> e.path().equals(dailyPath) ? 0 : 1)
                .thenComparing(Entry::path)
                .thenComparingInt(e -> e.block().start()))
        .toList();
  }

  public List<DayOutline.Section> filter(List<DayOutline.Section> source, String dailyPath) {
    List<DayOutline.Section> result = new ArrayList<>();
    for (var section : source) {
      var items =
          section.items().stream()
              .filter(i -> contains(i.path().isEmpty() ? dailyPath : i.path(), i.original()))
              .toList();
      var children = filter(section.children(), dailyPath);
      if (!items.isEmpty() || !children.isEmpty())
        result.add(new DayOutline.Section(section.heading(), section.title(), items, children));
    }
    return List.copyOf(result);
  }

  /**
   * A verified local reorder gives an exact identity mapping even when two blocks have equal text.
   */
  public void reordered(String path, Markdown before, Markdown after, Map<Integer, Integer> moved) {
    for (int i = 0; i < entries.size(); i++) {
      var e = entries.get(i);
      if (!e.path().equals(path) || !e.snapshot().equals(before.text)) continue;
      int start = moved.getOrDefault(e.block().start(), e.block().start());
      var block =
          after.blocks.stream()
              .filter(b -> b.start() == start && b.text().equals(e.block().text()))
              .findFirst()
              .orElseThrow();
      entries.set(i, new Entry(path, after.text, block));
    }
  }
}
