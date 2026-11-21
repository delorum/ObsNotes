package ru.obsnotes;

import java.util.*;

/**
 * Reorder physical block slots, retaining every gap, timestamp, heading, anchor and source byte.
 */
public final class BlockOrder {
  private BlockOrder() {}

  public record Result(Markdown document, Map<Integer, Integer> positions) {}

  public static Result move(Markdown source, Markdown.Block from, Markdown.Block to) {
    if (!source.blocks.contains(from) || !source.blocks.contains(to))
      throw new IllegalArgumentException("Запись изменилась");
    if (!from.section().equals(to.section()))
      throw new IllegalArgumentException("Перемещение доступно только внутри раздела");
    var slots = source.blocks.stream().filter(b -> b.section().equals(from.section())).toList();
    var order = new ArrayList<>(slots);
    int target = order.indexOf(to);
    order.remove(from);
    order.add(target, from);
    StringBuilder output = new StringBuilder();
    Map<Integer, Integer> positions = new HashMap<>();
    int offset = 0;
    for (int i = 0; i < slots.size(); i++) {
      var slot = slots.get(i);
      var block = order.get(i);
      output.append(source.text, offset, slot.start());
      positions.put(block.start(), output.length());
      output.append(block.text());
      offset = slot.end();
    }
    output.append(source.text.substring(offset));
    var after = new Markdown(output.toString());
    // Map unmodified ranges following the changed section, too.
    for (int i = 0; i < source.blocks.size(); i++) {
      var old = source.blocks.get(i);
      if (!positions.containsKey(old.start()))
        positions.put(old.start(), after.blocks.get(i).start());
    }
    return new Result(after, Map.copyOf(positions));
  }
}
