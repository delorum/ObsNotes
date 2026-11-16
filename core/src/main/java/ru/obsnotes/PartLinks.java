package ru.obsnotes;

import java.util.*;

/** Directed acyclic graph with ordered, independently editable parent references. */
public final class PartLinks {
  public boolean configured;

  /** Legacy first-local-parent mirror, retained for old recovery records. */
  public final Map<String, String> parents = new LinkedHashMap<>();

  public final Map<String, List<EventLink>> edges = new LinkedHashMap<>();
  public final Set<String> roots = new LinkedHashSet<>();

  public List<EventLink> refs(String child) {
    if (!edges.containsKey(child) && parents.containsKey(child))
      edges.put(child, List.of(EventLink.local(parents.get(child))));
    return edges.getOrDefault(child, List.of());
  }

  public Set<String> children() {
    Set<String> keys = new LinkedHashSet<>(parents.keySet());
    keys.addAll(edges.keySet());
    return keys;
  }

  public PartLinks copy() {
    PartLinks result = new PartLinks();
    result.configured = configured;
    result.roots.addAll(roots);
    for (String child : children()) result.set(child, refs(child));
    return result;
  }

  public void set(String child, List<EventLink> refs) {
    edges.put(child, List.copyOf(refs));
    parents.remove(child);
    refs.stream()
        .filter(EventLink::local)
        .findFirst()
        .ifPresent(ref -> parents.put(child, ref.part()));
  }

  public void assign(PartLinks graph) {
    if (graph == this) return;
    parents.clear();
    edges.clear();
    roots.clear();
    roots.addAll(graph.roots);
    configured = graph.configured;
    for (String child : graph.children()) set(child, graph.refs(child));
  }

  private void cyclic(String node, Set<String> visiting, Set<String> done) {
    if (done.contains(node)) return;
    if (!visiting.add(node)) throw new IllegalArgumentException("Эта связь создаст круг");
    for (EventLink ref : refs(node)) if (ref.local()) cyclic(ref.part(), visiting, done);
    visiting.remove(node);
    done.add(node);
  }

  public void add(String child, EventLink ref) {
    if (refs(child).stream().anyMatch(old -> old.identity().equals(ref.identity()))) return;
    List<EventLink> next = new ArrayList<>(refs(child));
    next.add(ref);
    replaceAllChecked(child, next);
  }

  public void replace(String child, String key, EventLink ref) {
    List<EventLink> next = new ArrayList<>(refs(child));
    int at = -1;
    for (int i = 0; i < next.size(); i++) if (next.get(i).key().equals(key)) at = i;
    if (at < 0) throw new IllegalArgumentException("Связь уже изменилась");
    for (int i = 0; i < next.size(); i++)
      if (i != at && next.get(i).identity().equals(ref.identity()))
        throw new IllegalArgumentException("Такая связь уже есть");
    next.set(at, ref.key(key));
    replaceAllChecked(child, next);
  }

  private void replaceAllChecked(String child, List<EventLink> refs) {
    PartLinks candidate = copy();
    candidate.configured = true;
    candidate.set(child, refs);
    candidate.checkCycles();
    assign(candidate);
  }

  public void remove(String child, String key) {
    configured = true;
    set(child, refs(child).stream().filter(ref -> !ref.key().equals(key)).toList());
  }

  public void parent(String child, String parent) {
    replaceAllChecked(child, List.of(EventLink.local(parent)));
    roots.remove(child);
  }

  public void root(String id) {
    configured = true;
    set(id, List.of());
    roots.add(id);
  }

  public void markRoot(String id) {
    configured = true;
    roots.add(id);
  }

  public void independent(String id) {
    configured = true;
    set(id, List.of());
    roots.remove(id);
  }

  public boolean involved(String id) {
    return roots.contains(id)
        || !refs(id).isEmpty()
        || children().stream()
            .anyMatch(
                child ->
                    refs(child).stream().anyMatch(ref -> ref.local() && ref.part().equals(id)));
  }

  public Set<String> rootIds() {
    Set<String> ids = new LinkedHashSet<>(roots);
    for (String child : children())
      for (EventLink ref : refs(child)) if (ref.local()) ids.add(ref.part());
    return ids;
  }

  public void checkCycles() {
    Set<String> done = new HashSet<>();
    for (String child : children()) cyclic(child, new HashSet<>(), done);
  }

  public void validate(Set<String> ids) {
    Set<String> needed = rootIds();
    for (String child : children()) if (!refs(child).isEmpty()) needed.add(child);
    if (!ids.containsAll(needed))
      throw new IllegalArgumentException("Сначала измените связи удаляемой части");
    checkCycles();
  }
}
