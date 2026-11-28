package ru.obsnotes;

import java.time.*;
import java.util.*;

/** Paragraph identities and assignments, independent of the cursor and Android views. */
public final class EditorParts {
  public record Part(int start, int end, EditorState form) {}

  public final EditorState owner;
  public final List<Part> parts = new ArrayList<>();
  public String value, baseline, error = "";
  public Markdown template = new Markdown("");
  public boolean linked; // Compatibility with earlier recovery snapshots and star-link toggle.
  public final PartLinks links = new PartLinks();
  public int selected;
  public boolean imported;

  public record Incoming(String child, int position, EventLink link) {}

  public record DeletedRoot(String part, List<Incoming> incoming) {}

  public final List<DeletedRoot> deletedRoots = new ArrayList<>();

  public EditorParts(EditorState owner) {
    this.owner = owner;
    var fields = owner.fieldForms();
    if (fields.isEmpty()) {
      value = baseline = owner.editableText();
      parts.add(new Part(0, value.length(), owner.copy()));
      edit(value);
    } else {
      StringBuilder input = new StringBuilder();
      for (var field : fields) {
        if (!parts.isEmpty()) input.append("\n\n");
        int start = input.length();
        input.append(field.editableText());
        parts.add(new Part(start, input.length(), field));
      }
      value = baseline = input.toString();
    }
    importLinks();
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
    next = next.replace("\r\n", "\n").replace('\r', '\n');
    value = next;
    // Replacing the whole field must retain the original slots while the IME types them again.
    if (owner.batch != null && next.isEmpty() && error.isEmpty()) {
      List<Part> empty = new ArrayList<>();
      for (var part : parts) {
        var form = part.form().copy();
        form.replaceBody("");
        empty.add(new Part(0, 0, form));
      }
      parts.clear();
      parts.addAll(empty);
      baseline = "";
      selected = 0;
      return;
    }
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
    java.util.function.ToIntFunction<Part> mappedStart =
        part ->
            part.start() == part.end()
                    && (pinned(part.form())
                        || owner.batch != null && !part.form().origins.isEmpty())
                ? map.applyAsInt(part.start())
                : mapStart.applyAsInt(part.start());
    Map<String, Integer> originalOrder = new HashMap<>();
    for (int i = 0; i < parts.size(); i++) originalOrder.put(parts.get(i).form().partId, i);
    var fieldSlots = parts.stream().filter(part -> !part.form().fieldPrefix.isEmpty()).toList();
    boolean replaceValues =
        fieldSlots.size() > 1
            && (prefix == 0 && suffix == 0
                || prefix < fieldSlots.get(0).end()
                    && oldEnd > fieldSlots.get(fieldSlots.size() - 1).start());
    List<Part> result = new ArrayList<>();
    Map<String, String> merged = new LinkedHashMap<>();
    Set<EditorState> used = Collections.newSetFromMap(new IdentityHashMap<>());
    try {
      var spans = ranges(next);
      var taggedBaseline = TaggedText.parse(baseline);
      if (next.isBlank() && parts.stream().anyMatch(part -> pinned(part.form())))
        spans = List.of(new int[] {0, 0});
      boolean replaceBatch =
          owner.batch != null
              && spans.size() == parts.size()
              && prefix <= parts.get(0).start()
              && oldEnd > parts.get(0).end();
      int slot = 0;
      for (int[] range : spans) {
        List<Part> contributors = new ArrayList<>();
        for (Part old : parts) {
          int a = mappedStart.applyAsInt(old), b = map.applyAsInt(old.end());
          if (Math.max(a, range[0]) < Math.min(b, range[1])) contributors.add(old);
        }
        if (replaceValues && slot < fieldSlots.size()) {
          contributors.clear();
          contributors.add(fieldSlots.get(slot));
        }
        if (replaceBatch && !replaceValues) {
          contributors.clear();
          contributors.add(parts.get(slot));
        }
        slot++;
        boolean inferred = contributors.isEmpty();
        if (contributors.isEmpty()) {
          Part closest = parts.get(0);
          for (Part old : parts)
            if (mappedStart.applyAsInt(old) <= range[0]
                && mappedStart.applyAsInt(old) > mappedStart.applyAsInt(closest)) closest = old;
          contributors.add(closest);
        }
        if (contributors.size() > 1 && contributors.stream().anyMatch(old -> old.form().locked()))
          throw new IllegalArgumentException("Сначала расшифруйте объединяемые части замком");
        Part origin = contributors.get(0);
        EditorState form = origin.form().copy();
        boolean mappedContent = map.applyAsInt(origin.end()) > mapStart.applyAsInt(origin.start());
        int originStart = Math.max(0, Math.min(next.length(), mappedStart.applyAsInt(origin)));
        boolean separatedEmpty =
            inferred
                && pinned(origin.form())
                && range[0] > originStart
                && next.substring(originStart, range[0]).contains("\n\n");
        boolean first = !separatedEmpty && (!inferred || !mappedContent) && used.add(origin.form());
        if (!first) {
          boolean opened = form.secretOpen;
          form.clearEventMetadata();
          if (opened) {
            form.text = SecretText.opened(form.text);
            form.secretOpen = true;
          }
          form.partId = UUID.randomUUID().toString();
          if (form.valueBound() || form.kind != Markdown.Kind.SECTION) {
            form.fieldPrefix = form.taskShell = "";
            form.editFields = false;
            form.taskAppend = false;
            form.fresh = true;
            form.appendTaskText = true;
            form.kind = Markdown.Kind.SECTION;
            form.heading = form.fallbackHeading = "## Заметки";
            form.targetSource = "";
            form.targetLine = -1;
            form.routes = List.of();
            form.routesInitialized = false;
            form.routeChosen = false;
            form.initializeRoutes(template);
          }
        }
        // Block-level destinations follow a split. Per-line/person tags follow their characters.
        List<TaggedText.Tag> tags = new ArrayList<>();
        List<Routing.Choice> routes = new ArrayList<>(form.routes);
        for (Part old : contributors) {
          EditorState source = old.form();
          if (old != origin) {
            merged.put(source.partId, form.partId);
            if (source.valueBound())
              throw new IllegalArgumentException("Поля имеют разные ключи. Верните пустую строку.");
            if (!Events.id(source.text).isEmpty()) {
              if (Events.rootId(source.text).isEmpty())
                throw new IllegalArgumentException(
                    "Часть с отдельным якорем нельзя объединить без потери адреса");
              form.adoptRoot(source);
            }
            for (Routing.Choice route :
                source.routeChosen ? source.routes : List.<Routing.Choice>of()) {
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
          var mapped = taggedBaseline.withTags(global).edit(next);
          for (var t : mapped.tags) {
            if (t.kind().equals("crossPeople")) {
              tags.add(new TaggedText.Tag(t.kind(), t.ids(), 0));
            } else if (t.position() >= range[0] && t.position() <= range[1]) {
              tags.add(new TaggedText.Tag(t.kind(), t.ids(), t.position() - range[0]));
            }
          }
        }
        form.origins =
            contributors.stream().flatMap(old -> old.form().origins.stream()).distinct().toList();
        String clean = next.substring(range[0], range[1]);
        form.replaceBody(
            TaggedText.parse(clean).withTags(tags.stream().distinct().toList()).render());
        if (contributors.size() > 1) form.applyRoutes(routes, form.routingCatalog(template));
        result.add(new Part(range[0], range[1], form));
      }
      for (Part old : parts) {
        if ((pinned(old.form())
                || owner.batch != null && old.start() == old.end() && !old.form().origins.isEmpty())
            && result.stream().noneMatch(part -> part.form().partId.equals(old.form().partId))) {
          EditorState empty = old.form().copy();
          empty.replaceBody("");
          int at = Math.max(0, Math.min(next.length(), mappedStart.applyAsInt(old)));
          for (var existing : result)
            if (at >= existing.start() && at < existing.end()) {
              boolean follows =
                  originalOrder.get(old.form().partId)
                      > originalOrder.getOrDefault(existing.form().partId, Integer.MAX_VALUE);
              at = follows ? existing.end() : existing.start();
            }
          result.add(new Part(at, at, empty));
        }
      }
      result.sort(
          Comparator.comparingInt(Part::start)
              .thenComparingInt(
                  part -> originalOrder.getOrDefault(part.form().partId, Integer.MAX_VALUE)));
      for (Part old : parts) {
        String id = Events.id(old.form().text);
        if (!id.isEmpty()
            && result.stream().noneMatch(part -> Events.id(part.form().text).equals(id)))
          throw new IllegalArgumentException(
              "Часть с якорем нельзя удалить. Верните её текст или пустую строку.");
      }
      Set<String> ids = new HashSet<>();
      for (Part part : result) ids.add(part.form().partId);
      PartLinks changedGraph = effectiveLinks().copy();
      if (!merged.isEmpty()) {
        PartLinks remapped = new PartLinks();
        remapped.configured = changedGraph.configured;
        for (String root : changedGraph.rootIds())
          remapped.roots.add(merged.getOrDefault(root, root));
        for (String child : changedGraph.children()) {
          String actual = merged.getOrDefault(child, child);
          List<EventLink> refs = new ArrayList<>(remapped.refs(actual));
          for (var ref : changedGraph.refs(child)) {
            var mapped =
                ref.local()
                    ? new EventLink(
                        ref.key(),
                        merged.getOrDefault(ref.part(), ref.part()),
                        ref.path(),
                        ref.id(),
                        ref.label(),
                        ref.raw(),
                        ref.edit())
                    : ref;
            if (mapped.local() && mapped.part().equals(actual)) continue;
            if (refs.stream().noneMatch(old -> old.identity().equals(mapped.identity())))
              refs.add(mapped);
          }
          remapped.set(actual, refs);
        }
        changedGraph = remapped;
      }
      if (changedGraph.configured) changedGraph.validate(ids);
      if (!merged.isEmpty()) links.assign(changedGraph);
      parts.clear();
      parts.addAll(result);
      if (!merged.isEmpty()) syncRawLinks();
      baseline = next;
      selected = Math.min(selected, parts.size() - 1);
      error = "";
    } catch (IllegalArgumentException failure) {
      // Keep every assignment and the last valid layout: a failed merge is fully recoverable.
      error = failure.getMessage();
    }
  }

  private static boolean pinned(EditorState form) {
    return form.valueBound() || form.taskAppend;
  }

  /** Metadata actions may insert a person's visible name; keep the shared field in sync. */
  public void sync() {
    String pending = value;
    boolean retry = !error.isEmpty();
    StringBuilder joined = new StringBuilder();
    List<Part> adjusted = new ArrayList<>();
    int end = 0;
    EditorState previous = null;
    for (Part part : parts) {
      if (previous != null
          && part.start() == end
          && !previous.fieldPrefix.isEmpty()
          && !part.form().fieldPrefix.isEmpty()) joined.append("\n\n");
      joined.append(baseline, end, part.start());
      int start = joined.length();
      joined.append(part.form().editableText());
      adjusted.add(new Part(start, joined.length(), part.form()));
      end = part.end();
      previous = part.form();
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
    // Cleared batch originals can share one empty position; retain the explicitly chosen card.
    if (selected >= 0 && selected < parts.size()) {
      var current = parts.get(selected);
      if (current.start() == current.end() && cursor == current.start()) return selected;
    }
    int index = 0;
    for (int i = 0; i < parts.size(); i++) if (parts.get(i).start() <= cursor) index = i;
    return index;
  }

  public int selectAt(int cursor) {
    return selected = partAt(cursor);
  }

  /** Select a metadata card using the latest visible ranges, excluding hidden Markdown metadata. */
  public int selectEnd(int index) {
    var part = parts.get(index);
    selected = index;
    owner.cursor = Math.max(0, Math.min(value.length(), part.end()));
    part.form().cursor = Math.max(0, owner.cursor - part.start());
    return owner.cursor;
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
    links.assign(current);
    links.configured = true;
  }

  public void importLinks() {
    if (imported) return;
    for (Part part : parts) {
      var raw = part.form().parentLinks();
      if (raw.isEmpty()) continue;
      configureLinks();
      List<EventLink> refs = new ArrayList<>();
      for (var link : raw) {
        var edit =
            part.form().pendingRoots().stream()
                .filter(
                    e ->
                        e.path().equals(link.parent().path())
                            && Events.id(e.replacement()).equals(link.parent().id()))
                .findFirst()
                .orElse(null);
        refs.add(EventLink.external(link, edit));
      }
      part.form().rootPath =
          part.form().rootOriginal = part.form().rootReplacement = part.form().rootSnapshot = "";
      part.form().rootEdits.clear();
      refs.addAll(links.refs(part.form().partId));
      links.set(part.form().partId, refs);
    }
    imported = true;
  }

  private void syncRawLinks() {
    for (Part part : parts) {
      List<String> raw =
          effectiveLinks().refs(part.form().partId).stream()
              .filter(ref -> !ref.local())
              .map(EventLink::raw)
              .toList();
      if (!part.form().parentLinks().stream().map(Events.Leading::raw).toList().equals(raw))
        part.form().text = Events.withLinks(part.form().text, raw);
    }
  }

  public void toggleSecret(int index, char[] password) {
    if (!error.isEmpty()) throw new IllegalArgumentException(error);
    boolean wasLocked = parts.get(index).form().locked();
    parts.get(index).form().toggleSecret(password, template);
    owner.sensitive = true;
    sync();
    int caret = parts.get(index).end();
    if (wasLocked && ranges(parts.get(index).form().editableText()).size() > 1) edit(value);
    owner.cursor = Math.min(caret, value.length());
    selected = partAt(owner.cursor);
  }

  public List<EventLink> references(int index) {
    return effectiveLinks().refs(parts.get(index).form().partId);
  }

  public boolean isRoot(int index) {
    return !Events.rootId(parts.get(index).form().text).isEmpty()
        || effectiveLinks().rootIds().contains(parts.get(index).form().partId);
  }

  public String referenceLabel(EventLink ref) {
    if (!ref.local()) return "К корню: " + ref.label();
    for (int i = 0; i < parts.size(); i++)
      if (parts.get(i).form().partId.equals(ref.part())) return "К части " + (i + 1);
    return "Связь";
  }

  public void addInternal(int child, int parent, String replacing) {
    EditorState a = parts.get(child).form(), b = parts.get(parent).form();
    if (WorkDaily.selected(a) || WorkDaily.selected(b))
      throw new IllegalArgumentException("Рабочие записи не поддерживают связи частей");
    if (a.kind != Markdown.Kind.SECTION || b.kind != Markdown.Kind.SECTION)
      throw new IllegalArgumentException(
          "Связи доступны для разделов, без задач и Dataview-ключей");
    PartLinks candidate = effectiveLinks().copy();
    if (replacing == null) candidate.add(a.partId, EventLink.local(b.partId));
    else candidate.replace(a.partId, replacing, EventLink.local(b.partId));
    links.assign(candidate);
    syncRawLinks();
  }

  public void addExternal(int index, Events.Root root, String snapshot, String replacing) {
    if (owner.batch != null) {
      for (int i = 0; i < parts.size(); i++) {
        var candidate = parts.get(i).form();
        if (candidate.sourcePath.equals(root.path()) && candidate.start == root.block().start()) {
          addInternal(index, i, replacing);
          return;
        }
      }
    }
    EditorState form = parts.get(index).form();
    String replacement = "";
    EventLink.RootEdit edit = null;
    if (root.id().isEmpty()) {
      for (Part part : parts)
        for (var ref : references(parts.indexOf(part)))
          if (ref.edit() != null
              && ref.edit().path().equals(root.path())
              && ref.edit().original().equals(root.block().text())) edit = ref.edit();
      if (edit == null) {
        replacement = Events.root(root.block().text());
        edit = new EventLink.RootEdit(root.path(), root.block().text(), replacement, snapshot);
      }
      root = new Events.Root(root.path(), root.block(), Events.id(edit.replacement()));
    }
    if (root.path().equals(form.sourcePath) && root.id().equals(Events.id(form.text)))
      throw new IllegalArgumentException("Нельзя связать блок с самим собой");
    var raw = Events.prefix(root.path(), Events.format(root)).links().get(0);
    EventLink ref = EventLink.external(raw, edit);
    PartLinks candidate = effectiveLinks().copy();
    if (replacing == null) candidate.add(form.partId, ref);
    else candidate.replace(form.partId, replacing, ref);
    links.assign(candidate);
    syncRawLinks();
  }

  public void removeReference(int index, String key) {
    configureLinks();
    links.remove(parts.get(index).form().partId, key);
    syncRawLinks();
  }

  public void linkTo(int child, int parent) {
    EditorState form = parts.get(child).form(), target = parts.get(parent).form();
    if (form.kind != Markdown.Kind.SECTION || target.kind != Markdown.Kind.SECTION)
      throw new IllegalArgumentException(
          "Связи доступны для разделов, без задач и Dataview-ключей");
    PartLinks candidate = effectiveLinks().copy();
    candidate.parent(form.partId, target.partId);
    links.assign(candidate);
    form.removeRootLink();
  }

  public void makeRoot(int index) {
    EditorState form = parts.get(index).form();
    if (form.kind != Markdown.Kind.SECTION)
      throw new IllegalArgumentException("Корень должен находиться в разделе");
    PartLinks candidate = effectiveLinks().copy();
    candidate.markRoot(form.partId);
    var removed =
        deletedRoots.stream()
            .filter(old -> old.part().equals(form.partId))
            .findFirst()
            .orElse(null);
    if (removed != null)
      for (var incoming : removed.incoming()) {
        if (parts.stream().noneMatch(part -> part.form().partId.equals(incoming.child()))) continue;
        List<EventLink> refs = new ArrayList<>(candidate.refs(incoming.child()));
        if (refs.stream().noneMatch(ref -> ref.identity().equals(incoming.link().identity())))
          refs.add(Math.min(incoming.position(), refs.size()), incoming.link());
        candidate.set(incoming.child(), refs);
      }
    candidate.checkCycles();
    String id = Events.rootId(form.removedRootBefore);
    form.restoreRootMetadata();
    links.assign(candidate);
    deletedRoots.removeIf(old -> old.part().equals(form.partId));
    syncRawLinks();
    if (!id.isEmpty()) owner.removedRoots.remove(new Events.Parent(form.sourcePath, id));
  }

  public void deleteRoot(int index) {
    EditorState form = parts.get(index).form();
    String id = Events.rootId(form.text);
    PartLinks candidate = effectiveLinks().copy();
    List<Incoming> removed = new ArrayList<>();
    for (String child : candidate.children()) {
      var refs = candidate.refs(child);
      List<EventLink> keep = new ArrayList<>();
      for (int i = 0; i < refs.size(); i++) {
        var ref = refs.get(i);
        if (ref.local()
            ? ref.part().equals(form.partId)
            : !id.isEmpty() && ref.path().equals(form.sourcePath) && ref.id().equals(id))
          removed.add(new Incoming(child, i, ref));
        else keep.add(ref);
      }
      candidate.set(child, keep);
    }
    candidate.roots.remove(form.partId);
    candidate.configured = true;
    form.deleteRootMetadata();
    links.assign(candidate);
    owner.removedRoots.addAll(form.removedRoots);
    syncRawLinks();
    deletedRoots.removeIf(old -> old.part().equals(form.partId));
    deletedRoots.add(new DeletedRoot(form.partId, List.copyOf(removed)));
  }

  public void attachExternal(int index, Events.Root root) {
    EditorState form = parts.get(index).form();
    configureLinks();
    links.independent(form.partId);
    addExternal(index, root, form.sourceSnapshot, null);
  }

  public void independent(int index) {
    configureLinks();
    EditorState form = parts.get(index).form();
    links.independent(form.partId);
    form.removeRootLink();
    syncRawLinks();
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
    links.edges.clear();
    links.roots.clear();
    imported = false;
  }

  public String relationLabel(int index) {
    var refs = references(index);
    boolean root = isRoot(index);
    if (!refs.isEmpty()) {
      var ref = refs.get(0);
      return referenceLabel(ref) + (root ? " · Корень" : "");
    }
    return root ? "Корень" : "Связь";
  }

  public String validateRoutes(EditorState form, List<Routing.Choice> choices) {
    if (WorkDaily.selected(choices)
        && effectiveLinks().involved(form.partId)
        && !WorkDaily.selected(form))
      return "Сначала уберите связи этой части перед переносом в работу";
    if ((effectiveLinks().involved(form.partId)
            || owner.batch != null && !Events.id(form.text).isEmpty())
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

  public List<EventLink.RootEdit> pendingRootEdits() {
    List<EventLink.RootEdit> edits = new ArrayList<>();
    for (Part part : parts)
      for (var ref : effectiveLinks().refs(part.form().partId))
        if (ref.edit() != null) edits.add(ref.edit());
    return edits;
  }

  List<EditorState> savedForms() {
    List<EditorState> forms = parts.stream().map(part -> part.form().copy()).toList();
    PartLinks graph = effectiveLinks();
    Map<String, Events.Root> roots = new HashMap<>();
    for (EditorState form : forms) {
      form.text = Events.withLinks(form.text, List.of());
      form.rootPath = "";
      form.rootEdits.clear();
      form.removedRoots.addAll(owner.removedRoots);
      form.deferRootCleanup = true;
      if (graph.rootIds().contains(form.partId)) {
        form.text = Events.root(form.text);
        var doc = new Markdown(form.heading + "\n" + form.text + "\n");
        roots.put(
            form.partId,
            new Events.Root(form.destinationPath, doc.blocks.get(0), Events.rootId(form.text)));
      }
    }
    for (EditorState form : forms) {
      List<String> raw = new ArrayList<>();
      for (EventLink ref : graph.refs(form.partId))
        raw.add(
            ref.local()
                ? Events.format(roots.get(ref.part()))
                : ref.raw().isEmpty()
                    ? "[["
                        + ref.path().replaceFirst("\\.md$", "")
                        + "#^"
                        + ref.id()
                        + "|"
                        + ref.label()
                        + "]]"
                    : ref.raw());
      form.text = Events.withLinks(form.text, raw);
      if (!form.sourcePath.equals(form.destinationPath))
        form.text = Events.bindLocalParents(form.sourcePath, form.text);
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
    List<EditorState> forms = savedForms().stream().map(WorkDaily::physical).toList();
    boolean internalEvents = !effectiveLinks().rootIds().isEmpty();
    // A single part retains the existing in-place edit and protected-anchor semantics.
    if (parts.size() == 1 && !(internalEvents && !owner.sourcePath.equals(owner.destinationPath))) {
      EditorState form = forms.get(0);
      form.edit(value);
      return VaultRepository.addRoots(
          VaultRepository.plan(
              form, source, new Markdown(form.destinationSnapshot), template, date, now, rules),
          pendingRootEdits());
    }
    if (!owner.fresh) owner.originalBlock(source);
    LinkedHashMap<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    before.put(owner.destinationPath, destination.text);
    before.put(owner.sourcePath, source.text);
    for (var form : forms) before.putIfAbsent(form.destinationPath, form.destinationSnapshot);
    after.putAll(before);

    if (!owner.fresh
        && forms.stream()
            .allMatch(
                f ->
                    f.kind == Markdown.Kind.SECTION
                        && f.destinationPath.equals(owner.sourcePath)
                        && f.heading.equals(owner.originalBlock(source).section().heading()))) {
      StringBuilder output = new StringBuilder();
      for (int i = 0; i < forms.size(); i++) {
        if (i > 0) {
          var previous = forms.get(i - 1);
          boolean adjacentFields =
              !previous.fieldPrefix.isEmpty()
                  && !forms.get(i).fieldPrefix.isEmpty()
                  && !Events.hasRelations(previous.sourcePath, previous.text)
                  && !Events.hasRelations(forms.get(i).sourcePath, forms.get(i).text);
          output.append(adjacentFields ? "\n" : "\n\n");
        }
        output.append(forms.get(i).savedText());
      }
      String joined = output.toString();
      List<VaultRepository.Change> changes =
          List.of(
              new VaultRepository.Change(
                  owner.sourcePath,
                  source.text,
                  source.replace(owner.originalBlock(source), joined).text));
      return VaultRepository.addRoots(changes, pendingRootEdits());
    }
    boolean relocatedGroup = internalEvents && !owner.sourcePath.equals(owner.destinationPath);
    if (relocatedGroup && !owner.fresh) {
      if ((!Events.id(owner.original).isEmpty()
              && !owner.removedRoots.contains(
                  new Events.Parent(owner.sourcePath, Events.id(owner.original))))
          || forms.stream().anyMatch(f -> f.text.contains("[[#^")))
        throw new IllegalArgumentException(
            "Корневой блок нельзя переносить между днями: ссылки должны сохранить адрес.");
      after.put(owner.sourcePath, source.replace(owner.originalBlock(source), "").text);
    }
    boolean firstPart = true;
    for (EditorState form : forms) {
      if (!owner.sourcePath.equals(owner.destinationPath)
          && ((!Events.id(owner.original).isEmpty()
                  && !owner.removedRoots.contains(
                      new Events.Parent(owner.sourcePath, Events.id(owner.original))))
              || forms.stream().anyMatch(f -> f.text.contains("[[#^"))))
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
    List<String> writeOrder = new ArrayList<>(before.keySet());
    if (writeOrder.remove(owner.sourcePath)) writeOrder.add(owner.sourcePath);
    for (String path : writeOrder) {
      String updated = after.get(path);
      result.add(new VaultRepository.Change(path, before.get(path), updated));
    }
    result = new ArrayList<>(VaultRepository.addRoots(result, pendingRootEdits()));
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
