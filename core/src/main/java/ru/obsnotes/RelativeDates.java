package ru.obsnotes;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Labels use the opened file's day, regardless of the wall clock or selected destination. */
public final class RelativeDates {
  private RelativeDates() {}

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yy · EEE", Locale.forLanguageTag("ru"));

  public static LocalDate date(String path) {
    return LocalDate.parse(DiaryLayout.fileName(path).substring(0, 10));
  }

  public static String label(LocalDate date, LocalDate origin) {
    long delta = ChronoUnit.DAYS.between(origin, date);
    if (delta == -2) return "Позавчера";
    if (delta == -1) return "Вчера";
    if (delta == 1) return "Завтра";
    if (delta == 2) return "Послезавтра";
    return date.format(FORMAT);
  }

  public static String label(String path, String origin) {
    return label(date(path), date(origin));
  }
}
