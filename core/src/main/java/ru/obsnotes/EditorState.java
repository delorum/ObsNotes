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
  public String fallbackHeading = "";
  public List<Routing.Choice> routes = List.of();

  public boolean taskOnly() {
    return kind == Markdown.Kind.TASK && !appendTaskText;
  }

  public boolean emptyNote() {
    return TaggedText.parse(parts().body).clean.isBlank();
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
    if (kind != Markdown.Kind.TASK) appendTaskText = true;
  }

  public void chooseDay(String path, String snapshot) {
    if (!path.equals(sourcePath)
        && DiaryLayout.fileName(path)
                .substring(0, 10)
                .compareTo(DiaryLayout.fileName(sourcePath).substring(0, 10))
            >= 0) throw new IllegalArgumentException("Выберите существующий прошлый день");
    destinationPath = path;
    destinationSnapshot = path.equals(sourcePath) ? sourceSnapshot : snapshot;
    kind = Markdown.Kind.SECTION;
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
    int at = value.indexOf("\n> [!info]- Дополнения к событию");
    String callout = at < 0 ? "" : value.substring(at),
        body = at < 0 ? value : value.substring(0, at);
    Matcher anchor = Pattern.compile("(?:^|[ \t]+)\\^([A-Za-z0-9_-]+)[ \t]*$").matcher(body);
    String id = "";
    if (anchor.find()) {
      id = anchor.group(1);
      body = body.substring(0, anchor.start());
    }
    Matcher link = Pattern.compile("^\\[\\[[^\\n]*#\\^[^\\n]*]]\\n").matcher(body);
    String prefix = "";
    if (link.find()) {
      prefix = link.group();
      body = body.substring(link.end());
    }
    return new Parts(prefix, body, id, callout);
  }

  private String editableBody(Parts p) {
    var fields = FieldBlock.parse(p.body);
    return editFields && fields.present() ? fields.values() : p.body;
  }

  private String joinEditable(Parts p, String raw) {
    var fields = FieldBlock.parse(p.body);
    return p.join(editFields && fields.present() ? fields.withValues(raw) : raw);
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
    return TaggedText.parse(editableBody(parts())).clean;
  }

  public List<TaggedText.Tag> tags() {
    return TaggedText.parse(editableBody(parts())).tags;
  }

  public void edit(String value) {
    Parts parts = parts();
    text = joinEditable(parts, TaggedText.parse(editableBody(parts)).edit(value).render());
  }

  public void addTags(int cursor, Collection<String> ids, boolean oneLine, boolean person) {
    Parts parts = parts();
    text =
        joinEditable(
            parts,
            TaggedText.parse(editableBody(parts)).add(cursor, ids, oneLine, person).render());
  }

  public void removeTag(int index) {
    Parts parts = parts();
    text = joinEditable(parts, TaggedText.parse(editableBody(parts)).remove(index).render());
  }

  public String rootLinkLabel() {
    String link = parts().link;
    if (link.isEmpty()) return "";
    int alias = link.indexOf('|');
    return alias < 0 ? "Корневой блок" : link.substring(alias + 1, link.lastIndexOf("]]"));
  }

  public void removeRootLink() {
    Parts p = parts();
    text = new Parts("", p.body, p.anchor, p.callout).join(p.body);
    rootPath = "";
    rootOriginal = "";
    rootReplacement = "";
    rootSnapshot = "";
  }

  public String savedText() {
    Parts parts = parts();
    TaggedText tags = TaggedText.parse(parts.body);
    if (tags.clean.isBlank()) throw new IllegalArgumentException("Введите текст");
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
    TaggedText body = TaggedText.parse(editableBody(p));
    var old = body.tags.get(index);
    List<String> replacement = new ArrayList<>();
    if (old.kind().equals("crossPeople"))
      for (var token : catalog.split(old.ids()))
        if (token.choice() != null) replacement.add(token.text());
    replacement.addAll(ids);
    Set<String> seen = new HashSet<>();
    replacement.removeIf(id -> !seen.add(id.toLowerCase(Locale.ROOT)));
    text = joinEditable(p, body.replace(index, replacement).render());
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
    String value = FieldBlock.labels(TaggedText.parse(parts().body).clean);
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
    TaggedText body = TaggedText.parse(editableBody(p));
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
    text = joinEditable(p, body.withTags(tags).render());
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
