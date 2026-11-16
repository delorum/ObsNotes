package ru.obsnotes;

import java.time.*;
import java.util.*;

/** Paragraph identities and assignments, independent of the cursor and Android views. */
public final class EditorParts {
  public record Part(int start, int end, EditorState form) {}

  public final List<Part> parts = new ArrayList<>();
  public String value, baseline, error = "";
  public Markdown template = new Markdown("");
  public boolean linked; // Compatibility with earlier recovery snapshots and star-link toggle.
  public final PartLinks links = new PartLinks();
  public int selected;

  public EditorParts(EditorState owner) {
    value = baseline = owner.editableText();
    parts.add(new Part(0, value.length(), owner.copy()));
    edit(value);
  }

  public EditorState selected() {
    return parts.get(Math.max(0, Math.min(selected, parts.size() - 1))).form();
  }

  private static List<int[]> ranges(String value) {
    List<int[]> result = new ArrayList<>();
    int start = -1, end = 0;
    for (var line : MarkdownLines.scan(value)) {
      if (!line.literal() && line.text().isBlank()) {
        if (start >= 0) result.add(new int[] {start, end});
        start = -1;
      } else {
        if (start < 0) start = line.start();
        end = line.start() + line.text().length();
      }
    }
    if (start >= 0) result.add(new int[] {start, end});
    if (result.isEmpty()) result.add(new int[] {0, value.length()});
    return result;
  }

  /** Stable character overlap retains assignments when paragraphs are inserted or removed. */
  public void edit(String next) {
    next = next.replace("\r\n", "\n");
    value = next;
    int prefix = 0;
    while (prefix < baseline.length()
        && prefix < next.length()
        && baseline.charAt(prefix) == next.charAt(prefix)) prefix++;
    int suffix = 0;
    while (suffix < baseline.length() - prefix
        && suffix < next.length() - prefix
        && baseline.charAt(baseline.length() - 1 - suffix)
            == next.charAt(next.length() - 1 - suffix)) suffix++;
    int oldEnd = baseline.length() - suffix, newEnd = next.length() - suffix;
    final int p = prefix, o = oldEnd, n = newEnd;
    java.util.function.IntUnaryOperator map = at -> at <= p ? at : at >= o ? at + n - o : p;
    java.util.function.IntUnaryOperator mapStart = at -> at < p ? at : at >= o ? at + n - o : p;
    List<Part> result = new ArrayList<>();
    Set<EditorState> used = Collections.newSetFromMap(new IdentityHashMap<>());
    try {
      for (int[] range : ranges(next)) {
        List<Part> contributors = new ArrayList<>();
        for (Part old : parts) {
          int a = mapStart.applyAsInt(old.start()), b = map.applyAsInt(old.end());
          if (Math.max(a, range[0]) < Math.min(b, range[1])) contributors.add(old);
        }
        boolean inferred = contributors.isEmpty();
        if (contributors.isEmpty()) {
          Part closest = parts.get(0);
          for (Part old : parts) if (mapStart.applyAsInt(old.start()) <= range[0]) closest = old;
          contributors.add(closest);
        }
        Part origin = contributors.get(0);
        EditorState form = origin.form().copy();
        boolean mappedContent = map.applyAsInt(origin.end()) > mapStart.applyAsInt(origin.start());
        boolean first = (!inferred || !mappedContent) && used.add(origin.form());
        if (!first) {
          form.clearEventMetadata();
          form.partId = UUID.randomUUID().toString();
        }
        // Block-level destinations follow a split. Per-line/person tags follow their characters.
        List<TaggedText.Tag> tags = new ArrayList<>();
        List<Routing.Choice> routes = new ArrayList<>(form.routes);
        for (Part old : contributors) {
          EditorState source = old.form();
          if (old != origin) {
            if (!Events.id(source.text).isEmpty() || !source.rootLinkLabel().isEmpty())
              throw new IllegalArgumentException(
                  "Для объединения частей сначала уберите их связи или верните пустую строку.");
            for (Routing.Choice route : source.routes) {
              if (routes.stream().anyMatch(c -> c.target().equals(route.target()))) continue;
              if (route.target().kind() != Markdown.Kind.SECTION || route.crossId().isEmpty())
                throw new IllegalArgumentException(
                    "У частей разные назначения. Выберите одинаковые разделы или верните пустую"
                        + " строку.");
              routes.add(route);
            }
          }
          List<TaggedText.Tag> global =
              source.tags().stream()
                  .map(t -> new TaggedText.Tag(t.kind(), t.ids(), old.start() + t.position()))
                  .toList();
          var mapped = TaggedText.parse(baseline).withTags(global).edit(next);
          for (var t : mapped.tags) {
            if (t.kind().equals("crossPeople")) {
              tags.add(new TaggedText.Tag(t.kind(), t.ids(), 0));
            } else if (t.position() >= range[0] && t.position() <= range[1]) {
              tags.add(new TaggedText.Tag(t.kind(), t.ids(), t.position() - range[0]));
            }
          }
        }
        String clean = next.substring(range[0], range[1]);
        form.replaceBody(
            TaggedText.parse(clean).withTags(tags.stream().distinct().toList()).render());
        if (contributors.size() > 1) form.applyRoutes(routes, form.routingCatalog(template));
        result.add(new Part(range[0], range[1], form));
      }
      for (Part old : parts) {
        String id = Events.id(old.form().text);
        if (!id.isEmpty()
            && result.stream().noneMatch(part -> Events.id(part.form().text).equals(id)))
          throw new IllegalArgumentException(
              "Часть с якорем нельзя удалить. Верните её текст или пустую строку.");
      }
      Set<String> ids = new HashSet<>();
      for (Part part : result) ids.add(part.form().partId);
      if (links.configured) links.validate(ids);
      parts.clear();
      parts.addAll(result);
      baseline = next;
      selected = Math.min(selected, parts.size() - 1);
      error = "";
    } catch (IllegalArgumentException failure) {
      // Keep every assignment and the last valid layout: a failed merge is fully recoverable.
      error = failure.getMessage();
    }
  }

