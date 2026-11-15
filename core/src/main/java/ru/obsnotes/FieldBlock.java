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
