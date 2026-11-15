package ru.obsnotes;

import java.util.*;

/** A checkbox stages multiple items; choosing a label applies the staged items immediately. */
public final class PickerSelection<T> {
  private final LinkedHashSet<T> selected = new LinkedHashSet<>();

  public PickerSelection() {}

  public PickerSelection(Collection<T> initial) {
    selected.addAll(initial);
  }

  public void set(List<T> items) {
    selected.clear();
    selected.addAll(items);
  }

  public boolean contains(T item) {
    return selected.contains(item);
  }

  public void toggle(T item) {
    if (!selected.remove(item)) selected.add(item);
  }

  public List<T> selected() {
    return List.copyOf(selected);
  }

  public List<T> choose(T item) {
    selected.add(item);
    return selected();
  }
}
