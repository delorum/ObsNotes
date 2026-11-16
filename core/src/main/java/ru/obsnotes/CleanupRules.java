package ru.obsnotes;

import java.io.*;
import java.util.*;

/** The vault owns the marker list; missing configuration never enables word deletion. */
public final class CleanupRules {
  public static final String PATH = "Скрипты/Настройки/Сжатие дневника.md";
  public static final String HEADING = "## Пустые маркеры";
  public static final CleanupRules EMPTY = new CleanupRules(Set.of());
  public final Set<String> markers;

  private CleanupRules(Set<String> markers) {
    this.markers = Set.copyOf(markers);
  }

  public static CleanupRules parse(String source) {
    Set<String> markers = new LinkedHashSet<>();
    boolean found = false, active = false;
    for (var line : MarkdownLines.scan(source.replaceFirst("^\\uFEFF", ""))) {
      if (line.literal()) continue;
      String text = line.text().strip();
      if (text.equals(HEADING)) {
        if (found) throw new IllegalArgumentException("Повторяющийся раздел настроек: " + HEADING);
        found = active = true;
      } else if (Markdown.HEADING.matcher(text).matches()) active = false;
      else if (active && text.startsWith("- ")) {
        String marker = text.substring(2).strip().toLowerCase(Locale.ROOT);
        if (!marker.isEmpty()) markers.add(marker);
      }
    }
    if (!found)
      throw new IllegalArgumentException("В " + PATH + " не найден раздел «Пустые маркеры»");
    return new CleanupRules(markers);
  }

  public static CleanupRules load(VaultStorage storage) throws IOException {
    final String source;
    try {
      source = storage.read(PATH);
    } catch (FileNotFoundException missing) {
      return EMPTY;
    }
    try {
      return parse(source);
    } catch (IllegalArgumentException invalid) {
      throw new IOException(invalid.getMessage(), invalid);
    }
  }
}
