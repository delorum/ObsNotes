package ru.obsnotes;

import java.util.*;

/** A forest of paragraph relationships, keyed by identity rather than visible order. */
public final class PartLinks {
  public boolean configured;
  public final Map<String, String> parents = new LinkedHashMap<>();
  public final Set<String> roots = new LinkedHashSet<>();

  public void parent(String child, String parent) {
    if (child.equals(parent))
      throw new IllegalArgumentException("Часть не может ссылаться на себя");
    String at = parent;
    Set<String> seen = new HashSet<>();
    while (at != null && seen.add(at)) {
      if (at.equals(child)) throw new IllegalArgumentException("Эта связь создаст круг");
      at = parents.get(at);
    }
    configured = true;
    parents.put(child, parent);
    roots.remove(child);
  }

  public void root(String id) {
    configured = true;
    parents.remove(id);
    roots.add(id);
  }

  public void independent(String id) {
    configured = true;
    parents.remove(id);
    roots.remove(id);
  }

  public boolean involved(String id) {
    return roots.contains(id) || parents.containsKey(id) || parents.containsValue(id);
  }

  public Set<String> rootIds() {
    Set<String> ids = new LinkedHashSet<>(roots);
    ids.addAll(parents.values());
    return ids;
  }

  public void validate(Set<String> ids) {
    if (!ids.containsAll(roots)
        || !ids.containsAll(parents.keySet())
        || !ids.containsAll(parents.values()))
      throw new IllegalArgumentException("Сначала измените связи удаляемой части");
    for (String id : parents.keySet()) {
      Set<String> seen = new HashSet<>();
      String at = id;
      while (at != null) {
        if (!seen.add(at)) throw new IllegalArgumentException("Связи частей образуют круг");
        at = parents.get(at);
      }
    }
  }
}
