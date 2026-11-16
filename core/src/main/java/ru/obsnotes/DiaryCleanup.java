package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Diary whitespace and orphan-marker rules, without altering code, callouts or frontmatter. */
public final class DiaryCleanup {
  private DiaryCleanup() {}

  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

  public static String compress(String text) {
    return compress(text, CleanupRules.EMPTY);
  }

  public static String compress(String text, CleanupRules rules) {
    String newline = text.contains("\r\n") ? "\r\n" : "\n";
    boolean finalNewline = text.endsWith("\n");
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
    if (finalNewline) lines.remove(lines.size() - 1);
    boolean[] protectedLine = new boolean[lines.size()];
    boolean yaml = false, fence = false;
    char marker = 0;
    int length = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (i == 0 && line.equals("---")) {
        yaml = true;
        protectedLine[i] = true;
        continue;
      }
      if (yaml) {
        protectedLine[i] = true;
        if (line.equals("---") || line.equals("...")) yaml = false;
        continue;
      }
      Matcher code = FENCE.matcher(line);
      if (code.matches()) {
        protectedLine[i] = true;
        if (!fence) {
          fence = true;
          marker = code.group(1).charAt(0);
          length = code.group(1).length();
        } else if (code.group(1).charAt(0) == marker
            && code.group(1).length() >= length
            && code.group(2).isBlank()) fence = false;
        continue;
      }
      protectedLine[i] = fence || line.stripLeading().startsWith(">");
    }
    List<Integer> kept = new ArrayList<>();
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i), trimmed = line.strip();
      String next = kept.isEmpty() ? "" : lines.get(kept.get(kept.size() - 1));
      if (!protectedLine[i]) {
        if (rules.markers.contains(trimmed.toLowerCase(Locale.ROOT)) && next.isBlank()) continue;
        if (Markdown.STAMP.matcher(trimmed).matches()) {
          boolean orphan = next.isBlank();
          if (!orphan) {
            String n = next.strip();
            orphan =
                Markdown.STAMP.matcher(n).matches()
                    || Markdown.HEADING.matcher(n).matches()
                    || n.equals("*конец заметок*");
          }
          if (orphan) continue;
        }
        if (line.isBlank()
            && !kept.isEmpty()
            && !protectedLine[kept.get(kept.size() - 1)]
            && next.isBlank()) continue;
      }
      kept.add(i);
    }
    Collections.reverse(kept);
    List<String> result = new ArrayList<>();
    for (int i : kept) result.add(!protectedLine[i] && lines.get(i).isBlank() ? "" : lines.get(i));
    return String.join(newline, result) + (finalNewline && !result.isEmpty() ? newline : "");
  }
}
