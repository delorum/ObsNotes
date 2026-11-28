package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Reads one event and streams its direct additions in order of distance from its day. */
public final class RelationSearch {
  private RelationSearch() {}

  public record RootChoice(Events.Parent target, String label) {}

  public record Related(String path, Markdown.Block block, String preview) {
    public Related(String path, Markdown.Block block) {
      this(path, block, visible(block.text()));
    }

    public String location() {
      return DiaryLayout.fileName(path) + " / " + block.section().title();
    }

    private static String visible(String text) {
      if (!Events.rootId(text).isEmpty()) text = text.substring(0, Events.rootCalloutStart(text));
      return CrossReferences.clean(text);
    }
  }

  /** The root is sent once, followed by batches of newly found additions. */
  public record Update(
      Related root, List<Related> additions, int checked, int unreadable, boolean complete) {}

  public static List<RootChoice> roots(EditorState editor) {
    var part = editor.activePart();
    var choices = rootsByTarget(part.sourcePath, part.text);
    if (editor.parts != null) {
      var refs = editor.parts.references(editor.parts.selected);
      for (var candidate : editor.parts.parts) {
        var parent = candidate.form();
        if (refs.stream().noneMatch(ref -> ref.part().equals(parent.partId))) continue;
        String id = Events.rootId(parent.text);
        if (id.isEmpty()) continue;
        var target = new Events.Parent(parent.sourcePath, id);
        choices.putIfAbsent(target, new RootChoice(target, Events.caption(parent.text)));
      }
    }
    return List.copyOf(choices.values());
  }

  static List<RootChoice> roots(String source, String text) {
    return List.copyOf(rootsByTarget(source, text).values());
  }

  private static Map<Events.Parent, RootChoice> rootsByTarget(String source, String text) {
    Map<Events.Parent, RootChoice> choices = new LinkedHashMap<>();
    String id = Events.rootId(text);
    if (!id.isEmpty()) {
      var own = new Events.Parent(source, id);
      choices.put(own, new RootChoice(own, Events.caption(text)));
    }
    for (var ref : Events.prefix(source, text).links())
      choices.putIfAbsent(ref.parent(), new RootChoice(ref.parent(), ref.label()));
    return choices;
  }

  public static void run(
      VaultStorage storage,
      Events.Parent root,
      LocalDate sourceDay,
      BooleanSupplier cancelled,
      Consumer<Update> updates)
      throws IOException {
    if (cancelled.getAsBoolean()) return;
    String raw = storage.read(root.path());
    if (cancelled.getAsBoolean()) return;
    Markdown note = new Markdown(raw);
    String anchor = "^" + root.id();
    var rootBlock =
        note.blocks.stream()
            .filter(
                block ->
                    block.text().contains(anchor) && root.id().equals(Events.rootId(block.text())))
            .findFirst();
    if (rootBlock.isEmpty()) {
      updates.accept(new Update(null, List.of(), 0, 0, true));
      return;
    }
    Related event = new Related(root.path(), rootBlock.get());
    updates.accept(
        new Update(event, additions(root.path(), note, root, event.block().start()), 0, 0, false));
    if (cancelled.getAsBoolean()) return;

    var archive = nearbyDays(storage, root.path(), sourceDay, cancelled);
    List<Related> batch = new ArrayList<>();
    int checked = 0, unreadable = 0;
    boolean showedRemote = false;
    String needle = "#^" + root.id();
    for (var entry : archive) {
      if (cancelled.getAsBoolean()) return;
      try {
        String day = storage.read(entry.path());
        if (cancelled.getAsBoolean()) return;
        // Most files cannot contain the anchor; parse only those with a possible reference.
        if (day.contains(needle))
          batch.addAll(additions(entry.path(), new Markdown(day), root, -1));
      } catch (IOException unavailable) {
        unreadable++;
      }
      checked++;
      if (checked % 16 == 0 || !showedRemote && !batch.isEmpty()) {
        if (cancelled.getAsBoolean()) return;
        updates.accept(new Update(null, List.copyOf(batch), checked, unreadable, false));
        showedRemote |= !batch.isEmpty();
        batch.clear();
      }
    }
    if (!cancelled.getAsBoolean())
      updates.accept(new Update(null, List.copyOf(batch), checked, unreadable, true));
  }

  private record DatedPath(String path, LocalDate date, long distance) {}

  private static List<DatedPath> nearbyDays(
      VaultStorage storage, String rootPath, LocalDate sourceDay, BooleanSupplier cancelled)
      throws IOException {
    LocalDate rootDay = date(rootPath);
    long origin = (rootDay == null ? sourceDay : rootDay).toEpochDay();
    List<DatedPath> days = new ArrayList<>();
    Set<String> seen = new HashSet<>(Set.of(rootPath));
    storage.visitDailyNotes(
        cancelled,
        path -> {
          if (cancelled.getAsBoolean() || !seen.add(path)) return;
          LocalDate day = date(path);
          if (day != null) days.add(new DatedPath(path, day, Math.abs(day.toEpochDay() - origin)));
        });
    if (!cancelled.getAsBoolean())
      days.sort(
          Comparator.comparingLong(DatedPath::distance)
              .thenComparing(DatedPath::date)
              .thenComparing(DatedPath::path));
    return days;
  }

  private static LocalDate date(String path) {
    int start = path.lastIndexOf('/') + 1;
    if (path.length() < start + 10) return null;
    try {
      return LocalDate.parse(path.substring(start, start + 10));
    } catch (DateTimeException invalid) {
      return null;
    }
  }

  private static List<Related> additions(
      String path, Markdown doc, Events.Parent root, int rootStart) {
    List<Related> found = new ArrayList<>();
    for (var block : doc.blocks)
      if (block.start() != rootStart
          && block.text().contains("#^")
          && Events.references(block.text(), path, root.path(), root.id()))
        found.add(new Related(path, block));
    return List.copyOf(found);
  }

  /**
   * Locate the same block again after rereading its file, including edits before its old offset.
   */
  public static Optional<Markdown.Block> currentBlock(Markdown doc, Related selected) {
    Markdown.Block previous = selected.block();
    var equal =
        doc.blocks.stream()
            .filter(
                block ->
                    block.section().heading().equals(previous.section().heading())
                        && block.text().equals(previous.text()))
            .toList();
    var atOriginalPosition =
        equal.stream().filter(block -> block.start() == previous.start()).findFirst();
    if (atOriginalPosition.isPresent()) return atOriginalPosition;
    if (equal.size() == 1) return Optional.of(equal.get(0));
    String id = Events.rootId(previous.text());
    if (id.isEmpty()) return Optional.empty();
    var anchors =
        doc.blocks.stream().filter(block -> id.equals(Events.rootId(block.text()))).toList();
    return anchors.size() == 1 ? Optional.of(anchors.get(0)) : Optional.empty();
  }
}
