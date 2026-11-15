package ru.obsnotes;

import java.util.Locale;

/** Case-insensitive ordered subsequence search shared by every search field. */
public final class FuzzySearch {
  private FuzzySearch() {}

  public static boolean matches(String text, String query) {
    String haystack = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    String needle = query.toLowerCase(Locale.ROOT).replace('ё', 'е');
    int at = 0;
    for (int cp : needle.codePoints().filter(c -> !Character.isWhitespace(c)).toArray()) {
      boolean found = false;
      while (at < haystack.length()) {
        int ch = haystack.codePointAt(at);
        at += Character.charCount(ch);
        if (ch == cp) {
          found = true;
          break;
        }
      }
      if (!found) return false;
    }
    return true;
  }
}
