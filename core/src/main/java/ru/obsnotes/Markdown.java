package ru.obsnotes;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Lossless document model. Every operation changes explicit source ranges only. */
public final class Markdown {
  public static final Pattern HEADING = Pattern.compile("^(#{1,6}) +(.+?) *#* *$");
  public static final Pattern STAMP =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");
  public static final Pattern FIELD = Pattern.compile("^([\\p{L}\\d_-]+)::(.*)$");
  public static final Pattern TASK = Pattern.compile("^\\s*- \\[[ xX]\\].*");

  public record Section(String heading, String title, int level, int start, int body, int end) {}

  public record Block(int start, int end, Section section, String text) {
    public String preview() {
      return text.lines().filter(s -> !s.startsWith("> ")).findFirst().orElse(text);
    }
  }

  public record Target(String label, String heading, int line, String source, Kind kind) {}

  public enum Kind {
    SECTION,
    TASK,
    FIELD
  }

  public final String text;
  public final String newline;
  public final List<Section> sections;
  public final List<Block> blocks;

  public Markdown(String text) {
    this.text = text;
    newline = text.contains("\r\n") ? "\r\n" : "\n";
    List<String> lines = text.lines().collect(java.util.stream.Collectors.toList());
    List<Integer> offsets = new ArrayList<>();
    int offset = 0;
    for (String line : lines) {
      offsets.add(offset);
      offset += line.length() + newline.length();
    }
    List<Section> ss = new ArrayList<>();
    boolean fence = false;
    char fenceChar = 0;
    int fenceLength = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      Matcher f = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$").matcher(line);
      if (f.matches()) {
        if (!fence) {
          fence = true;
          fenceChar = f.group(1).charAt(0);
          fenceLength = f.group(1).length();
        } else if (f.group(1).charAt(0) == fenceChar && f.group(1).length() >= fenceLength)
          fence = false;
        continue;
      }
      if (fence) continue;
      Matcher h = HEADING.matcher(line);
      if (h.matches())
        ss.add(
            new Section(
                line,
                h.group(2),
                h.group(1).length(),
                offsets.get(i),
                Math.min(text.length(), offsets.get(i) + line.length() + newline.length()),
                text.length()));
    }
    for (int i = 0; i < ss.size(); i++) {
      Section s = ss.get(i);
      ss.set(
          i,
          new Section(
              s.heading,
              s.title,
              s.level,
              s.start,
              s.body,
              i + 1 < ss.size() ? ss.get(i + 1).start : text.length()));
    }
    sections = List.copyOf(ss);
    List<Block> bs = new ArrayList<>();
    for (Section s : sections) {
      int start = -1;
      boolean inFence = false, generated = false;
      char marker = 0;
      int markerLength = 0;
      for (int i = 0; i < lines.size(); i++) {
        int pos = offsets.get(i);
        if (pos < s.body || pos >= s.end) continue;
        String line = lines.get(i);
        Matcher code = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(line);
        boolean boundary = code.matches(), wasFence = inFence, wasGenerated = generated;
        if (boundary) {
          if (!inFence) {
            inFence = true;
            marker = code.group(1).charAt(0);
            markerLength = code.group(1).length();
            generated = code.group(2).trim().matches("dataviewjs|dataview|tasks");
          } else if (code.group(1).charAt(0) == marker
              && code.group(1).length() >= markerLength
              && code.group(2).isBlank()) {
            inFence = false;
            generated = false;
          }
        }
        boolean structural =
            generated
                || wasGenerated
                || !inFence && !wasFence && STAMP.matcher(line.strip()).matches();
        boolean stop = structural || line.isBlank() && !inFence && !wasFence;
        if (stop && start >= 0) {
          bs.add(block(start, pos, s));
          start = -1;
        }
        if (!stop && start < 0) start = pos;
      }
      if (start >= 0) bs.add(block(start, s.end, s));
    }
    blocks = List.copyOf(bs);
  }

  private Block block(int start, int end, Section s) {
    while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
    return new Block(start, end, s, text.substring(start, end));
  }

  public Section section(String heading) {
    List<Section> matches =
        sections.stream()
            .filter(s -> s.heading.equals(heading))
            .collect(java.util.stream.Collectors.toList());
    if (matches.size() > 1) throw new IllegalArgumentException("Повторяющийся раздел: " + heading);
    return matches.isEmpty() ? null : matches.get(0);
  }

  public Markdown replace(Block block, String replacement) {
    if (block.start < 0
        || block.end > text.length()
        || !text.substring(block.start, block.end).equals(block.text))
      throw new IllegalArgumentException("Блок изменился");
    return new Markdown(
        text.substring(0, block.start)
            + replacement.replace("\r\n", "\n").replace("\n", newline)
            + text.substring(block.end));
  }

  public Markdown append(String heading, String value, Markdown template) {
    if (value.isBlank()) throw new IllegalArgumentException("Пустой блок");
    Section s = section(heading);
    if (s == null) {
      Section original = template.section(heading);
      if (original == null) throw new IllegalArgumentException("Неизвестный раздел: " + heading);
      List<Section> parents = new ArrayList<>();
      int level = original.level;
      for (int i = template.sections.indexOf(original) - 1; i >= 0; i--) {
        Section candidate = template.sections.get(i);
        if (candidate.level < level) {
          parents.add(0, candidate);
          level = candidate.level;
        }
      }
      for (Section parent : parents)
        if (section(parent.heading) == null)
          return restore(parent, template).append(heading, value, template);
      return restore(original, template).append(heading, value, template);
    }
    int at = s.end;
    while (at > s.body && Character.isWhitespace(text.charAt(at - 1))) at--;
    return new Markdown(
        text.substring(0, at)
            + newline
            + newline
            + value.replace("\r\n", "\n").replace("\n", newline)
            + newline
            + newline
            + text.substring(s.end));
  }

  private Markdown restore(Section original, Markdown template) {
    // Restore missing cleaned-up sections at their position in the template.
    int insertion = text.length();
    List<Section> order = template.sections;
    for (int i = order.indexOf(original) + 1; i < order.size(); i++) {
      Section next = section(order.get(i).heading);
      if (next != null) {
        insertion = next.start;
        break;
      }
    }
    String boilerplate = original.heading;
    Matcher view =
        Pattern.compile(
                "(?s)```dataviewjs\\R(?:(?!```).)*Скрипты/Dataview/crossPeople(?:(?!```).)*```")
            .matcher(template.text.substring(original.body, original.end));
    if (view.find()) boilerplate += "\n\n" + view.group();
    String prefix = text.substring(0, insertion);
    String added =
        (prefix.endsWith(newline + newline) || prefix.isEmpty() ? "" : newline)
            + boilerplate.replace("\r\n", "\n").replace("\n", newline)
            + newline
            + newline;
    return new Markdown(prefix + added + text.substring(insertion));
  }

  public String timestamp(LocalDateTime now) {
    Section s = section("## Заметки");
    if (s == null) return now.format(DiaryLayout.TIMESTAMP) + "\n";
    LocalDateTime last = null;
    for (String line :
        text.substring(s.body, s.end).lines().collect(java.util.stream.Collectors.toList())) {
      if (STAMP.matcher(line.strip()).matches()) {
        try {
          last = LocalDateTime.parse(line.strip(), DiaryLayout.TIMESTAMP);
        } catch (java.time.format.DateTimeParseException ignored) {
          /* invalid timestamps are ordinary text */
        }
      }
    }
    return last == null || Math.abs(java.time.Duration.between(last, now).toMinutes()) > 10
        ? now.format(DiaryLayout.TIMESTAMP) + "\n"
        : "";
  }

  public List<Target> targets(Markdown template) {
    List<Target> result = new ArrayList<>();
    for (Section s : template.sections)
      result.add(new Target(s.title, s.heading, -1, "", Kind.SECTION));
    for (Section s : sections) {
      if (result.stream().noneMatch(t -> t.heading.equals(s.heading)))
        result.add(new Target(s.title, s.heading, -1, "", Kind.SECTION));
      int at = s.body;
      boolean fence = false;
      for (String line : text.substring(s.body, s.end).split("\\r?\\n", -1)) {
        if (line.startsWith("```") || line.startsWith("~~~")) fence = !fence;
        if (!fence && (TASK.matcher(line).matches() || FIELD.matcher(line).matches()))
          result.add(
              new Target(
                  s.title + " / " + line,
                  s.heading,
                  at,
                  line,
                  TASK.matcher(line).matches() ? Kind.TASK : Kind.FIELD));
        at += line.length() + newline.length();
      }
    }
    return result;
  }

  public Markdown insert(Target target, String value, LocalDate date, Markdown template) {
    if (target.kind == Kind.SECTION) return append(target.heading, value, template);
    if (value.contains("\n"))
      throw new IllegalArgumentException("Для задачи или ключа нужен однострочный блок");
    if (target.line < 0 || !text.startsWith(target.source, target.line))
      throw new IllegalArgumentException("Назначение изменилось");
    String result;
    if (target.kind == Kind.TASK) {
      String line = target.source.replaceFirst("\\[ \\]", "[x]");
      Matcher done = Pattern.compile("(\\s*✅\\s*\\d{4}-\\d{2}-\\d{2})\\s*$").matcher(line);
      String marker = done.find() ? done.group(1) : " ✅ " + date;
      if (done.find(0)) line = line.substring(0, done.start());
      if (!value.isBlank()
          && !line.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)))
        line += "; " + value;
      result = line + marker;
    } else {
      Matcher field = FIELD.matcher(target.source);
      if (!field.matches()) throw new IllegalArgumentException("Неизвестный ключ");
      String previous = field.group(2).trim().replaceAll("Заполни [^!]*!", "").trim();
      result = field.group(1) + ":: " + (previous.isEmpty() ? "" : previous + "; ") + value;
    }
    return new Markdown(
        text.substring(0, target.line)
            + result
            + text.substring(target.line + target.source.length()));
  }
}
