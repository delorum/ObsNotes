package ru.obsnotes;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** Pages of calendar dates, addressed directly. Folder enumeration is never needed. */
public final class PastDays {
  private PastDays() {}

  public static final int PAGE_SIZE = 14;

  public record Day(LocalDate date, String path, String description) {}

  public record Page(List<Day> days, LocalDate nextDate) {}

  public static Page load(VaultStorage storage, LocalDate from) throws IOException {
    List<Day> days = new ArrayList<>();
    LocalDate date = from;
    for (int i = 0; i < PAGE_SIZE; i++) {
      String path = DiaryLayout.path(date);
      try {
        days.add(new Day(date, path, description(storage.read(path))));
      } catch (FileNotFoundException absent) {
        /* Only existing days are selectable. */
      }
      if (date.equals(LocalDate.of(1, 1, 1))) return new Page(List.copyOf(days), null);
      date = date.minusDays(1);
    }
    return new Page(List.copyOf(days), date);
  }

  public static String description(String source) {
    String text = source.startsWith("\ufeff") ? source.substring(1) : source;
    for (var line : MarkdownLines.scan(text)) {
      if (line.literal()) continue;
      String value = line.text().strip();
      if (value.startsWith("[") && value.endsWith("]"))
        value = value.substring(1, value.length() - 1).strip();
      if (value.startsWith("ShortDescr::")) return value.substring("ShortDescr::".length()).strip();
    }
    return "";
  }
}
