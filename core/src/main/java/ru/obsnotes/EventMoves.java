package ru.obsnotes;

import java.io.IOException;
import java.util.*;

/** Expand an editor transaction with references to anchors moved into another file. */
final class EventMoves {
  private EventMoves() {}

  static List<VaultRepository.Change> rewrite(
      VaultStorage storage, EditorState editor, List<VaultRepository.Change> changes)
      throws IOException {
    if (changes.size() < 2) return changes;
    var originals = editor.batch == null ? List.of(editor) : editor.batch.originals;
    Map<Events.Parent, Events.Parent> moves = new LinkedHashMap<>();
    Map<String, Markdown> before = new HashMap<>(), after = new HashMap<>();
    for (var original : originals) {
      if (original.fresh || !original.original.contains("^")) continue;
      String id = Events.id(original.original);
      if (id.isEmpty()) continue;
      var from = new Events.Parent(original.sourcePath, id);
      if (editor.removedRoots.contains(from)) continue;
      var source = changes.stream().filter(c -> c.path().equals(from.path())).findFirst();
      if (source.isEmpty() || source.get().before().equals(source.get().after())) continue;
      int previous = anchors(source.get(), id, before, true);
      int remaining = anchors(source.get(), id, after, false);
      if (remaining == previous) continue;
      if (remaining != 0 || previous != 1)
        throw new IllegalArgumentException("Якорь в исходной заметке неоднозначен: " + id);
      Events.Parent target = null;
      for (var change : changes) {
        if (change.path().equals(from.path())) continue;
        int count = anchors(change, id, after, false);
        if (count == 0 || count <= anchors(change, id, before, true)) continue;
        if (target != null || count > 1)
          throw new IllegalArgumentException("Якорь уже есть в заметке назначения: " + id);
        target = new Events.Parent(change.path(), id);
      }
      if (target == null)
        throw new IllegalArgumentException("Не удалось сохранить якорь при переносе: " + id);
      moves.put(from, target);
    }
    if (moves.isEmpty()) return changes;

    Set<String> planned = new HashSet<>();
    for (var change : changes) planned.add(change.path());
    List<VaultRepository.Change> backlinks = new ArrayList<>();
    var markers = moves.keySet().stream().map(key -> "#^" + key.id()).distinct().toList();
    storage.visitMarkdownNotes(
        Thread.currentThread()::isInterrupted,
        path -> {
          if (!planned.add(path)) return;
          String text = storage.read(path);
          if (markers.stream().noneMatch(text::contains)) return;
          String updated = Events.retarget(path, text, moves);
          if (!text.equals(updated)) backlinks.add(new VaultRepository.Change(path, text, updated));
        });
    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();

    Set<String> origins = new HashSet<>();
    for (var address : moves.keySet()) origins.add(address.path());
    List<VaultRepository.Change> result = new ArrayList<>();
    boolean included = false;
    for (var change : changes) {
      // Save external rewrites before the first relocated anchor disappears from its old file.
      if (!included && origins.contains(change.path())) {
        result.addAll(backlinks);
        included = true;
      }
      result.add(
          new VaultRepository.Change(
              change.path(),
              change.before(),
              Events.retarget(change.path(), change.after(), moves)));
    }
    return List.copyOf(result);
  }

  private static int anchors(
      VaultRepository.Change change, String id, Map<String, Markdown> parsed, boolean old) {
    String text = old ? change.before() : change.after();
    if (!text.contains("^" + id)) return 0;
    return (int)
        parsed.computeIfAbsent(change.path(), path -> new Markdown(text)).blocks.stream()
            .filter(block -> block.text().contains("^" + id) && Events.id(block.text()).equals(id))
            .count();
  }
}
