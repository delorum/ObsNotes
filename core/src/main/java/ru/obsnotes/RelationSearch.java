package ru.obsnotes;

import java.io.*;
import java.util.*;
import java.util.function.*;

/**
 * Fast direct parents and current-day children; root back-links stream without caching the archive.
 */
public final class RelationSearch {
  private RelationSearch() {}

  public record Related(String path, Markdown.Block block) {
    public String label() {
      return DiaryLayout.fileName(path)
          + " / "
          + block.section().title()
          + "\n"
          + CrossReferences.clean(block.text());
    }
  }

  public record Snapshot(List<Related> items, int checked, int unreadable, boolean complete) {}

  public static void run(
      VaultStorage storage,
      String source,
      String text,
      String sourceSnapshot,
      BooleanSupplier cancelled,
      Consumer<Snapshot> updates)
      throws IOException {
    if (cancelled.getAsBoolean()) return;
    if (!Events.hasRelations(source, text)) {
      updates.accept(new Snapshot(List.of(), 0, 0, true));
      return;
    }
    Markdown current = new Markdown(sourceSnapshot);
    String rootId = Events.rootId(text);
    Map<String, Related> found = new LinkedHashMap<>();
    Set<String> visited = new HashSet<>();
    int[] checked = {0}, unreadable = {0};
    Consumer<Boolean> publish =
        complete -> {
          if (!cancelled.getAsBoolean())
            updates.accept(
                new Snapshot(List.copyOf(found.values()), checked[0], unreadable[0], complete));
        };
    var parent = Events.parents(source, text);
    collect(found, source, current, source, rootId, parent);
    visited.add(source);
    publish.accept(false);
    for (var relation : parent) {
      String path = relation.path();
      if (!visited.add(path)) continue;
      if (cancelled.getAsBoolean()) return;
      try {
        String raw = storage.read(path);
        if (cancelled.getAsBoolean()) return;
        collect(found, path, new Markdown(raw), source, rootId, parent);
        checked[0]++;
      } catch (IOException unavailable) {
        unreadable[0]++;
      }
      publish.accept(false);
    }
    boolean persistedRoot =
        !rootId.isEmpty()
            && current.blocks.stream().anyMatch(b -> Events.id(b.text()).equals(rootId));
    if (!persistedRoot) {
      publish.accept(true);
      return;
    }
    storage.visitDailyNotes(
        cancelled,
        path -> {
          if (cancelled.getAsBoolean() || !visited.add(path)) return;
          int before = found.size();
          try {
            String raw = storage.read(path);
            if (cancelled.getAsBoolean()) return;
            // Most days cannot contain this reference; avoid parsing and retaining their Markdown.
            if (raw.contains("#^" + rootId))
              collect(found, path, new Markdown(raw), source, rootId, parent);
          } catch (IOException unavailable) {
            unreadable[0]++;
          }
          checked[0]++;
          if (found.size() != before || checked[0] % 20 == 0) publish.accept(false);
        });
    publish.accept(true);
  }

  private static void collect(
      Map<String, Related> found,
      String path,
      Markdown doc,
      String source,
      String rootId,
      List<Events.Parent> parent) {
    for (var block : doc.blocks) {
      boolean child = !rootId.isEmpty() && Events.references(block.text(), path, source, rootId);
      boolean root =
          parent.stream()
              .anyMatch(
                  relation ->
                      relation.path().equals(path)
                          && relation.id().equals(Events.id(block.text())));
      if (child || root) found.putIfAbsent(path + ":" + block.start(), new Related(path, block));
    }
  }
}
