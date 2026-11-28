package ru.obsnotes;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

public final class Events {
  private Events() {}

  private static final Pattern ID = Pattern.compile("(?m)(?:^|\\s)\\^([A-Za-z0-9_-]+)[ \\t]*$");

  public record Root(String path, Markdown.Block block, String id) {
    public String label() {
      return DiaryLayout.fileName(path) + " / " + block.section().title() + " / " + caption(block);
    }

    public String visibleText(DayContentRules rules) {
      String source = block.text();
      int callout = rootCalloutStart(source);
      if (!id.isEmpty() && callout >= 0) source = source.substring(0, callout);
      return rules.view(source).text();
    }

    public String label(DayContentRules rules) {
      return DiaryLayout.fileName(path)
          + " / "
          + block.section().title()
          + " / "
          + caption(visibleText(rules));
    }
  }

  public static String id(String text) {
    for (var line : MarkdownLines.scan(text)) {
      if (line.literal() || line.text().stripLeading().startsWith(">")) continue;
      Matcher m = ID.matcher(line.text());
      if (m.find()) return m.group(1);
    }
    return "";
  }

  public static int rootCalloutStart(String text) {
    for (var line : MarkdownLines.scan(text))
      if (!line.literal() && line.start() > 0 && line.text().startsWith("> [!"))
        return line.start() - 1;
    return -1;
  }

  /** A block reference alone is not a relationship root. */
  public static String rootId(String text) {
    String normalized = text.replace("\r\n", "\n");
    int callout = rootCalloutStart(normalized);
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

  public record Leading(Parent parent, String label, String raw) {}

  public record Prefix(String raw, List<Leading> links, String body) {}

  private static final Pattern LINK =
      Pattern.compile("\\[\\[([^]|\\r\\n]*)#\\^([A-Za-z0-9_-]+)(?:\\|([^\\r\\n]*?))?]]");

  private static Parent resolve(String source, String target, String id) {
    target = target.replaceFirst("\\.md$", "");
    if (target.startsWith("/") || Arrays.asList(target.split("/")).contains("..")) return null;
    if (target.isEmpty()) target = source;
    else if (!target.contains("/") && target.matches("\\d{4}-\\d{2}-\\d{2}-[а-я]{2}")) {
      try {
        target = DiaryLayout.path(LocalDate.parse(target.substring(0, 10)));
      } catch (java.time.format.DateTimeParseException invalid) {
        return null;
      }
    } else target += ".md";
    return new Parent(target, id);
  }

  /** Only complete consecutive leading wiki-link lines are metadata, never inline/code links. */
  public static Prefix prefix(String source, String text) {
    List<Leading> links = new ArrayList<>();
    int end = 0;
    for (var line : MarkdownLines.scan(text)) {
      if (line.start() != end
          || line.literal()
          || line.text().startsWith("    ")
          || line.text().startsWith("\t")) break;
      Matcher m = LINK.matcher(line.text());
      List<Leading> row = new ArrayList<>();
      int at = 0;
      boolean valid = true;
      while (m.find()) {
        if (!line.text().substring(at, m.start()).isBlank()) {
          valid = false;
          break;
        }
        Parent p = resolve(source, m.group(1), m.group(2));
        if (p == null) {
          valid = false;
          break;
        }
        row.add(new Leading(p, m.group(3) == null ? "Корневой блок" : m.group(3), m.group()));
        at = m.end();
      }
      if (!valid || row.isEmpty() || !line.text().substring(at).isBlank()) break;
      links.addAll(row);
      end = line.start() + line.text().length();
      if (end < text.length() && text.charAt(end) == '\r') end++;
      if (end < text.length() && text.charAt(end) == '\n') end++;
    }
    return new Prefix(text.substring(0, end), List.copyOf(links), text.substring(end));
  }

  public static List<Parent> parents(String source, String text) {
    return prefix(source, text).links().stream().map(Leading::parent).toList();
  }

  public static Optional<Parent> parent(String source, String text) {
    return parents(source, text).stream().findFirst();
  }

  public static String caption(Markdown.Block block) {
    return caption(block.text());
  }

  static String caption(String text) {
    String label =
        CrossReferences.clean(text)
            .lines()
            .filter(
                line ->
                    !line.isBlank()
                        && !line.startsWith(">")
                        && !line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))
            .findFirst()
            .orElse("Корневой блок")
            .strip();
    return label.length() > 180 ? label.substring(0, 180) + "…" : label;
  }

