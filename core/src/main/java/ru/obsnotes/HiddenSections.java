package ru.obsnotes;

import java.util.*;
import java.util.function.Function;

/** Local visibility only: source documents, routing and existing assignments stay intact. */
public final class HiddenSections {
  public static final Set<String> DEFAULTS =
      Set.of(
          "## План на день",
          "## Важные и/или срочные задачи",
          "## Сделано сегодня (или отмечено сделанным сегодня)");

  public record Entry(String heading, String title, boolean hidden, boolean inherited) {}

  private final List<Markdown.Section> order;
  private final Set<String> explicit;
  private final Set<String> effective;
  private final Map<String, List<String>> ancestors;
  private final List<Entry> entries;

  public HiddenSections(Markdown document, Markdown template, Collection<String> hidden) {
    this(DayOutline.orderedSections(document, template), hidden);
  }

  private HiddenSections(List<Markdown.Section> order, Collection<String> hidden) {
    this.order = order;
    explicit = Set.copyOf(hidden);
    Set<String> allHidden = new HashSet<>(explicit);
    Map<String, List<String>> paths = new LinkedHashMap<>();
    List<Markdown.Section> stack = new ArrayList<>();
    List<Entry> rows = new ArrayList<>();
    for (var section : order) {
      while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= section.level())
        stack.remove(stack.size() - 1);
      var parents = stack.stream().map(Markdown.Section::heading).toList();
      paths.put(section.heading(), parents);
      boolean inherited = parents.stream().anyMatch(explicit::contains);
      boolean invisible = inherited || explicit.contains(section.heading());
      if (invisible) allHidden.add(section.heading());
      rows.add(new Entry(section.heading(), section.title(), invisible, inherited));
      stack.add(section);
    }
    // Keep preferences for temporarily missing/renamed headings reversible in settings.
    explicit.stream()
        .filter(heading -> !paths.containsKey(heading))
        .sorted()
        .forEach(
            heading ->
                rows.add(new Entry(heading, heading.replaceFirst("^#+ +", ""), true, false)));
    effective = Set.copyOf(allHidden);
    ancestors = Map.copyOf(paths);
    rows.sort(Comparator.comparing(entry -> !entry.hidden()));
    entries = List.copyOf(rows);
  }

  public Set<String> explicit() {
    return explicit;
  }

  public List<Entry> entries() {
    return entries;
  }

  public boolean hidden(String heading) {
    return effective.contains(heading);
  }

  public HiddenSections show(String heading, boolean visible) {
    Set<String> next = new HashSet<>(explicit);
    if (visible) {
      next.remove(heading);
      next.removeAll(ancestors.getOrDefault(heading, List.of()));
    } else next.add(heading);
    return new HiddenSections(order, next);
  }

  public <T> List<T> offered(Collection<T> items, Function<T, String> heading) {
    return items.stream().filter(item -> !hidden(heading.apply(item))).toList();
  }

  public List<DayOutline.Section> outline(List<DayOutline.Section> roots) {
    return roots.stream()
        .filter(section -> !hidden(section.heading()))
        .map(
            section ->
                new DayOutline.Section(
                    section.heading(),
                    section.title(),
                    section.items(),
                    outline(section.children())))
        .toList();
  }
}
