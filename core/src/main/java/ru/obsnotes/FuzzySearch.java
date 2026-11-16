package ru.obsnotes;

import java.util.*;
import java.util.Locale;
import java.util.function.Function;

/** Case-insensitive ordered subsequence search shared by every search field. */
public final class FuzzySearch {
  private FuzzySearch() {}

  private static String normalized(String text) {
    return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
  }

  public static int rank(String text, String query) {
    String value = normalized(text), needle = normalized(query).strip();
    if (needle.isEmpty()) return 0;
    for (String part : value.split("[\\r\\n,]+")) if (part.strip().equals(needle)) return 0;
    if (value.contains(needle)) return 1;
    return matches(text, query) ? 2 : Integer.MAX_VALUE;
  }

  public static <T> List<T> ranked(Collection<T> items, String query, Function<T, String> label) {
    record Hit<T>(T item, int rank) {}
    List<Hit<T>> found = new ArrayList<>();
    for (T item : items) {
      int rank = rank(label.apply(item), query);
      if (rank != Integer.MAX_VALUE) found.add(new Hit<>(item, rank));
    }
    found.sort(Comparator.comparingInt(Hit::rank));
    return found.stream().map(Hit::item).collect(java.util.stream.Collectors.toList());
  }

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
