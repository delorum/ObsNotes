package ru.obsnotes;

import java.util.*;
import java.util.regex.*;

/** Form state belongs to core. Android widgets are merely its projection. */
public final class EditorState {
  public String sourcePath = "",
      original = "",
      text = "",
      destinationPath = "",
      heading = "## Заметки",
      targetSource = "";
  public String sourceSnapshot = "", destinationSnapshot = "";
  public String rootPath = "", rootOriginal = "", rootReplacement = "", rootSnapshot = "";
  public int start = -1, end = -1, targetLine = -1, cursor = 0;
  public Markdown.Kind kind = Markdown.Kind.SECTION;
  public boolean fresh = true;
  public boolean appendTaskText = true;
  public boolean editFields = false, routesInitialized = false, routeChosen = false;
  public boolean weatherForm = false;
  public boolean taskAppend = false;
  public String fallbackHeading = "";
  public List<Routing.Choice> routes = List.of();

  public String initialRevision = "";
  public String partId = UUID.randomUUID().toString();
  public EditorParts parts;
  public boolean secretOpen, sensitive;
  public String secretBefore = "", secretClear = "";
  public final Set<Events.Parent> removedRoots = new LinkedHashSet<>();
  public final List<EventLink.RootEdit> rootEdits = new ArrayList<>();
  public String removedRootBefore = "", removedRootAfter = "";
  public boolean deferRootCleanup;

  public void enableParts(Markdown template) {
    if (parts == null && !weatherForm && !taskAppend && !editFields) parts = new EditorParts(this);
    if (parts != null) {
      parts.template = template;
      parts.sync();
      parts.migrateLegacyLinks();
      parts.importLinks();
    }
  }

  public EditorState activePart() {
    return parts == null ? this : parts.selected();
  }

  /** Copy a paragraph form without recursively copying the shared editor. */
  public EditorState copy() {
    EditorState s = new EditorState();
    s.partId = partId;
    s.secretOpen = secretOpen;
    s.sensitive = sensitive;
    s.secretBefore = secretBefore;
    s.secretClear = secretClear;
    s.removedRoots.addAll(removedRoots);
    s.rootEdits.addAll(rootEdits);
    s.removedRootBefore = removedRootBefore;
    s.removedRootAfter = removedRootAfter;
    s.sourcePath = sourcePath;
    s.original = original;
    s.text = text;
    s.destinationPath = destinationPath;
    s.heading = heading;
    s.targetSource = targetSource;
    s.sourceSnapshot = sourceSnapshot;
    s.destinationSnapshot = destinationSnapshot;
    s.rootPath = rootPath;
    s.rootOriginal = rootOriginal;
    s.rootReplacement = rootReplacement;
    s.rootSnapshot = rootSnapshot;
    s.start = start;
    s.end = end;
    s.targetLine = targetLine;
    s.cursor = cursor;
    s.kind = kind;
    s.fresh = fresh;
    s.appendTaskText = appendTaskText;
    s.editFields = editFields;
    s.routesInitialized = routesInitialized;
    s.routeChosen = routeChosen;
    s.weatherForm = weatherForm;
    s.taskAppend = taskAppend;
    s.fallbackHeading = fallbackHeading;
    s.routes = List.copyOf(routes);
    return s;
  }

  void replaceBody(String body) {
    Parts p = parts();
    text = p.join(secretOpen ? SecretText.opened(body) : SecretText.edit(p.body, body));
  }

  void clearEventMetadata() {
    String plainBody = editableBody(parts());
    secretOpen = false;
    secretBefore = secretClear = "";
    text = plainBody;
    rootPath = rootOriginal = rootReplacement = rootSnapshot = "";
    rootEdits.clear();
    removedRoots.clear();
    removedRootBefore = removedRootAfter = "";
  }

  private static EditorState at(String path, Markdown source, String heading) {
    EditorState state = new EditorState();
    state.sourcePath = state.destinationPath = path;
    state.sourceSnapshot = state.destinationSnapshot = source.text;
    state.heading = heading;
    return state;
  }

  public static EditorState newNote(
      String path, Markdown source, Markdown template, String heading) {
    if (source.section(heading) == null && template.section(heading) == null)
      throw new IllegalArgumentException("Раздел не найден");
    EditorState state = at(path, source, heading);
    state.routeChosen = !heading.equals("## Заметки");
    state.initializeRoutes(template);
    EditorRevision.remember(state, template);
    return state;
  }

