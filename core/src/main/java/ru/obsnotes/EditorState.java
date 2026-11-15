package ru.obsnotes;

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

  /** Generated event callout and stable anchor are metadata, not text-editing controls. */
  public String editableText() {
    String normalized = text.replace("\r\n", "\n");
    int at = normalized.indexOf("\n> [!info]- Дополнения к событию");
    String content = at < 0 ? normalized : normalized.substring(0, at);
    return content.replaceFirst("[ \\t]+\\^[A-Za-z0-9_-]+[ \\t]*$", "");
  }

  public void edit(String value) {
    text = text.replace("\r\n", "\n");
    String id = Events.id(text);
    int at = text.indexOf("\n> [!info]- Дополнения к событию");
    String metadata = at < 0 ? "" : text.substring(at);
    text = value + (id.isEmpty() ? "" : " ^" + id) + metadata;
  }

  public String savedText() {
    String visible = editableText();
    if (visible.isBlank()) throw new IllegalArgumentException("Введите текст");
    String id = Events.id(text);
    if (id.isEmpty()) return text.stripTrailing();
    String normalized = text.replace("\r\n", "\n");
    int at = normalized.indexOf("\n> [!info]- Дополнения к событию");
    return visible.stripTrailing() + " ^" + id + (at < 0 ? "" : normalized.substring(at));
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