  /** Metadata actions may insert a person's visible name; keep the shared field in sync. */
  public void sync() {
    String pending = value;
    boolean retry = !error.isEmpty();
    StringBuilder joined = new StringBuilder();
    List<Part> adjusted = new ArrayList<>();
    int end = 0;
    for (Part part : parts) {
      joined.append(baseline, end, part.start());
      int start = joined.length();
      joined.append(part.form().editableText());
      adjusted.add(new Part(start, joined.length(), part.form()));
      end = part.end();
    }
    joined.append(baseline.substring(end));
    parts.clear();
    parts.addAll(adjusted);
    baseline = value = joined.toString();
    if (retry) edit(pending);
  }

  public List<TaggedText.Tag> tags() {
    if (!error.isEmpty()) return List.of();
    List<TaggedText.Tag> tags = new ArrayList<>();
    for (Part part : parts)
      for (var tag : part.form().tags())
        tags.add(new TaggedText.Tag(tag.kind(), tag.ids(), part.start() + tag.position()));
    return tags;
  }

  public int partAt(int cursor) {
    int index = 0;
    for (int i = 0; i < parts.size(); i++) if (parts.get(i).start() <= cursor) index = i;
    return index;
  }

  public int selectAt(int cursor) {
    return selected = partAt(cursor);
  }

  public PartLinks effectiveLinks() {
    if (links.configured) return links;
    PartLinks result = new PartLinks();
    if (linked && parts.size() > 1) {
      String first = parts.get(0).form().partId;
      result.root(first);
      for (int i = 1; i < parts.size(); i++) result.parent(parts.get(i).form().partId, first);
    }
    return result;
  }

  private void configureLinks() {
    if (links.configured) return;
    PartLinks current = effectiveLinks();
    links.parents.putAll(current.parents);
    links.roots.addAll(current.roots);
    links.configured = true;
  }