  public static EditorState editing(
      String path, Markdown source, Markdown template, Markdown.Block block) {
    if (!source.blocks.contains(block))
      throw new IllegalArgumentException("Текстовый блок изменился");
    EditorState state = at(path, source, block.section().heading());
    state.fresh = false;
    state.original = state.text = block.text();
    state.start = block.start();
    state.end = block.end();
    state.initializeRoutes(template);
    state.cursor = state.editableText().length();
    EditorRevision.remember(state, template);
    return state;
  }

  public record InputCaret(int field, int offset) {}

  public static EditorState appendTask(
      String path, Markdown source, Markdown template, DiaryTasks.Task selected) {
    var task =
        DiaryTasks.in(source.text, 0).stream()
            .filter(t -> t.start() == selected.start() && t.source().equals(selected.source()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Задача изменилась"));
    var section =
        source.sections.stream()
            .filter(s -> task.start() >= s.body() && task.start() < s.end())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Раздел задачи не найден"));
    EditorState state = at(path, source, section.heading());
    state.chooseTarget(
        new Markdown.Target(
            task.label(), section.heading(), task.start(), task.source(), Markdown.Kind.TASK));
    state.taskAppend = true;
    state.routeChosen = true;
    state.initializeRoutes(template);
    EditorRevision.remember(state, template);
    return state;
  }

  /** Cursor offsets refer to visible values, never field keys or hidden Markdown metadata. */
  public InputCaret inputCaret() {
    int at = Math.max(0, cursor);
    int count = fieldCount();
    if (count == 0) return new InputCaret(0, Math.min(at, editableText().length()));
    for (int i = 0; i < count; i++) {
      int length = fieldValue(i).length();
      if (at <= length || i == count - 1) return new InputCaret(i, Math.min(at, length));
      at -= length + 1;
    }
    throw new IllegalStateException("Нет поля ввода");
  }

  public boolean taskOnly() {
    return kind == Markdown.Kind.TASK && !appendTaskText;
  }

  public boolean emptyNote() {
    if (parts != null)
      return parts.value.isBlank()
          && !(!fresh
              && Events.prefix(sourcePath, original).body().isBlank()
              && parts.parts.stream()
                  .anyMatch(part -> !parts.references(parts.parts.indexOf(part)).isEmpty()));
    Parts p = parts();
    return TaggedText.parse(secretOpen ? SecretText.openBody(p.body) : p.body).clean.isBlank()
        && !parentOnly();
  }

  public static EditorState weather(String path, Markdown source) {
    EditorState state = at(path, source, "## Погода");
    state.fresh = false;
    state.weatherForm = true;
    state.editFields = true;
    state.original =
        state.text =
            "Погода:: "
                + InlineFields.value(source, "Погода")
                + "\nТемпература:: "
                + InlineFields.value(source, "Температура");
    return state;
  }

  public void addWeather(WeatherSummary summary) {
    for (int i = 0; i < fieldCount(); i++) {
      String addition =
          fieldName(i).equals("Погода")
              ? summary.weather()
              : fieldName(i).equals("Температура") ? summary.temperature() : "";
      if (!addition.isBlank()) editField(i, InlineFields.append(fieldValue(i), addition));
    }
  }

  public Markdown savedWeather(Markdown source, Markdown template) {
    Map<String, String> before = new HashMap<>();
    for (var field : FieldBlock.parse(original).fields()) before.put(field.key(), field.value());
    Map<String, String> values = new LinkedHashMap<>();
    for (var field : FieldBlock.parse(text).fields())
      if (!field.value().equals(before.get(field.key()))) values.put(field.key(), field.value());
    return InlineFields.write(source, values, "## Погода", template);
  }

  public void chooseTarget(Markdown.Target target) {
    if (!fresh
        && sourcePath.equals(destinationPath)
        && target.kind() != Markdown.Kind.SECTION
        && target.line() >= start
        && target.line() < end)
      throw new IllegalArgumentException("Нельзя перенести блок внутрь самого себя");
    heading = target.heading();
    kind = target.kind();
    targetLine = target.line();
    targetSource = target.source();
    if (kind != Markdown.Kind.TASK) {
      appendTaskText = true;
      taskAppend = false;
    } else if (!DiaryTasks.isDiary(target.source())) {
      taskAppend = false;
    }
  }

  public void chooseDay(String path, String snapshot) {
    if (parts != null) {
      for (var part : parts.parts) part.form().chooseDay(path, snapshot);
    }
    destinationPath = path;
    destinationSnapshot = path.equals(sourcePath) ? sourceSnapshot : snapshot;
    kind = Markdown.Kind.SECTION;
    taskAppend = false;
    targetLine = -1;
    targetSource = "";
    appendTaskText = true;
    routesInitialized = false;
    routes = List.of();
  }

  public void attach(Events.Root selected) {
    Events.Root root = selected;
    String replacement = selected.id().isEmpty() ? Events.root(selected.block().text()) : "";
    if (!replacement.isEmpty())
      root = new Events.Root(selected.path(), selected.block(), Events.id(replacement));
    String linked = Events.link(text, root);
    rootPath = replacement.isEmpty() ? "" : selected.path();
    rootOriginal = selected.block().text();
    rootReplacement = replacement;
    rootSnapshot = sourceSnapshot;
    text = linked;
  }

  private record Parts(String link, String body, String anchor, String callout) {
    String join(String value) {
      return link + value + (anchor.isEmpty() ? "" : " ^" + anchor) + callout;
    }
  }

  private Parts parts() {
    String value = text.replace("\r\n", "\n");
    int at = Events.rootId(value).isEmpty() ? -1 : Events.rootCalloutStart(value);
    String callout = at < 0 ? "" : value.substring(at),
        body = at < 0 ? value : value.substring(0, at);
    Matcher anchor = Pattern.compile("(?:^|[ \t]+)\\^([A-Za-z0-9_-]+)[ \t]*$").matcher(body);
    String id = "";
    if (anchor.find()) {
      id = anchor.group(1);
      body = body.substring(0, anchor.start());
    }
    var prefix = Events.prefix(sourcePath, body);
    String prefixRaw = prefix.raw();
    body = prefix.body();
    return new Parts(prefixRaw, body, id, callout);
  }

  private String editableBody(Parts p) {
    String body = secretOpen ? SecretText.openBody(p.body) : p.body;
    var fields = FieldBlock.parse(body);
    return editFields && fields.present() ? fields.values() : body;
  }

  private String joinVisible(Parts p, String visible) {
    return joinEditable(p, SecretText.edit(editableBody(p), visible));
  }

  public boolean locked() {
    return SecretText.has(parts().body);
  }

  public void toggleSecret(char[] password, Markdown template) {
    if (kind != Markdown.Kind.SECTION || editFields || taskAppend || weatherForm)
      throw new IllegalArgumentException("Шифрование доступно для текстовых блоков в разделах");
    Parts p = parts();
    String body = editableBody(p);
    if (body.isBlank() && secretBefore.isEmpty())
      throw new IllegalArgumentException("Сначала введите текст");
    if (locked()) {
      String clear = SecretText.decrypt(p.body, password);
      secretBefore = p.body;
      secretClear = clear;
      secretOpen = true;
      sensitive = true;
      text = p.join(SecretText.opened(clear));
      routesInitialized = false;
      initializeRoutes(template);
    } else {
      String encrypted = "";
      if (!secretBefore.isEmpty() && body.equals(secretClear)) {
        try {
          if (SecretText.decrypt(secretBefore, password).equals(body)) encrypted = secretBefore;
        } catch (IllegalArgumentException differentPassword) {
          /* Re-encrypt with the new password. */
        }
      }
      if (encrypted.isEmpty()) encrypted = "`secret " + InlineCrypto.encrypt(body, password) + "`";
      text = p.join(encrypted);
      secretBefore = secretClear = "";
      secretOpen = false;
      sensitive = true;
    }
  }

  private String joinEditable(Parts p, String raw) {
    var fields = FieldBlock.parse(p.body);
    String value = editFields && fields.present() ? fields.withValues(raw) : raw;
    return p.join(secretOpen ? SecretText.opened(value) : value);
  }

  public int fieldCount() {
    return editFields ? FieldBlock.parse(parts().body).fields().size() : 0;
  }

  public String fieldName(int index) {
    return FieldBlock.parse(parts().body).fields().get(index).key();
  }

  public String fieldValue(int index) {
    return TaggedText.parse(FieldBlock.parse(parts().body).fields().get(index).value()).clean;
  }

  public void editField(int index, String value) {
    Parts p = parts();
    var fields = FieldBlock.parse(p.body);
    String raw =
        TaggedText.parse(fields.fields().get(index).value())
            .edit(value.replace("\r\n", "\n").replace("\n", "; "))
            .render();
    text = p.join(fields.edit(index, raw));
  }

  public String editableText() {
    if (parts != null) return parts.value;
    return TaggedText.parse(SecretText.display(editableBody(parts()))).clean;
  }

  public List<TaggedText.Tag> tags() {
    if (parts != null) return parts.tags();
    return TaggedText.parse(SecretText.display(editableBody(parts()))).tags;
  }

  public void edit(String value) {
    if (parts != null) {
      parts.edit(value);
      return;
    }
    if (taskAppend) value = DiaryTasks.addition(value);
    Parts parts = parts();
    text =
        joinVisible(
            parts, TaggedText.parse(SecretText.display(editableBody(parts))).edit(value).render());
  }

  public void addTags(int cursor, Collection<String> ids, boolean oneLine, boolean person) {
    Parts parts = parts();
    text =
        joinVisible(
            parts,
            TaggedText.parse(SecretText.display(editableBody(parts)))
                .add(cursor, ids, oneLine, person)
                .render());
  }

  public void removeTag(int index) {
    Parts parts = parts();
    text =
        joinVisible(
            parts,
            TaggedText.parse(SecretText.display(editableBody(parts))).remove(index).render());
  }

  public List<Events.Leading> parentLinks() {
    return Events.prefix(sourcePath, text).links();
  }

  public String rootLinkLabel() {
    return parentLinks().isEmpty() ? "" : parentLinks().get(0).label();
  }

  public List<EventLink.RootEdit> pendingRoots() {
    List<EventLink.RootEdit> edits = new ArrayList<>(rootEdits);
    if (!rootPath.isEmpty())
      edits.add(new EventLink.RootEdit(rootPath, rootOriginal, rootReplacement, rootSnapshot));
    return edits;
  }

  public void removeRootLink(int index) {
    List<String> links = new ArrayList<>(parentLinks().stream().map(Events.Leading::raw).toList());
    links.remove(index);
    text = Events.withLinks(text, links);
    rootEdits.removeIf(
        edit -> !Events.references(text, sourcePath, edit.path(), Events.id(edit.replacement())));
    if (!rootPath.isEmpty()
        && !Events.references(text, sourcePath, rootPath, Events.id(rootReplacement)))
      rootPath = rootOriginal = rootReplacement = rootSnapshot = "";
  }

  public void removeRootLink() {
    text = Events.withLinks(text, List.of());
    rootEdits.clear();
    rootPath = rootOriginal = rootReplacement = rootSnapshot = "";
  }

  public void addRootLink(Events.Root root, String snapshot, Integer replacing) {
    String replacement = root.id().isEmpty() ? Events.root(root.block().text()) : "";
    Events.Root resolved =
        replacement.isEmpty()
            ? root
            : new Events.Root(root.path(), root.block(), Events.id(replacement));
    if (resolved.path().equals(sourcePath) && resolved.id().equals(Events.id(text)))
      throw new IllegalArgumentException("Нельзя связать блок с самим собой");
    var existing = parentLinks();
    List<String> links = new ArrayList<>(existing.stream().map(Events.Leading::raw).toList());
    for (int i = 0; i < existing.size(); i++)
      if ((replacing == null || i != replacing)
          && existing.get(i).parent().equals(new Events.Parent(resolved.path(), resolved.id()))) {
        if (replacing == null) return;
        throw new IllegalArgumentException("Такая связь уже есть");
      }
    if (replacing == null) links.add(Events.format(resolved));
    else links.set(replacing, Events.format(resolved));
    text = Events.withLinks(text, links);
    if (!replacement.isEmpty())
      rootEdits.add(
          new EventLink.RootEdit(root.path(), root.block().text(), replacement, snapshot));
    rootEdits.removeIf(
        edit -> !Events.references(text, sourcePath, edit.path(), Events.id(edit.replacement())));
  }

  public void deleteRootMetadata() {
    String id = Events.rootId(text);
    if (id.isEmpty()) return;
    long matches =
        new Markdown(sourceSnapshot)
            .blocks.stream().filter(block -> Events.id(block.text()).equals(id)).count();
    if (matches > 1) throw new IllegalArgumentException("Якорь корня неоднозначен");
    String before = text;
    boolean preserve = Events.id(original).equals(id) && Events.rootId(original).isEmpty();
    String after = Events.withoutRoot(text, !preserve);
    text = after;
    removedRootBefore = before;
    removedRootAfter = after;
    if (new Markdown(sourceSnapshot)
        .blocks.stream().anyMatch(block -> Events.rootId(block.text()).equals(id)))
      removedRoots.add(new Events.Parent(sourcePath, id));
  }

  public void restoreRootMetadata() {
    if (removedRootBefore.isEmpty()) return;
    String id = Events.rootId(removedRootBefore);
    var current = Events.prefix(sourcePath, text);
    String oldPlain = Events.prefix(sourcePath, removedRootAfter).body();
    if (current.body().equals(oldPlain))
      text = current.raw() + Events.prefix(sourcePath, removedRootBefore).body();
    else if (!id.isEmpty())
      text = current.raw() + Events.root(current.body().stripTrailing() + " ^" + id);
    if (!id.isEmpty()) removedRoots.remove(new Events.Parent(sourcePath, id));
    removedRootBefore = removedRootAfter = "";
  }

  private boolean parentOnly() {
    return !fresh
        && Events.prefix(sourcePath, original).body().isBlank()
        && !parentLinks().isEmpty();
  }

  public String savedText() {
    Parts parts = parts();
    TaggedText tags = TaggedText.parse(secretOpen ? SecretText.openBody(parts.body) : parts.body);
    if (tags.clean.isBlank()) {
      if (parentOnly()) return parts.link.stripTrailing();
      throw new IllegalArgumentException("Введите текст");
    }
    return parts.join(tags.render().stripTrailing());
  }

  public Routing.Catalog routingCatalog(Markdown template) {
    return new Routing.Catalog(new Markdown(destinationSnapshot), template);
  }

  public void initializeRoutes(Markdown template) {
    if (routesInitialized) return;
    if (fallbackHeading.isEmpty()) fallbackHeading = heading;
    if (!fresh && FieldBlock.parse(parts().body).present()) editFields = true;
    var catalog = routingCatalog(template);
    List<Routing.Choice> choices = new ArrayList<>();
    choices.add(catalog.resolve(target()));
    Set<String> headings = new HashSet<>();
    headings.add(heading);
    for (var tag : tags())
      if (tag.kind().equals("crossPeople"))
        for (var token : catalog.split(tag.ids()))
          if (token.choice() != null && headings.add(token.choice().target().heading()))
            choices.add(token.choice());
    routes = List.copyOf(choices);
    routesInitialized = true;
  }

  public List<Routing.Choice> pickerRoutes() {
    return !routeChosen
            && routes.size() == 1
            && kind == Markdown.Kind.SECTION
            && heading.equals("## Заметки")
        ? List.of()
        : routes;
  }

  public List<String> crossIds() {
    return routes.size() < 2
        ? List.of()
        : routes.subList(1, routes.size()).stream()
            .map(Routing.Choice::crossId)
            .collect(java.util.stream.Collectors.toList());
  }

  public void applyRoutes(List<Routing.Choice> choices, Routing.Catalog catalog) {
    String error = validateRoutes(choices);
    if (error != null) throw new IllegalArgumentException(error);
    if (editFields && !choices.isEmpty() && choices.get(0).target().kind() != Markdown.Kind.SECTION)
      throw new IllegalArgumentException("Для поля изменяйте значение или раздел");
    Markdown.Target selected =
        choices.isEmpty()
            ? new Markdown.Target(fallbackHeading, fallbackHeading, -1, "", Markdown.Kind.SECTION)
            : choices.get(0).target();
    chooseTarget(selected);
    Parts p = parts();
    TaggedText body = TaggedText.parse(editableBody(p));
    List<TaggedText.Tag> keep = new ArrayList<>();
    for (var tag : body.tags) {
      if (!tag.kind().equals("crossPeople")) {
        keep.add(tag);
        continue;
      }
      String unknown =
          String.join(
              "",
              catalog.split(tag.ids()).stream()
                  .filter(t -> t.choice() == null)
                  .map(Routing.Token::text)
                  .collect(java.util.stream.Collectors.toList()));
      if (!unknown.isEmpty()) keep.add(new TaggedText.Tag(tag.kind(), unknown, tag.position()));
    }
    routes = List.copyOf(choices);
    routeChosen = true;
    routesInitialized = true;
    TaggedText updated = body.withTags(keep);
    if (routes.size() > 1) updated = updated.add(0, crossIds(), false, false);
    text = joinEditable(p, updated.render());
  }

  public String validateRoutes(List<Routing.Choice> choices) {
    String error = Routing.validate(choices);
    if (error != null) return error;
    if (!choices.isEmpty()
        && choices.get(0).target().kind() == Markdown.Kind.FIELD
        && editableText().contains("\n")) return "Для Dataview-ключа нужен однострочный текст";
    return null;
  }

  public void removeRoute(int index, Routing.Catalog catalog) {
    List<Routing.Choice> next = new ArrayList<>(routes);
    next.remove(index);
    applyRoutes(next, catalog);
  }

  public List<Routing.Choice> replacingRoute(int index, Routing.Choice choice) {
    List<Routing.Choice> next = new ArrayList<>(routes);
    next.set(index, choice);
    return List.copyOf(next);
  }

  public void replaceRoute(int index, Routing.Choice choice, Routing.Catalog catalog) {
    applyRoutes(replacingRoute(index, choice), catalog);
  }

  public void replaceTag(int index, List<String> ids, Routing.Catalog catalog) {
    Parts p = parts();
    TaggedText body = TaggedText.parse(SecretText.display(editableBody(p)));
    var old = body.tags.get(index);
    List<String> replacement = new ArrayList<>();
    if (old.kind().equals("crossPeople"))
      for (var token : catalog.split(old.ids()))
        if (token.choice() != null) replacement.add(token.text());
    replacement.addAll(ids);
    Set<String> seen = new HashSet<>();
    replacement.removeIf(id -> !seen.add(id.toLowerCase(Locale.ROOT)));
    text = joinVisible(p, body.replace(index, replacement).render());
    if (old.kind().equals("crossPeople")) {
      List<Routing.Choice> next = new ArrayList<>(routes);
      for (var tag : tags())
        if (tag.kind().equals("crossPeople"))
          for (var token : catalog.split(tag.ids()))
            if (token.choice() != null
                && next.stream()
                    .noneMatch(c -> c.target().heading().equals(token.choice().target().heading())))
              next.add(token.choice());
      routes = List.copyOf(next);
    }
  }

  /** A conflict copy preserves the visible text, without duplicating anchors or active keys. */
  public String conflictCopyText() {
    if (parts != null) {
      StringBuilder raw = new StringBuilder();
      int end = 0;
      for (var part : parts.parts) {
        raw.append(parts.baseline, end, part.start());
        raw.append(part.form().conflictCopyText());
        end = part.end();
      }
      raw.append(parts.baseline.substring(end));
      return SecretText.edit(raw.toString(), FieldBlock.labels(parts.value));
    }
    Parts p = parts();
    String value =
        FieldBlock.labels(
            TaggedText.parse(secretOpen ? SecretText.openBody(p.body) : p.body).clean);
    if (kind == Markdown.Kind.FIELD && !editFields) {
      var field = Markdown.FIELD.matcher(targetSource);
      if (field.matches()) return field.group(1) + ": " + value;
    }
    return value;
  }

  public String unmanagedCrossIds(TaggedText.Tag tag, Routing.Catalog catalog) {
    if (!tag.kind().equals("crossPeople")) return tag.ids();
    return String.join(
        "",
        catalog.split(tag.ids()).stream()
            .filter(t -> t.choice() == null)
            .map(Routing.Token::text)
            .collect(java.util.stream.Collectors.toList()));
  }

  public void removeUnmanagedTag(int index, Routing.Catalog catalog) {
    Parts p = parts();
    TaggedText body = TaggedText.parse(SecretText.display(editableBody(p)));
    var tag = body.tags.get(index);
    if (!tag.kind().equals("crossPeople")) {
      removeTag(index);
      return;
    }
    String known =
        String.join(
            "",
            catalog.split(tag.ids()).stream()
                .filter(t -> t.choice() != null)
                .map(Routing.Token::text)
                .collect(java.util.stream.Collectors.toList()));
    List<TaggedText.Tag> tags = new ArrayList<>(body.tags);
    if (known.isEmpty()) tags.remove(index);
    else tags.set(index, new TaggedText.Tag(tag.kind(), known, tag.position()));
    text = joinVisible(p, body.withTags(tags).render());
  }

  public Markdown.Block originalBlock(Markdown document) {
    return document.blocks.stream()
        .filter(b -> b.start() == start && b.end() == end && b.text().equals(original))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Исходный блок изменился. Текст остался в редакторе."));
  }

  public Markdown.Target target() {
    return new Markdown.Target(heading, heading, targetLine, targetSource, kind);
  }
}
