package ru.obsnotes;

import java.util.*;

/** Name matches retain complete sections; content matches retain only matching field/task lines. */
public final class DaySearch {
  private record Line(
      DayContentRules.Item item, MarkdownLines.Line line, String label, boolean counted) {}

  private record Node(
      DayOutline.Section section, String label, List<Line> lines, List<Node> children) {}

  private record Hit(DayOutline.Section section, int rank) {}

  private final List<DayOutline.Section> original;
  private final List<Node> roots;
  private final Set<String> allHeadings;

  public record Result(List<DayOutline.Section> sections, Set<String> fullHeadings) {}

  public DaySearch(List<DayOutline.Section> outline, Routing.Catalog catalog) {
    this(outline, catalog, DayContentRules.EMPTY);
  }

  public DaySearch(
      List<DayOutline.Section> outline, Routing.Catalog catalog, DayContentRules rules) {
    original = outline;
    roots = outline.stream().map(s -> index(s, catalog, rules)).toList();
    Set<String> headings = new LinkedHashSet<>();
    for (var section : outline) includeWhole(section, headings);
    allHeadings = Set.copyOf(headings);
  }

  private static Node index(
      DayOutline.Section section, Routing.Catalog catalog, DayContentRules rules) {
    List<Line> lines = new ArrayList<>();
    for (var item : section.items()) {
      for (var line : item.view().lines()) {
        if (line.literal()) continue;
        var field = Markdown.FIELD.matcher(CrossReferences.clean(line.text()));
        String label = null;
        if (field.matches()) label = field.group(1) + "\n" + field.group(2).strip();
        else if (DiaryTasks.isDiary(line.text())) {
          String task = new DiaryTasks.Task(0, line.text(), false).label();
          label = task + "\n" + task.split(";", 2)[0].strip();
        }
        if (label != null) lines.add(new Line(item, line, label, rules.countable(line.text())));
      }
    }
    return new Node(
        section,
        catalog.sectionIdentityLabel(section.heading()),
        List.copyOf(lines),
        section.children().stream().map(s -> index(s, catalog, rules)).toList());
  }

  public List<DayOutline.Section> find(String query) {
    return result(query).sections();
  }

  public Result result(String query) {
    if (query.isBlank()) return new Result(original, allHeadings);
    Set<String> full = new LinkedHashSet<>();
    var sections = filtered(roots, query, full).stream().map(Hit::section).toList();
    return new Result(sections, Set.copyOf(full));
  }

  private static void includeWhole(DayOutline.Section section, Set<String> headings) {
    headings.add(section.heading());
    for (var child : section.children()) includeWhole(child, headings);
  }

  /** Fields have dedicated result rows; keep matching tasks in the same source block visible. */
  public static List<DayContentRules.Item> withoutFields(
      List<DayContentRules.Item> items, List<Routing.FieldHit> fields) {
    if (fields.isEmpty()) return items;
    Set<Integer> offsets = new HashSet<>();
    for (var field : fields) offsets.add(field.start());
    List<DayContentRules.Item> result = new ArrayList<>();
    for (var item : items) {
      if (item.projection()) {
        result.add(item);
        continue;
      }
      var lines =
          item.view().lines().stream()
              .filter(line -> !offsets.contains(item.original().start() + line.start()))
              .toList();
      if (lines.isEmpty()) continue;
      String raw = String.join("\n", lines.stream().map(MarkdownLines.Line::text).toList());
      result.add(
          new DayContentRules.Item(
              item.original(),
              false,
              new DayContentRules.View(
                  lines, raw, CrossReferences.clean(raw), item.view().counted())));
    }
    return List.copyOf(result);
  }

  private static List<Hit> filtered(List<Node> nodes, String query, Set<String> full) {
    List<Hit> hits = new ArrayList<>();
    for (Node node : nodes) {
      int nameRank = FuzzySearch.rank(node.label(), query);
      int rank = nameRank;
      var lines = FuzzySearch.ranked(node.lines(), query, Line::label);
      Map<DayContentRules.Item, List<MarkdownLines.Line>> grouped = new LinkedHashMap<>();
      for (Line line : lines) {
        rank = Math.min(rank, FuzzySearch.rank(line.label(), query));
        grouped.computeIfAbsent(line.item(), ignored -> new ArrayList<>()).add(line.line());
      }
      List<DayContentRules.Item> items = new ArrayList<>();
      for (var entry : grouped.entrySet()) {
        var item = entry.getKey();
        var visible = entry.getValue();
        String raw = String.join("\n", visible.stream().map(MarkdownLines.Line::text).toList());
        var view =
            new DayContentRules.View(
                List.copyOf(visible),
                raw,
                CrossReferences.clean(raw),
                lines.stream().anyMatch(l -> l.item().equals(item) && l.counted()));
        items.add(new DayContentRules.Item(item.original(), item.projection(), view));
      }
      var children = filtered(node.children(), query, full);
      for (var child : children) rank = Math.min(rank, child.rank());
      if (rank == Integer.MAX_VALUE) continue;
      var s = node.section();
      DayOutline.Section visible;
      if (nameRank != Integer.MAX_VALUE) {
        visible = s;
        includeWhole(s, full);
      } else {
        visible =
            new DayOutline.Section(
                s.heading(),
                s.title(),
                List.copyOf(items),
                children.stream().map(Hit::section).toList());
      }
      hits.add(new Hit(visible, rank));
    }
    hits.sort(Comparator.comparingInt(Hit::rank));
    return List.copyOf(hits);
  }
}
