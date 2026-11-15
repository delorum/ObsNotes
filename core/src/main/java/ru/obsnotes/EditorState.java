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

  public String editableText() {
    return TaggedText.parse(parts().body).clean;
  }

  public List<TaggedText.Tag> tags() {
    return TaggedText.parse(parts().body).tags;
  }

  public void edit(String value) {
    Parts parts = parts();
    text = parts.join(TaggedText.parse(parts.body).edit(value).render());
  }

  public void addTags(int cursor, Collection<String> ids, boolean oneLine, boolean person) {
    Parts parts = parts();
    text = parts.join(TaggedText.parse(parts.body).add(cursor, ids, oneLine, person).render());
  }

  public void removeTag(int index) {
    Parts parts = parts();
    text = parts.join(TaggedText.parse(parts.body).remove(index).render());
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

  public Markdown.Block originalBlock(Markdown document) {
    return document.blocks.stream()
        .filter(b -> b.start() == start && b.end() == end && b.text().equals(original))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Исходный блок изменился. Черновик сохранён."));
  }

  public Markdown.Target target() {
    return new Markdown.Target(heading, heading, targetLine, targetSource, kind);
  }
}
