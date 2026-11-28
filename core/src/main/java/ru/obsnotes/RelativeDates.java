package ru.obsnotes;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Date navigation and list labels use the selected destination, independent of the wall clock. */
public final class RelativeDates {
  private RelativeDates() {}

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yy · EEE", Locale.forLanguageTag("ru"));

  public static LocalDate date(String path) {
    return LocalDate.parse(DiaryLayout.fileName(path).substring(0, 10));
  }

  private static final DateTimeFormatter BUTTON_FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT);

  public static String buttonLabel(String path) {
    return date(path).format(BUTTON_FORMAT);
  }

  public static LocalDate shortcut(String destinationPath, int days) {
    return date(destinationPath).plusDays(days);
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
