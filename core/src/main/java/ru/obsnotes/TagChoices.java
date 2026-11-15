package ru.obsnotes;

import java.util.*;

/** Existing concatenated assignments preselect their IDs, including legacy unknown IDs. */
public record TagChoices(
    List<CrossReferences.Destination> items, List<CrossReferences.Destination> selected) {
  public TagChoices {
    items = List.copyOf(items);
    selected = List.copyOf(selected);
  }

  public static TagChoices of(Markdown template, boolean person, String ids) {
    List<CrossReferences.Destination> items =
        new ArrayList<>(CrossReferences.selectable(template, person));
    List<CrossReferences.Destination> selected = new ArrayList<>();
    StringBuilder unknown = new StringBuilder();
    int at = 0;
    while (at < ids.length()) {
      CrossReferences.Destination found = null;
      for (var item : items)
        if (ids.regionMatches(true, at, item.id(), 0, item.id().length())
            && (found == null || item.id().length() > found.id().length())) found = item;
      if (found == null) {
        unknown.append(ids.charAt(at++));
        continue;
      }
      flush(unknown, items, selected);
      if (!selected.contains(found)) selected.add(found);
      at += found.id().length();
    }
    flush(unknown, items, selected);
    return new TagChoices(items, selected);
  }

  private static void flush(
      StringBuilder unknown,
      List<CrossReferences.Destination> items,
      List<CrossReferences.Destination> selected) {
    if (unknown.length() == 0) return;
    var item =
        new CrossReferences.Destination(unknown.toString(), unknown.toString(), "", List.of());
    if (!items.contains(item)) items.add(item);
    if (!selected.contains(item)) selected.add(item);
    unknown.setLength(0);
  }
}
