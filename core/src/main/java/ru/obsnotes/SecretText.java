package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Opaque inline secrets have a short editable-view placeholder and lossless Markdown backing. */
public final class SecretText {
  private SecretText() {}

  public static final String PLACEHOLDER = "⟦Зашифровано⟧";
  private static final Pattern SECRET = Pattern.compile("(?<!`)`secret[ \\t]+([^`\\r\\n]*)`(?!`)");

  public record Secret(int start, int end, String payload, String raw) {}

  public static List<Secret> scan(String text) {
    List<Secret> out = new ArrayList<>();
    for (var line : MarkdownLines.scan(text)) {
      if (line.literal()) continue;
      Matcher m = SECRET.matcher(line.text());
      while (m.find())
        out.add(
            new Secret(line.start() + m.start(), line.start() + m.end(), m.group(1), m.group()));
    }
    return List.copyOf(out);
  }

  private static final Pattern OPEN = Pattern.compile("`obsnotes-open ([A-Za-z0-9+/=]*)`");

  // An internal opaque envelope keeps decrypted Markdown from becoming editor metadata.
  // savedText() always removes it; it is never a vault format.
  static String opened(String clear) {
    return "`obsnotes-open "
        + Base64.getEncoder()
            .encodeToString(clear.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "`";
  }

  static String openBody(String body) {
    Matcher matcher = OPEN.matcher(body);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String clear;
      try {
        clear =
            new String(
                Base64.getDecoder().decode(matcher.group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
      } catch (IllegalArgumentException literal) {
        clear = matcher.group();
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(clear));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public static boolean has(String text) {
    return !scan(text).isEmpty();
  }

  public static String display(String raw) {
    StringBuilder out = new StringBuilder(raw);
    var list = scan(raw);
    for (int i = list.size() - 1; i >= 0; i--) {
      var s = list.get(i);
      out.replace(s.start(), s.end(), PLACEHOLDER);
    }
    return out.toString();
  }

  public static String decrypt(String text, char[] password) {
    StringBuilder out = new StringBuilder(text);
    var list = scan(text);
    for (int i = list.size() - 1; i >= 0; i--) {
      var s = list.get(i);
      out.replace(s.start(), s.end(), InlineCrypto.decrypt(s.payload(), password));
    }
    return out.toString();
  }

  /** A partial overwrite of a placeholder cannot damage the underlying ciphertext. */
  public static String edit(String raw, String next) {
    var secrets = scan(raw);
    if (secrets.isEmpty()) return next;
    String before = display(raw);
    if (before.equals(next)) return raw;
    List<Integer> markers = new ArrayList<>();
    for (int at = 0; (at = next.indexOf(PLACEHOLDER, at)) >= 0; at += PLACEHOLDER.length())
      markers.add(at);
    if (markers.size() == secrets.size()) {
      StringBuilder out = new StringBuilder(next);
      for (int i = markers.size() - 1; i >= 0; i--)
        out.replace(markers.get(i), markers.get(i) + PLACEHOLDER.length(), secrets.get(i).raw());
      return out.toString();
    }

    int prefix = 0;
    while (prefix < before.length()
        && prefix < next.length()
        && before.charAt(prefix) == next.charAt(prefix)) prefix++;
    int suffix = 0;
    while (suffix < before.length() - prefix
        && suffix < next.length() - prefix
        && before.charAt(before.length() - 1 - suffix) == next.charAt(next.length() - 1 - suffix))
      suffix++;
    int oldEnd = before.length() - suffix, newEnd = next.length() - suffix, shift = 0;
    record Restore(int at, String raw) {}
    List<Restore> restore = new ArrayList<>();
    for (var s : secrets) {
      int start = s.start() + shift, end = start + PLACEHOLDER.length();
      shift += PLACEHOLDER.length() - (s.end() - s.start());
      if (oldEnd <= start) restore.add(new Restore(start + newEnd - oldEnd, s.raw()));
      else if (prefix >= end) restore.add(new Restore(start, s.raw()));
      else if (prefix > start || oldEnd < end)
        throw new IllegalArgumentException("Сначала расшифруйте текст замком");
    }
    StringBuilder out = new StringBuilder(next);
    for (int i = restore.size() - 1; i >= 0; i--) {
      var s = restore.get(i);
      out.replace(s.at(), s.at() + PLACEHOLDER.length(), s.raw());
    }
    return out.toString();
  }
}
