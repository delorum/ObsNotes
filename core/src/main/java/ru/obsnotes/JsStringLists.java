package ru.obsnotes;

import java.util.*;

/** Reads literal string arrays from a vault module without executing JavaScript. */
final class JsStringLists {
  private final String source;
  private int at;

  private JsStringLists(String source) {
    this.source = source;
  }

  static List<String> read(String source, String name) {
    JsStringLists parser = new JsStringLists(source);
    while (parser.trivia() < source.length()) {
      char c = source.charAt(parser.at);
      if (c == '\'' || c == '"' || c == '`') {
        parser.string();
        continue;
      }
      String word = parser.word();
      if (word.isEmpty()) {
        parser.at++;
        continue;
      }
      if (!Set.of("const", "let", "var").contains(word)) continue;
      parser.trivia();
      if (!parser.word().equals(name)) continue;
      parser.expect('=');
      parser.expect('[');
      List<String> result = new ArrayList<>();
      while (parser.trivia() < source.length() && source.charAt(parser.at) != ']') {
        char quote = source.charAt(parser.at);
        if (quote != '\'' && quote != '"') throw parser.invalid(name);
        result.add(parser.string());
        parser.trivia();
        if (parser.at < source.length() && source.charAt(parser.at) == ']') break;
        parser.expect(',');
      }
      parser.expect(']');
      return List.copyOf(result);
    }
    throw parser.invalid(name);
  }

  private IllegalArgumentException invalid(String name) {
    return new IllegalArgumentException(
        "Не удалось прочитать список " + name + " в diaryCleanUtils.js");
  }

  private int trivia() {
    while (at < source.length()) {
      if (Character.isWhitespace(source.charAt(at))) {
        at++;
        continue;
      }
      if (source.startsWith("//", at)) {
        while (at < source.length() && source.charAt(at) != '\n') at++;
      } else if (source.startsWith("/*", at)) {
        int end = source.indexOf("*/", at + 2);
        if (end < 0) throw invalid("комментарий");
        at = end + 2;
      } else break;
    }
    return at;
  }

  private String word() {
    int start = at;
    while (at < source.length()
        && (Character.isLetterOrDigit(source.charAt(at))
            || source.charAt(at) == '_'
            || source.charAt(at) == '$')) at++;
    return source.substring(start, at);
  }

  private void expect(char c) {
    trivia();
    if (at >= source.length() || source.charAt(at++) != c) throw invalid(Character.toString(c));
  }

  private String string() {
    char quote = source.charAt(at++);
    StringBuilder value = new StringBuilder();
    while (at < source.length()) {
      char c = source.charAt(at++);
      if (c == quote) return value.toString();
      if (c != '\\') {
        value.append(c);
        continue;
      }
      if (at == source.length()) throw invalid("строка");
      char escape = source.charAt(at++);
      switch (escape) {
        case 'n' -> value.append('\n');
        case 'r' -> value.append('\r');
        case 't' -> value.append('\t');
        case 'b' -> value.append('\b');
        case 'f' -> value.append('\f');
        case 'v' -> value.append('\u000b');
        case '\n' -> {}
        case '\r' -> {
          if (at < source.length() && source.charAt(at) == '\n') at++;
        }
        case 'u', 'x' -> {
          int length = escape == 'u' ? 4 : 2;
          if (at + length > source.length()) throw invalid("escape");
          try {
            value.append((char) Integer.parseInt(source.substring(at, at + length), 16));
          } catch (NumberFormatException invalid) {
            throw invalid("escape");
          }
          at += length;
        }
        default -> value.append(escape);
      }
    }
    throw invalid("строка");
  }
}
