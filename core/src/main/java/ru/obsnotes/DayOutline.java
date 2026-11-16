package ru.obsnotes;

import java.util.*;

/** Heading hierarchy is presentation data; pickers continue using the complete flat catalog. */
public final class DayOutline {
  private DayOutline() {}

  public record Section(
      String heading,
      String title,
      List<Markdown.Block> blocks,
      List<CrossReferences.Projection> projections,
      List<Section> children) {
    public int count() {
      return blocks.size() + projections.size() + children.stream().mapToInt(Section::count).sum();
    }
  }

  private static final class Builder {
    final Markdown.Section section;
    final List<Builder> children = new ArrayList<>();

    Builder(Markdown.Section section) {
      this.section = section;
    }

    Section build(Markdown document, Markdown template) {
      List<Markdown.Block> blocks =
          document.blocks.stream()
              .filter(b -> b.section().heading().equals(section.heading()))
              .collect(java.util.stream.Collectors.toList());
      return new Section(
          section.heading(),
          section.title(),
          blocks,
          CrossReferences.project(document, template, section.heading()),
          children.stream()
              .map(c -> c.build(document, template))
              .collect(java.util.stream.Collectors.toList()));
    }
  }

  public static List<Section> sections(Markdown document, Markdown template) {
    List<Markdown.Section> order = new ArrayList<>(template.sections);
    Set<String> known = new HashSet<>();
    for (var s : order) known.add(s.heading());
    String previous = null;
    for (var s : document.sections) {
      if (known.add(s.heading())) {
        int at = 0;
        if (previous != null)
          for (int i = 0; i < order.size(); i++)
            if (order.get(i).heading().equals(previous)) {
              at = i + 1;
              break;
            }
        order.add(at, s);
      }
      previous = s.heading();
    }
    Deque<Builder> parents = new ArrayDeque<>();
    List<Builder> roots = new ArrayList<>();
    for (var s : order) {
      Builder current = new Builder(s);
      while (!parents.isEmpty() && parents.peek().section.level() >= s.level()) parents.pop();
      if (parents.isEmpty()) roots.add(current);
      else parents.peek().children.add(current);
      parents.push(current);
    }
    List<Section> result =
        roots.stream()
            .map(s -> s.build(document, template))
            .collect(java.util.stream.Collectors.toList());
    for (int i = 0; i < result.size(); i++)
      if (result.get(i).heading.equals("## Заметки")) {
        result.add(0, result.remove(i));
        break;
      }
    return List.copyOf(result);
  }

  public static List<Section> search(Markdown document, Markdown template, String query) {
    List<Section> roots = sections(document, template);
    return search(roots, new Routing.Catalog(document, template), query);
  }

  public static List<Section> search(List<Section> roots, Routing.Catalog catalog, String query) {
    if (query.isBlank()) return roots;
    List<Section> flat = new ArrayList<>();
    flatten(roots, flat);
    return FuzzySearch.ranked(
        flat,
        query,
        s ->
            catalog.searchLabel(
                catalog.resolve(
                    new Markdown.Target(s.title(), s.heading(), -1, "", Markdown.Kind.SECTION))));
  }

  private static void flatten(List<Section> source, List<Section> out) {
    for (var section : source) {
      out.add(
          new Section(
              section.heading(),
              section.title(),
              section.blocks(),
              section.projections(),
              List.of()));
      flatten(section.children(), out);
    }
  }

  public static List<String> ancestors(Markdown doc, Markdown template, String heading) {
    return ancestors(sections(doc, template), heading);
  }

  public static List<String> ancestors(List<Section> roots, String heading) {
    List<String> path = new ArrayList<>();
    find(roots, heading, path);
    return path;
  }

  private static boolean find(List<Section> roots, String heading, List<String> path) {
    for (var s : roots) {
      path.add(s.heading());
      if (s.heading().equals(heading) || find(s.children(), heading, path)) return true;
      path.remove(path.size() - 1);
    }
    return false;
  }
}
