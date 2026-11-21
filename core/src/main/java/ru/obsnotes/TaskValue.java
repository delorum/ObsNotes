package ru.obsnotes;

import java.time.LocalDate;

/** A diary task's fixed title and status stay outside its editable, semicolon-delimited value. */
public record TaskValue(String shell, String value) {
  public static TaskValue parse(String source) {
    if (!DiaryTasks.isSupported(source)) throw new IllegalArgumentException("Неизвестная задача");
    String plain = DiaryTasks.withoutDone(source);
    var anchor =
        java.util.regex.Pattern.compile("([ \\t]+\\^[A-Za-z0-9_-]+[ \\t]*)$").matcher(plain);
    String suffix = anchor.find() ? anchor.group(1) : "";
    if (!suffix.isEmpty()) plain = plain.substring(0, anchor.start());
    int separator = plain.indexOf(';');
    String title = separator < 0 ? plain : plain.substring(0, separator).stripTrailing();
    String value = separator < 0 ? "" : plain.substring(separator + 1).strip();
    String date = DiaryTasks.doneDate(source);
    return new TaskValue(title + (date.isEmpty() ? "" : " ✅ " + date) + suffix, value);
  }

  public String label() {
    return new DiaryTasks.Task(0, shell, false).label();
  }

  public String render(String value, LocalDate date) {
    String addition = DiaryTasks.addition(value);
    String line = DiaryTasks.withoutDone(shell);
    if (!addition.isBlank()) line = DiaryTasks.appendBeforeAnchor(line, "; " + addition);
    boolean checked = !addition.isBlank() || !shell.matches("^\\s*- \\[ \\].*");
    return DiaryTasks.mark(line, checked, date);
  }
}