  public void linkTo(int child, int parent) {
    EditorState childForm = parts.get(child).form(), parentForm = parts.get(parent).form();
    if (childForm.kind != Markdown.Kind.SECTION || parentForm.kind != Markdown.Kind.SECTION)
      throw new IllegalArgumentException(
          "Связи доступны для разделов, без задач и Dataview-ключей");
    PartLinks candidate = effectiveLinks();
    candidate.parent(childForm.partId, parentForm.partId);
    if (candidate != links) {
      links.parents.clear();
      links.parents.putAll(candidate.parents);
      links.roots.clear();
      links.roots.addAll(candidate.roots);
      links.configured = true;
    }
    childForm.removeRootLink();
  }

  public void makeRoot(int index) {
    EditorState form = parts.get(index).form();
    if (form.kind != Markdown.Kind.SECTION)
      throw new IllegalArgumentException("Корень должен находиться в разделе");
    configureLinks();
    links.root(form.partId);
    form.removeRootLink();
  }

  public void attachExternal(int index, Events.Root root) {
    EditorState form = parts.get(index).form();
    form.attach(root);
    configureLinks();
    links.independent(form.partId);
  }

  public void independent(int index) {
    configureLinks();
    EditorState form = parts.get(index).form();
    links.independent(form.partId);
    form.removeRootLink();
  }

  public boolean linksEnabled() {
    return linked || !effectiveLinks().rootIds().isEmpty();
  }

  /** Freeze the old checkbox's star as explicit edges; new parts stay independent. */
  public void migrateLegacyLinks() {
    if (!linked) return;
    configureLinks();
    linked = false;
  }

  public void linkAll(boolean enabled) {
    linked = enabled;
    if (enabled) for (int i = 1; i < parts.size(); i++) parts.get(i).form().removeRootLink();
    links.configured = false;
    links.parents.clear();
    links.roots.clear();
  }

  public String relationLabel(int index) {
    String id = parts.get(index).form().partId;
    PartLinks graph = effectiveLinks();
    String parent = graph.parents.get(id);
    boolean root =
        graph.rootIds().contains(id) || !Events.rootId(parts.get(index).form().text).isEmpty();
    if (parent != null) {
      for (int i = 0; i < parts.size(); i++)
        if (parts.get(i).form().partId.equals(parent))
          return "К части " + (i + 1) + (root ? " · Корень" : "");
    }
    String external = parts.get(index).form().rootLinkLabel();
    if (!external.isEmpty()) return "К корню: " + external + (root ? " · Корень" : "");
    return root ? "Корень" : "Связь";
  }

  public String validateRoutes(EditorState form, List<Routing.Choice> choices) {
    if (effectiveLinks().involved(form.partId)
        && !choices.isEmpty()
        && choices.get(0).target().kind() != Markdown.Kind.SECTION)
      return "Сначала уберите связь этой части: задачи и Dataview-ключи не поддерживают связи";
    return form.validateRoutes(choices);
  }

  public void validate() {
    if (!error.isEmpty()) throw new IllegalArgumentException(error);
    PartLinks graph = effectiveLinks();
    Set<String> ids = new HashSet<>();
    for (Part part : parts) ids.add(part.form().partId);
    graph.validate(ids);
    for (Part part : parts)
      if (graph.involved(part.form().partId) && part.form().kind != Markdown.Kind.SECTION)
        throw new IllegalArgumentException(
            "Связи доступны для разделов, без задач и Dataview-ключей");
  }

  private List<EditorState> savedForms() {
    List<EditorState> forms = parts.stream().map(p -> p.form().copy()).toList();
    PartLinks graph = effectiveLinks();
    Map<String, Events.Root> roots = new HashMap<>();
    for (EditorState form : forms)
      if (graph.rootIds().contains(form.partId)) {
        form.text = Events.root(form.text);
        var doc = new Markdown(form.heading + "\n" + form.text + "\n");
        roots.put(
            form.partId,
            new Events.Root(form.destinationPath, doc.blocks.get(0), Events.rootId(form.text)));
      }
    for (EditorState form : forms) {
      String parent = graph.parents.get(form.partId);
      if (parent != null) form.text = Events.link(form.text, roots.get(parent));
    }
    return forms;
  }

