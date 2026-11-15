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
}
