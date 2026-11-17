package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

public final class CrossReferences {
  private CrossReferences() {}

  private static final Pattern TAG =
      Pattern.compile("#(crossPeople|crossOneLine|person|personOneLine)/([\\p{L}]+)");

  public record Destination(String id, String title, String heading, List<String> includes) {}

  public record Projection(Markdown.Block origin, String text) {}

  public static List<Destination> selectable(Markdown template, boolean person) {
    Set<String> people = Set.of("Лена", "Аня", "Вика", "Мама");
    Map<String, Destination> unique = new LinkedHashMap<>();
    for (Destination d : destinations(template))
      if (d.id.matches("[\\p{L}]+") && (!person || people.contains(d.id)))
        unique.putIfAbsent(d.id, d);
    return List.copyOf(unique.values());
  }

  public static List<Destination> destinations(Markdown template) {
    List<Destination> list = new ArrayList<>();
    for (Markdown.Section s : template.sections) {
      String body = template.text.substring(s.body(), s.end());
      if (!body.contains("Скрипты/Dataview/crossPeople")) continue;
      Matcher p = Pattern.compile("person:\\s*\"([^\"]+)\"").matcher(body);
      Matcher includes = Pattern.compile("include_sections:\\s*\\[([^]]+)\\]").matcher(body);
      List<String> included = new ArrayList<>();
      if (includes.find()) {
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(includes.group(1));
        while (quoted.find()) included.add(quoted.group(1));
      }
      if (p.find())
        for (String id : p.group(1).split(","))
          list.add(new Destination(id.trim(), s.title(), s.heading(), List.copyOf(included)));
    }
    return List.copyOf(list);
  }

  public static String tag(
      String text, int cursor, Collection<String> ids, boolean oneLine, boolean person) {
    if (ids.isEmpty()) return text;
    for (String id : ids)
      if (!id.matches("[\\p{L}]+")) throw new IllegalArgumentException("Неверный идентификатор");
    String suffix =
        " #"
            + (person
                ? (oneLine ? "personOneLine" : "person")
                : (oneLine ? "crossOneLine" : "crossPeople"))
            + "/"
            + String.join("", ids);
    if (person && ids.size() == 1) {
      String name = ids.iterator().next();
      int from = oneLine ? text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1 : 0;
      int end = oneLine ? text.indexOf('\n', Math.min(cursor, text.length())) : text.length();
      if (end < 0) end = text.length();
      int found = text.indexOf(name, from);
      if (found >= 0 && found < end && (found == 0 || text.charAt(found - 1) != '/'))
        return text.substring(0, found)
            + "#"
            + (oneLine ? "personOneLine" : "person")
            + "/"
            + text.substring(found);
    }
    int at =
        oneLine ? text.indexOf('\n', Math.max(0, Math.min(cursor, text.length()))) : text.length();
    if (at < 0) at = text.length();
    int callout = text.indexOf("\n> [!info]- Дополнения к событию");
    if (callout >= 0 && at > callout) at = callout;
    // Keep Obsidian block IDs at the end of their line.
    int lineStart = text.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
    Matcher block =
        Pattern.compile("\\s+\\^[A-Za-z0-9_-]+$").matcher(text.substring(lineStart, at));
    if (block.find()) at = lineStart + block.start();
    else while (at > lineStart && (text.charAt(at - 1) == ' ' || text.charAt(at - 1) == '\t')) at--;
    return text.substring(0, at) + suffix + text.substring(at);
  }

  public static String clean(String text) {
    int callout = text.indexOf("\n> [!info]- Дополнения к событию");
    String content = callout >= 0 ? text.substring(0, callout) : text;
    content = SecretText.display(SecretText.openBody(Events.prefix("", content).body()));
    return content
        .replaceAll("#(?:crossPeople|crossOneLine)/[\\p{L}]+", "")
        .replaceAll("#(?:person|personOneLine)/", "")
        .replaceAll("(?m)(?:^|[ \\t])\\^[A-Za-z0-9_-]+[ \\t]*$", "")
        .strip();
  }

  private static boolean inside(String source, String target, Markdown document) {
    Markdown.Section s = document.section(source), t = document.section(target);
    if (s == null || t == null || s.start() < t.start()) return false;
    if (s.start() == t.start()) return true;
    for (Markdown.Section intermediate : document.sections)
      if (intermediate.start() > t.start()
          && intermediate.start() <= s.start()
          && intermediate.level() <= t.level()) return false;
    return true;
  }

  public static List<Projection> project(Markdown document, Markdown template, String heading) {
    List<Destination> destinations =
        destinations(template).stream()
            .filter(d -> d.heading.equals(heading))
            .collect(java.util.stream.Collectors.toList());
    if (destinations.isEmpty()) return List.of();
    List<Projection> result = new ArrayList<>();
    for (Markdown.Block block : document.blocks) {
      if (inside(block.section().heading(), heading, document)) continue;
      boolean full =
          destinations.stream().anyMatch(d -> d.includes.contains(block.section().heading()));
      List<String> selected = new ArrayList<>();
      for (String line : block.text().split("\\r?\\n")) {
        Matcher tags = TAG.matcher(line);
        while (tags.find()) {
          String ids = tags.group(2).toLowerCase(Locale.ROOT);
          if (destinations.stream().anyMatch(d -> ids.contains(d.id.toLowerCase(Locale.ROOT)))) {
            if (!tags.group(1).endsWith("OneLine")) full = true;
            else if (!selected.contains(line)) selected.add(line);
          }
        }
      }
      if (full || !selected.isEmpty()) {
        String displayed = full ? block.text() : String.join("\n", selected);
        // The Obsidian script retains a leading root link for one-line projections.
        if (!full && block.text().startsWith("[[") && !displayed.startsWith("[["))
          displayed = block.text().lines().findFirst().orElse("") + "\n" + displayed;
        result.add(new Projection(block, clean(displayed)));
      }
    }
    return List.copyOf(result);
  }
}
