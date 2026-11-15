package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Lossless meanings of hidden tags, anchored to clean text instead of Android widget offsets. */
public final class TaggedText {
  public record Tag(String kind, String ids, int position) {
    public boolean oneLine() {
      return kind.endsWith("OneLine");
    }

    public boolean person() {
      return kind.startsWith("person");
    }

    public int line(String text) {
      return (int)
              text.substring(0, Math.min(position, text.length()))
                  .chars()
                  .filter(c -> c == '\n')
                  .count()
          + 1;
    }
  }

  public final String clean;
  public final List<Tag> tags;

  private TaggedText(String clean, List<Tag> tags) {
    this.clean = clean;
    this.tags = List.copyOf(tags);
  }

  public static TaggedText parse(String raw) {
    Matcher m =
        Pattern.compile("([ \\t]*)#(crossPeople|crossOneLine|person|personOneLine)/([\\p{L}\\d]+)")
            .matcher(raw);
    StringBuilder clean = new StringBuilder();
    List<Tag> tags = new ArrayList<>();
    int from = 0;
    while (m.find()) {
      clean.append(raw, from, m.start());
      String kind = m.group(2), ids = m.group(3);
      if (kind.startsWith("person")) {
        clean.append(m.group(1));
        int at = clean.length();
        clean.append(ids);
        tags.add(new Tag(kind, ids, at));
      } else tags.add(new Tag(kind, ids, clean.length()));
      from = m.end();
    }
    clean.append(raw.substring(from));
    return new TaggedText(clean.toString(), tags);
  }

  private static int map(int position, int prefix, int oldEnd, int newEnd, boolean right) {
    if (position < prefix) return position;
    if (position > oldEnd) return position + newEnd - oldEnd;
    if (position == prefix && prefix == oldEnd) return right ? newEnd : prefix;
    return position == oldEnd ? newEnd : prefix;
  }

  public TaggedText edit(String value) {
    int prefix = 0;
    while (prefix < clean.length()
        && prefix < value.length()
        && clean.charAt(prefix) == value.charAt(prefix)) prefix++;
    int suffix = 0;
    while (suffix < clean.length() - prefix
        && suffix < value.length() - prefix
        && clean.charAt(clean.length() - 1 - suffix) == value.charAt(value.length() - 1 - suffix))
      suffix++;
    int oldEnd = clean.length() - suffix, newEnd = value.length() - suffix;
    List<Tag> mapped = new ArrayList<>();
    for (Tag tag : tags) {
      int at =
          Math.max(
              0, Math.min(value.length(), map(tag.position, prefix, oldEnd, newEnd, tag.person())));
      if (tag.person()) {
        if (at + tag.ids.length() > value.length() || !value.startsWith(tag.ids, at)) continue;
      } else if (tag.oneLine()) {
        int lineStart = clean.lastIndexOf('\n', Math.max(0, tag.position - 1)) + 1;
        int lineEnd = clean.indexOf('\n', tag.position);
        if (lineEnd < 0) lineEnd = clean.length();
        if (prefix <= lineStart
            && oldEnd >= lineEnd
            && clean.substring(prefix, oldEnd).contains("\n")) continue;
      }
      mapped.add(new Tag(tag.kind, tag.ids, at));
    }
    return new TaggedText(value, mapped);
  }

  public TaggedText add(int cursor, Collection<String> ids, boolean oneLine, boolean person) {
    TaggedText addition = parse(CrossReferences.tag(clean, cursor, ids, oneLine, person));
    List<Tag> combined = new ArrayList<>(edit(addition.clean).tags);
    combined.addAll(addition.tags);
    List<Tag> unique = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (Tag t : combined)
      if (keys.add(t.kind + "/" + t.ids + "/" + t.line(addition.clean))) unique.add(t);
    return new TaggedText(addition.clean, unique);
  }

  public TaggedText remove(int index) {
    List<Tag> kept = new ArrayList<>(tags);
    kept.remove(index);
    return new TaggedText(clean, kept);
  }

  /** Replace an assignment at its stored position, independent of the editor cursor. */
  public TaggedText replace(int index, List<String> ids) {
    Tag old = tags.get(index);
    for (String id : ids)
      if (!id.matches("[\\p{L}\\d]+")) throw new IllegalArgumentException("Неверный идентификатор");
    if (String.join("", ids).equals(old.ids())) return this;
    if (!old.person()) {
      List<Tag> next = new ArrayList<>(tags);
      if (ids.isEmpty()) next.remove(index);
      else next.set(index, new Tag(old.kind(), String.join("", ids), old.position()));
      return withTags(next);
    }
    TaggedText result = remove(index);
    for (String id : ids) result = result.add(old.position(), List.of(id), old.oneLine(), true);
    return result;
  }

  public TaggedText withTags(List<Tag> replacement) {
    return new TaggedText(clean, replacement);
  }

  public String render() {
    record Insertion(int at, int order, String value) {}
    List<Insertion> inserts = new ArrayList<>();
    for (int i = 0; i < tags.size(); i++) {
      Tag t = tags.get(i);
      int at = Math.min(clean.length(), t.position);
      if (t.person()) {
        if (clean.startsWith(t.ids, at)) inserts.add(new Insertion(at, i, "#" + t.kind + "/"));
      } else {
        int end = clean.indexOf('\n', at);
        if (end < 0) end = clean.length();
        int start = clean.lastIndexOf('\n', Math.max(0, end - 1)) + 1;
        Matcher anchor =
            Pattern.compile("(?:^|[ \\t]+)\\^[A-Za-z0-9_-]+[ \\t]*$")
                .matcher(clean.substring(start, end));
        if (anchor.find()) end = start + anchor.start();
        String suffix = end < clean.length() && clean.charAt(end) == '^' ? " " : "";
        inserts.add(new Insertion(end, i, " #" + t.kind + "/" + t.ids + suffix));
      }
    }
    inserts.sort(
        Comparator.comparingInt(Insertion::at).thenComparingInt(Insertion::order).reversed());
    StringBuilder out = new StringBuilder(clean);
    for (Insertion i : inserts) out.insert(i.at, i.value);
    return out.toString();
  }
}
