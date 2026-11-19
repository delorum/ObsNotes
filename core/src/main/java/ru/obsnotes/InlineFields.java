package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** All field edits use actual lines in the snapshot, never a rendered key in an input. */
public final class InlineFields {
  private InlineFields() {}

  /** A value occupies one physical Markdown line; paragraph splitting happens before this. */
  public static String singleLine(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "; ");
  }

  private static final Pattern FIELD = Pattern.compile("^([\\p{L}\\d_-]+)::([ \\t]*)(.*)$");
  private static final Pattern ANCHOR = Pattern.compile("[ \\t]+\\^[A-Za-z0-9_-]+[ \\t]*$");

  public record Field(String key, String value, int start, String source) {}

  public static List<Field> all(Markdown doc) {
    List<Field> result = new ArrayList<>();
    for (var line : MarkdownLines.scan(doc.text)) {
      var m = FIELD.matcher(line.text());
      if (!line.literal() && m.matches())
        result.add(new Field(m.group(1), m.group(3), line.start(), line.text()));
    }
    return List.copyOf(result);
  }

  public static String value(Markdown doc, String key) {
    var fields =
        all(doc).stream()
            .filter(f -> f.key().equals(key))
            .collect(java.util.stream.Collectors.toList());
    if (fields.size() > 1) throw new IllegalArgumentException("Повторяющийся ключ: " + key);
    if (fields.isEmpty()) return "";
    return ANCHOR.matcher(fields.get(0).value()).replaceFirst("");
  }

  public static String append(String current, String addition) {
    String old = current.replaceAll("Заполни[^!\\n]*!", "").strip(), fresh = addition.strip();
    if (fresh.isEmpty() || old.toLowerCase(Locale.ROOT).contains(fresh.toLowerCase(Locale.ROOT)))
      return old;
    return old.isEmpty() ? fresh : old + "; " + fresh;
  }

  public static Markdown write(
      Markdown doc, Map<String, String> values, String heading, Markdown template) {
    List<Field> fields = all(doc);
    List<Field> edits = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (var entry : values.entrySet()) {
      if (entry.getValue().contains("\n") || entry.getValue().contains("\r"))
        throw new IllegalArgumentException("Для ключа нужно одно значение");
      var found =
          fields.stream()
              .filter(f -> f.key().equals(entry.getKey()))
              .collect(java.util.stream.Collectors.toList());
      if (found.size() > 1)
        throw new IllegalArgumentException("Повторяющийся ключ: " + entry.getKey());
      if (found.isEmpty()) missing.add(entry.getKey() + ":: " + entry.getValue());
      else edits.add(found.get(0));
    }
    edits.sort(Comparator.comparingInt(Field::start).reversed());
    StringBuilder result = new StringBuilder(doc.text);
    for (var field : edits) {
      String value = values.get(field.key());
      var anchor = ANCHOR.matcher(field.value());
      if (anchor.find()) value += anchor.group();
      result.replace(
          field.start(), field.start() + field.source().length(), field.key() + ":: " + value);
    }
    Markdown out = new Markdown(result.toString());
    if (!missing.isEmpty()) out = out.append(heading, String.join("\n", missing), template);
    return out;
  }
}