  public static String format(Root root) {
    String label = caption(root.block).replace("|", "/").replace("::", "").replace("]]", "");
    return "[[" + root.path.replaceFirst("\\.md$", "") + "#^" + root.id + "|" + label + "]]";
  }

  public static String withLinks(String text, List<String> links) {
    String body = prefix("", text).body();
    String eol = text.contains("\r\n") ? "\r\n" : "\n";
    return links.isEmpty() ? body : String.join(eol, links) + eol + body;
  }

  public static String removeParents(
      String source, String text, java.util.function.Predicate<Parent> remove) {
    Prefix p = prefix(source, text);
    if (p.links().stream().noneMatch(link -> remove.test(link.parent()))) return text;
    return withLinks(
        text,
        p.links().stream().filter(link -> !remove.test(link.parent())).map(Leading::raw).toList());
  }

  /** Strip the generated root callout and its anchor, preserving other trailing contents. */
  public static String withoutRoot(String text, boolean removeAnchor) {
    String id = rootId(text);
    if (id.isEmpty()) return text;
    String eol = text.contains("\r\n") ? "\r\n" : "\n", value = text.replace("\r\n", "\n");
    int start = rootCalloutStart(value);
    int end = -1;
    boolean fence = false;
    for (var line : MarkdownLines.scan(value.substring(start + 1))) {
      String quoted = line.text().replaceFirst("^>[ \t]?", "").strip();
      if (quoted.startsWith("```dataviewjs")) fence = true;
      else if (fence && quoted.equals("```")) {
        end = start + 1 + line.start() + line.text().length();
        break;
      }
    }
    if (end < 0) throw new IllegalArgumentException("Не удалось определить границы callout корня");
    String before = value.substring(0, start);
    if (removeAnchor)
      before = before.replaceFirst("(?:^|[ \t]+)\\^" + Pattern.quote(id) + "[ \t]*$", "");
    return (before + value.substring(end)).replace("\n", eol);
  }

  /** Exact parent links are removed only in the edited file, preserving inline/literal content. */
  public static Markdown detach(Markdown document, String path, Set<Parent> removed) {
    Markdown result = document;
    List<Markdown.Block> blocks = new ArrayList<>(document.blocks);
    Collections.reverse(blocks);
    for (var block : blocks) {
      String changed = removeParents(path, block.text(), removed::contains);
      if (!changed.equals(block.text())) result = result.replace(block, changed);
    }
    return result;
  }

  public static boolean hasRelations(String source, String text) {
    return !rootId(text).isEmpty() || parent(source, text).isPresent();
  }

  public static boolean references(String text, String from, String path, String id) {
    return parents(from, text).stream()
        .anyMatch(parent -> parent.path.equals(path) && parent.id.equals(id));
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
    return withLinks(text, List.of(format(root)));
  }

  public static String addLink(String text, Root root) {
    Prefix p = prefix(root.path, text);
    Parent parent = new Parent(root.path, root.id);
    if (p.links().stream().anyMatch(link -> link.parent().equals(parent))) return text;
    List<String> links = new ArrayList<>(p.links().stream().map(Leading::raw).toList());
    links.add(format(root));
    return withLinks(text, links);
  }

  public static List<Markdown.Block> additions(Markdown document, String path, String id) {
    return document.blocks.stream()
        .filter(b -> references(b.text(), path, path, id))
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
