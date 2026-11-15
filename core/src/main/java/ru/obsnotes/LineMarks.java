package ru.obsnotes;

import java.util.*;

/** Only tagged lines consume a color, so inserting ordinary lines does not change their colors. */
public final class LineMarks {
  private LineMarks() {}

  public record Mark(int line, int start, int end, int colorIndex) {}

  public static List<Mark> of(String text, List<TaggedText.Tag> tags) {
    SortedSet<Integer> lines = new TreeSet<>();
    for (var tag : tags) if (tag.oneLine()) lines.add(tag.line(text));
    List<Mark> result = new ArrayList<>();
    int color = 0;
    for (int line : lines) {
      int start = 0;
      for (int i = 1; i < line; i++) {
        int next = text.indexOf('\n', start);
        start = next < 0 ? text.length() : next + 1;
      }
      int end = text.indexOf('\n', start);
      if (end < 0) end = text.length();
      result.add(new Mark(line, start, end, color++));
    }
    return List.copyOf(result);
  }
}
