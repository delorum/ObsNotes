package ru.obsnotes;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** diary is the UI spelling; dairy is the existing vault's historical tag spelling. */
public final class DiaryTasks {
  private DiaryTasks() {}

  private static final Pattern TAG =
      Pattern.compile("(?:^|\\s)#task/(?:diary|dairy)(?:/[\\p{L}\\d_-]+)*(?=\\s|$)");
  private static final Pattern DONE = Pattern.compile("[ \\t]*✅[ \\t]*\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern ANCHOR = Pattern.compile("([ \\t]+\\^[A-Za-z0-9_-]+[ \\t]*)$");

  public record Task(int start, String source, boolean checked) {
    public String label() {
      String body = source.replaceFirst("^\\s*- \\[[ xX]\\]\\s*", "");
      return CrossReferences.clean(withoutDone(TAG.matcher(body).replaceAll(""))).strip();
    }
  }

  public static boolean isDiary(String line) {
    return Markdown.TASK.matcher(line).matches() && TAG.matcher(line).find();
  }

  public static List<Task> in(String text, int base) {
    List<Task> result = new ArrayList<>();
    for (var line : MarkdownLines.scan(text))
      if (!line.literal() && isDiary(line.text()))
        result.add(
            new Task(base + line.start(), line.text(), !line.text().matches("^\\s*- \\[ \\].*")));
    return List.copyOf(result);
  }

  public static Optional<Task> projectedLine(List<Task> tasks, String line) {
    String visible = CrossReferences.clean(line);
    var matches =
        tasks.stream()
            .filter(t -> CrossReferences.clean(t.source()).equals(visible))
            .collect(java.util.stream.Collectors.toList());
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }

  public static String withoutDone(String line) {
    return DONE.matcher(line).replaceAll("").stripTrailing();
  }

  public static String addition(String text) {
    return InlineFields.singleLine(text);
  }

  public static String appendBeforeAnchor(String line, String addition) {
    var anchor = ANCHOR.matcher(line);
    int at = anchor.find() ? anchor.start() : line.length();
    return line.substring(0, at) + addition + line.substring(at);
  }

  public static String doneDate(String line) {
    var m = Pattern.compile("✅[ \\t]*(\\d{4}-\\d{2}-\\d{2})").matcher(line);
    return m.find() ? m.group(1) : "";
  }

  public static String mark(String line, boolean checked, LocalDate date) {
    String plain = withoutDone(line).replaceFirst("\\[[ xX]\\]", checked ? "[x]" : "[ ]");
    if (!checked) return plain;
    var anchor = ANCHOR.matcher(plain);
    int at = anchor.find() ? anchor.start() : plain.length();
    return plain.substring(0, at) + " ✅ " + date + plain.substring(at);
  }

  public static Markdown toggle(Markdown source, Task task, LocalDate date) {
    var actual =
        in(source.text, 0).stream()
            .filter(t -> t.start() == task.start() && t.source().equals(task.source()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Задача изменилась"));
    String changed = mark(actual.source(), !actual.checked(), date);
    return new Markdown(
        source.text.substring(0, task.start())
            + changed
            + source.text.substring(task.start() + task.source().length()));
  }
}
