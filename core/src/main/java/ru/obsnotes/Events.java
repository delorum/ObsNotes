package ru.obsnotes;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

public final class Events {
  private Events() {}

  private static final Pattern ID = Pattern.compile("(?m)(?:^|\\s)\\^([A-Za-z0-9_-]+)[ \\t]*$");

  public record Root(String path, Markdown.Block block, String id) {
    public String label() {
      return DiaryLayout.fileName(path)
          + " / "
          + block.section().title()
          + " / "
          + CrossReferences.clean(block.preview());
    }
  }

  public static String id(String text) {
    Matcher m = ID.matcher(text);
    return m.find() ? m.group(1) : "";
  }

  /** A block reference alone is not a relationship root. */
  public static String rootId(String text) {
    String normalized = text.replace("\r\n", "\n");
    int callout = normalized.indexOf("\n> [!");
    if (callout < 0) return "";
    String script = normalized.substring(callout).toLowerCase(Locale.ROOT);
    if (!script.contains("```dataviewjs") || !script.contains("скрипты/dataview/outlinkinfo"))
      return "";
    Matcher anchor =
        Pattern.compile("(?:^|[ \\t])\\^([A-Za-z0-9_-]+)[ \\t]*$")
            .matcher(normalized.substring(0, callout).stripTrailing());
    return anchor.find() ? anchor.group(1) : "";
  }

  public record Parent(String path, String id) {}

  public static Optional<Parent> parent(String source, String text) {
    String first = text.lines().findFirst().orElse("").strip();
    Matcher link =
        Pattern.compile("^\\[\\[([^]|]*)#\\^([A-Za-z0-9_-]+)(?:\\|.*)?]]$").matcher(first);
    if (!link.matches()) return Optional.empty();
    String target = link.group(1).replaceFirst("\\.md$", "");
    if (target.startsWith("/") || Arrays.asList(target.split("/")).contains(".."))
      return Optional.empty();
    if (target.isEmpty()) target = source;
    else if (!target.contains("/") && target.matches("\\d{4}-\\d{2}-\\d{2}-[а-я]{2}")) {
      try {
        target = DiaryLayout.path(LocalDate.parse(target.substring(0, 10)));
      } catch (java.time.format.DateTimeParseException invalid) {
        return Optional.empty();
      }
    } else target += ".md";
    return Optional.of(new Parent(target, link.group(2)));
  }

  public static boolean hasRelations(String source, String text) {
    return !rootId(text).isEmpty() || parent(source, text).isPresent();
  }

  public static boolean references(String text, String from, String path, String id) {
    Optional<Parent> parent = parent(from, text);
    return parent.isPresent() && parent.get().path.equals(path) && parent.get().id.equals(id);
  }

  public static String root(String text) {
    if (text.isBlank()) throw new IllegalArgumentException("Сначала введите текст события");
    String existing = id(text);
    if (!rootId(text).isEmpty()) return text;
    int callout = text.indexOf("\n> [!");
    if (callout >= 0)
      throw new IllegalArgumentException("В блоке уже есть callout; измените его вручную");
    String id =
        existing.isEmpty()
            ? "event-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
            : existing;
    return text.stripTrailing()
        + (existing.isEmpty() ? " ^" + id : "")
        + "\n"
        + "> [!info]- Дополнения к событию\n"
        + "> ```dataviewjs\n"
        + ">     await dv.view(\"Скрипты/Dataview/outLinkInfo\", \""
        + id
        + "\");\n> ```";
  }

  public static String link(String text, Root root) {
    if (id(text).equals(root.id))
      throw new IllegalArgumentException("Нельзя связать блок с самим собой");
    String cleaned = text.replaceFirst("^\\[\\[[^\\n]*#\\^[^\\n]*]]\\r?\\n", "");
    String label =
        CrossReferences.clean(root.block.preview())
            .replace("|", "/")
            .replace("::", "")
            .replace("]]", "");
    return "[["
        + root.path.replaceFirst("\\.md$", "")
        + "#^"
        + root.id
        + "|"
        + label
        + "]]\n"
        + cleaned;
  }

  public static List<Markdown.Block> additions(Markdown document, String path, String id) {
    return document.blocks.stream()
        .filter(b -> references(b.text(), path, id))
        .collect(java.util.stream.Collectors.toList());
  }

  public static boolean references(String text, String path, String id) {
    Matcher links =
        Pattern.compile("\\[\\[([^]|]+)#\\^" + Pattern.quote(id) + "(?:\\|[^]]*)?]]").matcher(text);
    while (links.find()) {
      String target = links.group(1).replaceFirst("\\.md$", "");
      if (target.equals(path.replaceFirst("\\.md$", ""))
          || target.equals(DiaryLayout.fileName(path))) return true;
    }
    return false;
  }
}
