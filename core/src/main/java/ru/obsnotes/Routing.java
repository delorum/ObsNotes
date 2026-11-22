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
      if (target.kind() == Markdown.Kind.TASK && DiaryTasks.isSupported(target.source()))
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

  public record FieldHit(
      String heading, String key, String value, Markdown.Block block, int start) {
    public String label() {
      return key + "::" + (value.isEmpty() ? "" : " " + value);
    }

    public String searchLabel() {
      return key + "\n" + value;
    }
  }

  private static String fieldLabel(Markdown.Target target) {
    var match = Markdown.FIELD.matcher(target.source());
    if (!match.matches()) return target.label();
    String value = CrossReferences.clean(match.group(2).strip());
    return match.group(1) + "::" + (value.isEmpty() ? "" : " " + value);
  }

  public static final class Catalog {
    public final List<Choice> choices;
    private final List<FieldHit> fields = new ArrayList<>();
    private final Map<String, Choice> canonical = new LinkedHashMap<>();
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();
    private final Map<String, String> sectionLabels = new LinkedHashMap<>();

    public Catalog(Markdown destination, Markdown template) {
      this(destination, template, false);
    }

    public Catalog(Markdown destination, Markdown template, boolean workAvailable) {
      Map<String, String> byHeading = new LinkedHashMap<>();
      for (var d : CrossReferences.destinations(template))
        aliases.computeIfAbsent(d.heading(), ignored -> new ArrayList<>()).add(d.id());
      for (var d : CrossReferences.sectionDestinations(template))
        byHeading.put(d.heading(), d.id());
      List<Choice> rows = new ArrayList<>();
      for (var t : destination.targets(template)) {
        String id =
            t.kind() == Markdown.Kind.SECTION ? byHeading.getOrDefault(t.heading(), "") : "";
        Choice choice = new Choice(t, id);
        rows.add(choice);
        if (!id.isEmpty()) canonical.putIfAbsent(id.toLowerCase(Locale.ROOT), choice);
      }
      if (workAvailable) rows.add(WorkDaily.choice());
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
      for (var field : InlineFields.all(destination)) {
        var section =
            destination.sections.stream()
                .filter(row -> field.start() >= row.body() && field.start() < row.end())
                .findFirst()
                .orElse(null);
        var block =
            destination.blocks.stream()
                .filter(row -> field.start() >= row.start() && field.start() < row.end())
                .findFirst()
                .orElse(null);
        if (section == null || block == null) continue;
        String value = CrossReferences.clean(field.value());
        fields.add(new FieldHit(section.heading(), field.key(), value, block, field.start()));
        termsBySection
            .computeIfAbsent(section.heading(), ignored -> new LinkedHashSet<>())
            .add(value);
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
     * Search names, aliases, field keys/current values and diary tasks, without ordinary note text.
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

    public List<FieldHit> matchingFields(String heading, String query) {
      if (query.isBlank()) return List.of();
      return FuzzySearch.ranked(
          fields.stream().filter(field -> field.heading().equals(heading)).toList(),
          query,
          FieldHit::searchLabel);
    }

    /** Only actual visible matching lines qualify, including duplicate keys in a shared block. */
    public List<FieldHit> matchingFields(DayOutline.Section section, String query) {
      if (query.isBlank()) return List.of();
      Set<Integer> visible = new HashSet<>();
      for (var item : section.items())
        if (!item.projection())
          for (var line : item.view().lines()) visible.add(item.original().start() + line.start());
      return FuzzySearch.ranked(
          fields.stream()
              .filter(
                  field ->
                      field.heading().equals(section.heading()) && visible.contains(field.start()))
              .toList(),
          query,
          FieldHit::key);
    }

    public String tagSearchLabel(CrossReferences.Destination destination) {
      return destination.id()
          + "\n"
          + destination.title()
          + "\n"
          + String.join(", ", aliases.getOrDefault(destination.heading(), List.of()));
    }

    public String pickerLabel(Choice choice) {
      if (choice.target().kind() == Markdown.Kind.FIELD)
        return choice.target().heading().replaceFirst("^#+ +", "")
            + " / "
            + fieldLabel(choice.target());
      String label =
          choice.target().kind() == Markdown.Kind.SECTION
              ? choice.label()
              : choice.target().label();
      var ids = aliases.getOrDefault(choice.target().heading(), List.of());
      return label + (ids.isEmpty() ? "" : "\n" + String.join(", ", ids));
    }

    public String searchLabel(Choice choice) {
      if (choice.target().kind() == Markdown.Kind.FIELD) {
        var match = Markdown.FIELD.matcher(choice.target().source());
        if (match.matches())
          return pickerLabel(choice)
              + "\n"
              + match.group(1)
              + "\n"
              + CrossReferences.clean(match.group(2).strip())
              + "\n"
              + String.join(", ", aliases.getOrDefault(choice.target().heading(), List.of()));
      }
      return pickerLabel(choice);
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
