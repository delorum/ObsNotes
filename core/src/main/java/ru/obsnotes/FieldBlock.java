package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Inline Dataview field names and separators are protected; only values are editable. */
public final class FieldBlock {
  private static final Pattern FIELD = Pattern.compile("^([ \\t]*([\\p{L}\\d_-]+)::[ \\t]*)(.*)$");

  public record Field(String key, String prefix, String value) {}

  private final List<Field> fields;

  private FieldBlock(List<Field> fields) {
    this.fields = List.copyOf(fields);
  }

  public static FieldBlock parse(String body) {
    List<Field> fields = new ArrayList<>();
    for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
      Matcher m = FIELD.matcher(line);
      if (!m.matches()) return new FieldBlock(List.of());
      fields.add(new Field(m.group(2), m.group(1), m.group(3)));
    }
    return new FieldBlock(fields);
  }

  public List<Field> fields() {
    return fields;
  }

  /** Turn active field lines into copy labels; literal fenced examples remain untouched. */
  public static String labels(String text) {
    String[] lines = text.split("\n", -1);
    char marker = 0;
    int length = 0;
    for (int i = 0; i < lines.length; i++) {
      var fence = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(lines[i]);
      if (fence.matches()) {
        if (marker == 0) {
          marker = fence.group(1).charAt(0);
          length = fence.group(1).length();
        } else if (fence.group(1).charAt(0) == marker
            && fence.group(1).length() >= length
            && fence.group(2).isBlank()) marker = 0;
        continue;
      }
      if (marker == 0 && FIELD.matcher(lines[i]).matches()) {
        int at = lines[i].indexOf("::");
        lines[i] = lines[i].substring(0, at) + lines[i].substring(at + 1);
      }
    }
    return String.join("\n", lines);
  }

  public boolean present() {
    return !fields.isEmpty();
  }

  public String values() {
    return String.join(
        "\n", fields.stream().map(Field::value).collect(java.util.stream.Collectors.toList()));
  }

  public String withValues(String values) {
    String[] lines = values.split("\n", -1);
    if (lines.length != fields.size())
      throw new IllegalArgumentException("Изменяйте значения полей по отдельности");
    List<String> result = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) result.add(fields.get(i).prefix() + lines[i]);
    return String.join("\n", result);
  }

  public String edit(int index, String rawValue) {
    List<String> values = new ArrayList<>();
    for (var f : fields) values.add(f.value());
    values.set(index, rawValue.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "; "));
    return withValues(String.join("\n", values));
  }
}
