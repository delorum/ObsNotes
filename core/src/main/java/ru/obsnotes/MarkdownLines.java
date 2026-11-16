package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Offset-preserving structural lines; code and frontmatter are never task/field targets. */
public final class MarkdownLines {
  private MarkdownLines() {}

  public record Line(int start, String text, boolean literal) {}

  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

  public static List<Line> scan(String text) {
    List<Line> result = new ArrayList<>();
    boolean yaml = false;
    char marker = 0;
    int length = 0, offset = 0, index = 0;
    for (String raw : text.split("\n", -1)) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      boolean literal = marker != 0 || yaml;
      if (index == 0 && line.equals("---")) {
        yaml = true;
        literal = true;
      } else if (yaml) {
        literal = true;
        if (line.equals("---") || line.equals("...")) yaml = false;
      } else {
        var fence = FENCE.matcher(line);
        if (fence.matches()) {
          literal = true;
          if (marker == 0) {
            marker = fence.group(1).charAt(0);
            length = fence.group(1).length();
          } else if (marker == fence.group(1).charAt(0)
              && fence.group(1).length() >= length
              && fence.group(2).isBlank()) marker = 0;
        }
      }
      result.add(new Line(offset, line, literal));
      offset += raw.length() + 1;
      index++;
    }
    return List.copyOf(result);
  }
}
