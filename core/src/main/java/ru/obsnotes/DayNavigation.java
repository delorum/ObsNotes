package ru.obsnotes;

import java.io.*;
import java.time.LocalDate;

/** A navigation attempt never replaces the displayed day before its file has been read. */
public final class DayNavigation {
  private DayNavigation() {}

  public static final String NOT_FOUND = "Заметка не найдена";

  public record View(LocalDate date, String path, Markdown document) {}

  public record Result(View view, String error) {
    public boolean opened() {
      return error == null;
    }
  }

  /** Probe only the next date's exact path; never search the archive or parse its content. */
  public static boolean hasNext(VaultStorage storage, LocalDate current) throws IOException {
    return storage.exists(DiaryLayout.path(current.plusDays(1)));
  }

  public static Result open(VaultStorage storage, View current, LocalDate requested)
      throws IOException {
    String path = DiaryLayout.path(requested);
    try {
      return new Result(new View(requested, path, new Markdown(storage.read(path))), null);
    } catch (IOException error) {
      if (missing(error)) return new Result(current, NOT_FOUND);
      throw error;
    }
  }

  public static boolean missing(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof FileNotFoundException) return true;
      if (cause == cause.getCause()) break;
    }
    return false;
  }

  public static String message(Throwable error) {
    return missing(error)
        ? NOT_FOUND
        : error.getMessage() == null ? "Не удалось открыть заметку" : error.getMessage();
  }
}
