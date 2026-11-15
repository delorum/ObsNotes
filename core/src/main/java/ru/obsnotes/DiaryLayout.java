package ru.obsnotes;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;

public final class DiaryLayout {
  private DiaryLayout() {}

  private static final String[] MONTHS = {
    "январь",
    "февраль",
    "март",
    "апрель",
    "май",
    "июнь",
    "июль",
    "август",
    "сентябрь",
    "октябрь",
    "ноябрь",
    "декабрь"
  };
  private static final String[] DAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
  public static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

  public static String name(LocalDate date) {
    return date + "-" + DAYS[date.getDayOfWeek().getValue() - 1];
  }

  public static String path(LocalDate date) {
    // Moment's Russian locale uses Monday / first week containing January 4.
    return "Дневник/Хроника/"
        + date.getYear()
        + "/"
        + ((date.getMonthValue() - 1) / 3 + 1)
        + "/"
        + String.format(java.util.Locale.ROOT, "%02d", date.getMonthValue())
        + "-"
        + MONTHS[date.getMonthValue() - 1]
        + "/"
        + date.get(WeekFields.ISO.weekBasedYear())
        + "-"
        + date.get(WeekFields.ISO.weekOfWeekBasedYear())
        + "-неделя/"
        + name(date)
        + ".md";
  }

  public static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.md$", "");
  }
}
