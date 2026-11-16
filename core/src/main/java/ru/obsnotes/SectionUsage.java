package ru.obsnotes;

import java.util.*;
import java.util.function.Function;

/** Local usage ranks suggestions, without pruning destinations or changing selected route order. */
public final class SectionUsage {
  public enum Role {
    PRIMARY,
    ADDITIONAL
  }

  public record Use(String heading, Role role) {}

  public record Count(long primary, long additional) {
    public Count {
      primary = Math.max(0, primary);
      additional = Math.max(0, additional);
    }

    public long total() {
      return plus(primary, additional);
    }
  }

  private final Map<String, Count> counts;

  public SectionUsage(Map<String, Count> source) {
    Map<String, Count> copy = new LinkedHashMap<>();
    source.forEach(
        (heading, count) -> {
          if (heading != null && !heading.isBlank() && count != null) copy.put(heading, count);
        });
    counts = Map.copyOf(copy);
  }

  private static long plus(long a, long b) {
    return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
  }

  public Map<String, Count> snapshot() {
    return counts;
  }

  public Count count(String heading) {
    return counts.getOrDefault(heading, new Count(0, 0));
  }

  public SectionUsage record(Collection<Use> uses) {
    Map<String, Count> next = new LinkedHashMap<>(counts);
    for (Use use : new LinkedHashSet<>(uses)) {
      if (use.heading() == null || use.heading().isBlank()) continue;
      Count old = next.getOrDefault(use.heading(), new Count(0, 0));
      next.put(
          use.heading(),
          use.role() == Role.PRIMARY
              ? new Count(plus(old.primary(), 1), old.additional())
              : new Count(old.primary(), plus(old.additional(), 1)));
    }
    return new SectionUsage(next);
  }

  public <T> List<T> ranked(Collection<T> items, Function<T, String> heading) {
    List<T> result = new ArrayList<>(items);
    result.sort(Comparator.<T>comparingLong(item -> count(heading.apply(item)).total()).reversed());
    return result;
  }

  public List<Routing.Choice> rankedRoutes(Collection<Routing.Choice> choices) {
    var sections =
        choices.stream().filter(c -> c.target().kind() == Markdown.Kind.SECTION).toList();
    List<Routing.Choice> result = ranked(sections, c -> c.target().heading());
    // Keep task/field destinations available after the section list, as in the original catalog.
    for (var choice : choices)
      if (choice.target().kind() != Markdown.Kind.SECTION) result.add(choice);
    return result;
  }

  /** Compute before committing; record the returned events only after a successful commit. */
  public static List<Use> planned(
      EditorState state, Markdown template, List<VaultRepository.Change> changes) {
    if (state.parts != null) {
      List<Use> uses = new ArrayList<>();
      for (int i = 0; i < state.parts.parts.size(); i++) {
        EditorState part = state.parts.parts.get(i).form().copy();
        part.fresh = i == 0 ? state.fresh : true;
        uses.addAll(planned(part, template, changes));
      }
      return List.copyOf(new LinkedHashSet<>(uses));
    }
    if (state.weatherForm
        || (!state.taskOnly() && state.emptyNote())
        || changes.stream().noneMatch(c -> !c.before().equals(c.after()))) return List.of();
    Markdown.Block original =
        state.fresh ? null : state.originalBlock(new Markdown(state.sourceSnapshot));
    List<Use> uses = new ArrayList<>();
    boolean moved =
        original != null
            && (!state.sourcePath.equals(state.destinationPath)
                || state.kind != Markdown.Kind.SECTION
                || !original.section().heading().equals(state.heading));
    if (moved || state.fresh && (state.routeChosen || !state.heading.equals("## Заметки")))
      uses.add(new Use(state.heading, Role.PRIMARY));
    Set<String> before =
        original == null
            ? Set.of()
            : additional(original.text(), template, original.section().heading());
    String saved =
        state.taskOnly()
            ? CrossReferences.tag("", 0, state.crossIds(), false, false)
            : state.savedText();
    for (String heading : additional(saved, template, state.heading))
      if (!before.contains(heading)) uses.add(new Use(heading, Role.ADDITIONAL));
    return List.copyOf(uses);
  }

  private static Set<String> additional(String text, Markdown template, String primary) {
    Set<String> result = new LinkedHashSet<>();
    for (var line : MarkdownLines.scan(text)) {
      if (line.literal() || line.text().stripLeading().startsWith(">")) continue;
      for (var tag : TaggedText.parse(line.text()).tags)
        for (var destination : TagChoices.of(template, tag.person(), tag.ids()).selected())
          if (!destination.heading().isBlank() && !destination.heading().equals(primary))
            result.add(destination.heading());
    }
    return result;
  }
}
