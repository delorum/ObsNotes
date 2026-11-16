package ru.obsnotes;

import java.util.*;

/** Heading hierarchy is presentation data; pickers continue using the complete flat catalog. */
public final class DayOutline {
  private DayOutline() {}

  public record Section(
      String heading, String title, List<DayContentRules.Item> items, List<Section> children) {
    public int count() {
      return (int) items.stream().filter(i -> i.view().counted()).count()
          + children.stream().mapToInt(Section::count).sum();
    }

    public List<Markdown.Block> blocks() {
      return items.stream()
          .filter(i -> !i.projection())
          .map(DayContentRules.Item::original)
          .toList();
    }

    public List<CrossReferences.Projection> projections() {
      return items.stream()
          .filter(DayContentRules.Item::projection)
          .map(i -> new CrossReferences.Projection(i.original(), i.view().text()))
          .toList();
    }
  }

  private static final class Builder {
    final Markdown.Section section;
    final List<Builder> children = new ArrayList<>();

    Builder(Markdown.Section section) {
      this.section = section;
    }

    Section build(Markdown document, Markdown template, DayContentRules rules) {
      List<DayContentRules.Item> items = new ArrayList<>();
      for (var block : document.blocks)
        if (block.section().heading().equals(section.heading())) {
          var view = rules.view(block.text());
          if (!view.raw().isBlank()) items.add(new DayContentRules.Item(block, false, view));
        }
      for (var projection : CrossReferences.project(document, template, section.heading())) {
        var view = rules.view(projection.text());
        if (!view.raw().isBlank())
          items.add(new DayContentRules.Item(projection.origin(), true, view));
      }
      var nested = children.stream().map(c -> c.build(document, template, rules)).toList();
      return new Section(section.heading(), section.title(), List.copyOf(items), nested);
    }
  }

  public static List<Section> sections(Markdown document, Markdown template) {
    return sections(document, template, DayContentRules.EMPTY);
  }

  public static List<Section> sections(
      Markdown document, Markdown template, DayContentRules rules) {
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
            .map(s -> s.build(document, template, rules))
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
    return FuzzySearch.ranked(flat, query, s -> catalog.sectionSearchLabel(s.heading()));
  }

  /** Checklist links address a heading title even when its Markdown nesting changes. */
  public static String resolveHeading(List<Section> roots, String requested) {
    List<Section> flat = new ArrayList<>();
    flatten(roots, flat);
    for (var section : flat) if (section.heading().equals(requested)) return requested;
    String title = requested.replaceFirst("^#+ +", "");
    var matches = flat.stream().filter(s -> s.title().equals(title)).toList();
    return matches.size() == 1 ? matches.get(0).heading() : null;
  }

  private static void flatten(List<Section> source, List<Section> out) {
    for (var section : source) {
      out.add(section);
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
