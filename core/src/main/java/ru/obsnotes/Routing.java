package ru.obsnotes;

import java.util.*;

/** Ordered destinations: the first receives the text, later sections receive crossPeople tags. */
public final class Routing {
  private Routing() {}

  public record Choice(Markdown.Target target, String crossId) {
    public String label() {
      if (target.kind() == Markdown.Kind.FIELD) {
        var m = Markdown.FIELD.matcher(target.source());
        if (m.matches()) return m.group(1);
      }
      if (target.kind() == Markdown.Kind.TASK && DiaryTasks.isDiary(target.source()))
        return new DiaryTasks.Task(target.line(), target.source(), false).label();
      if (target.kind() == Markdown.Kind.TASK)
        return target
            .source()
            .replaceFirst("^\\s*- \\[[ xX]\\]\\s*", "")
            .replaceAll("#task/[^\\s]+\\s*", "");
      return target.label().replaceFirst("^#+ +", "");
    }
  }

  public record Token(String text, Choice choice) {}

  public static final class Catalog {
    public final List<Choice> choices;
    private final Map<String, Choice> canonical = new LinkedHashMap<>();
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();
    private final Map<String, String> sectionLabels = new LinkedHashMap<>();

    public Catalog(Markdown destination, Markdown template) {
      Map<String, String> byHeading = new LinkedHashMap<>();
      for (var d : CrossReferences.destinations(template)) {
        aliases.computeIfAbsent(d.heading(), ignored -> new ArrayList<>()).add(d.id());
        if (d.id().matches("[\\p{L}]+")) byHeading.putIfAbsent(d.heading(), d.id());
      }
      List<Choice> rows = new ArrayList<>();
      for (var t : destination.targets(template)) {
        String id =
            t.kind() == Markdown.Kind.SECTION ? byHeading.getOrDefault(t.heading(), "") : "";
        Choice choice = new Choice(t, id);
        rows.add(choice);
        if (!id.isEmpty()) canonical.putIfAbsent(id.toLowerCase(Locale.ROOT), choice);
      }
      choices = List.copyOf(rows);
      Map<String, Set<String>> termsBySection = new LinkedHashMap<>();
      for (var doc : List.of(template, destination)) {
        var fields = InlineFields.all(doc);
        for (var section : doc.sections)
          for (var field : fields)
            if (field.start() >= section.body() && field.start() < section.end())
              termsBySection
                  .computeIfAbsent(section.heading(), ignored -> new LinkedHashSet<>())
                  .add(field.key());
      }
      for (var task : DiaryTasks.in(destination.text, 0))
        for (var section : destination.sections)
          if (task.start() >= section.body() && task.start() < section.end()) {
            Set<String> terms =
                termsBySection.computeIfAbsent(section.heading(), ignored -> new LinkedHashSet<>());
            terms.add(task.label());
            terms.add(task.label().split(";", 2)[0].strip());
          }
      for (var choice : choices)
        if (choice.target().kind() == Markdown.Kind.SECTION)
          sectionLabels.put(
              choice.target().heading(),
              searchLabel(choice)
                  + "\n"
                  + String.join(
                      "\n", termsBySection.getOrDefault(choice.target().heading(), Set.of())));
    }

    /**
     * Search names, aliases, field keys and current diary task text, without ordinary note values.
     */
    public String sectionSearchLabel(String heading) {
      return sectionLabels.getOrDefault(heading, heading);
    }

    public Choice resolve(Markdown.Target target) {
      return choices.stream()
          .filter(
              c ->
                  c.target.kind() == target.kind()
                      && c.target.heading().equals(target.heading())
                      && c.target.line() == target.line())
          .findFirst()
          .orElse(new Choice(target, ""));
    }

    public String searchLabel(Choice choice) {
      String label =
          choice.target().kind() == Markdown.Kind.SECTION
              ? choice.label()
              : choice.target().label();
      var ids = aliases.getOrDefault(choice.target().heading(), List.of());
      return label + (ids.isEmpty() ? "" : "\n" + String.join(", ", ids));
    }

    public List<Token> split(String ids) {
      List<Token> tokens = new ArrayList<>();
      int at = 0;
      StringBuilder unknown = new StringBuilder();
      while (at < ids.length()) {
        Choice found = null;
        int length = 0;
        for (var entry : canonical.entrySet())
          if (entry.getKey().length() > length
              && ids.regionMatches(true, at, entry.getKey(), 0, entry.getKey().length())) {
            found = entry.getValue();
            length = entry.getKey().length();
          }
        if (found == null) {
          unknown.append(ids.charAt(at++));
          continue;
        }
        if (unknown.length() > 0) {
          tokens.add(new Token(unknown.toString(), null));
          unknown.setLength(0);
        }
        tokens.add(new Token(ids.substring(at, at + length), found));
        at += length;
      }
      if (unknown.length() > 0) tokens.add(new Token(unknown.toString(), null));
      return tokens;
    }
  }

  public static String validate(List<Choice> choices) {
    Set<String> headings = new HashSet<>();
    for (int i = 0; i < choices.size(); i++) {
      Choice c = choices.get(i);
      if (i > 0 && (c.target.kind() != Markdown.Kind.SECTION || c.crossId.isEmpty()))
        return "Этот пункт можно выбрать только первым";
      if (!headings.add(c.target.kind() + "/" + c.target.heading() + "/" + c.target.line()))
        return "Пункт уже выбран";
    }
    return null;
  }
}