  public List<VaultRepository.Change> plan(
      EditorState owner,
      Markdown source,
      Markdown destination,
      Markdown template,
      LocalDate date,
      LocalDateTime now,
      CleanupRules rules) {
    validate();
    List<EditorState> forms = savedForms();
    boolean internalEvents = !effectiveLinks().rootIds().isEmpty();
    // A single part retains the existing in-place edit and protected-anchor semantics.
    if (parts.size() == 1 && !(internalEvents && !owner.sourcePath.equals(owner.destinationPath))) {
      EditorState form = forms.get(0);
      form.edit(value);
      return VaultRepository.plan(form, source, destination, template, date, now, rules);
    }
    if (!owner.fresh) owner.originalBlock(source);
    LinkedHashMap<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    before.put(owner.destinationPath, destination.text);
    before.put(owner.sourcePath, source.text);
    after.putAll(before);

    if (!owner.fresh
        && owner.sourcePath.equals(owner.destinationPath)
        && forms.stream()
            .allMatch(
                f ->
                    f.kind == Markdown.Kind.SECTION
                        && f.heading.equals(owner.originalBlock(source).section().heading()))) {
      String joined = String.join("\n\n", forms.stream().map(EditorState::savedText).toList());
      List<VaultRepository.Change> changes =
          List.of(
              new VaultRepository.Change(
                  owner.sourcePath,
                  source.text,
                  source.replace(owner.originalBlock(source), joined).text));
      for (EditorState form : forms) changes = VaultRepository.addRoot(changes, form);
      return changes;
    }
    boolean relocatedGroup = internalEvents && !owner.sourcePath.equals(owner.destinationPath);
    if (relocatedGroup && !owner.fresh) {
      if (!Events.id(owner.original).isEmpty() || owner.original.contains("[[#^"))
        throw new IllegalArgumentException(
            "Корневой блок нельзя переносить между днями: ссылки должны сохранить адрес.");
      after.put(owner.sourcePath, source.replace(owner.originalBlock(source), "").text);
    }
    boolean firstPart = true;
    for (EditorState form : forms) {
      if (!owner.sourcePath.equals(owner.destinationPath)
          && (!Events.id(owner.original).isEmpty() || owner.original.contains("[[#^")))
        throw new IllegalArgumentException(
            "Корневой блок нельзя переносить между днями: ссылки должны сохранить адрес.");
      form.fresh = firstPart && !relocatedGroup ? owner.fresh : true;
      form.original = owner.original;
      form.start = owner.start;
      form.end = owner.end;
      form.sourcePath = firstPart && !relocatedGroup ? owner.sourcePath : owner.destinationPath;
      form.parts = null;
      form.rootPath = ""; // Merge pending root edits once, after every paragraph insertion.
      Markdown current = new Markdown(after.get(form.destinationPath));
      if (form.kind != Markdown.Kind.SECTION) {
        // Resolve by exact original target line identity, accounting for earlier insertions.
        var candidates =
            current.targets(template).stream()
                .filter(
                    t ->
                        t.kind() == form.kind
                            && t.heading().equals(form.heading)
                            && t.source().equals(form.targetSource))
                .toList();
        if (candidates.size() != 1)
          throw new IllegalArgumentException(
              "Назначение изменилось или выбрано для нескольких частей.");
        form.targetLine = candidates.get(0).line();
      }
      for (var change :
          VaultRepository.plan(
              form,
              new Markdown(after.get(form.sourcePath)),
              current,
              template,
              date,
              now,
              CleanupRules.EMPTY)) {
        before.putIfAbsent(change.path(), change.before());
        after.put(change.path(), change.after());
      }
      firstPart = false;
    }
    List<VaultRepository.Change> result = new ArrayList<>();
    for (String path : before.keySet()) {
      String updated = after.get(path);
      result.add(new VaultRepository.Change(path, before.get(path), updated));
    }
    for (var part : parts) result = new ArrayList<>(VaultRepository.addRoot(result, part.form()));
    return result.stream()
        .map(
            c ->
                c.before().equals(c.after())
                    ? c
                    : new VaultRepository.Change(
                        c.path(), c.before(), DiaryCleanup.compress(c.after(), rules)))
        .toList();
  }
}
