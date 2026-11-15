package ru.obsnotes;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

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
    boolean fence = false;
    char marker = 0;
    int length = 0;
    for (String line : source.split("\\r?\\n")) {
      Matcher code = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(line);
      if (code.matches()) {
        if (!fence) {
          fence = true;
          marker = code.group(1).charAt(0);
          length = code.group(1).length();
        } else if (code.group(1).charAt(0) == marker
            && code.group(1).length() >= length
            && code.group(2).isBlank()) fence = false;
        continue;
      }
      if (fence) continue;
      String value = line.strip();
      if (value.startsWith("[") && value.endsWith("]"))
        value = value.substring(1, value.length() - 1).strip();
      if (value.startsWith("ShortDescr::")) return value.substring("ShortDescr::".length()).strip();
    }
    return "";
  }
}
