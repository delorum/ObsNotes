package ru.obsnotes;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Display filtering is separate from source blocks, offsets and save/delete operations. */
public final class DayContentRules {
  public static final String PATH = "Скрипты/Modules/Дневник/diaryCleanUtils.js";
  public static final DayContentRules EMPTY = new DayContentRules(List.of(), List.of());
  private final Set<String> excluded;
  private final List<String> contains;

  public DayContentRules(Collection<String> excluded, Collection<String> contains) {
    this.excluded = Set.copyOf(excluded);
    this.contains = List.copyOf(contains);
  }

  public Set<String> excludedLines() {
    return excluded;
  }

  public List<String> excludedContains() {
    return contains;
  }

  public static DayContentRules parse(String script) {
    return new DayContentRules(
        JsStringLists.read(script, "excludeLines"), JsStringLists.read(script, "excludeContains"));
  }

  public static DayContentRules load(VaultStorage storage) throws IOException {
    try {
      return parse(storage.read(PATH));
    } catch (FileNotFoundException missing) {
      return EMPTY;
    } catch (IllegalArgumentException invalid) {
      throw new IOException(invalid.getMessage(), invalid);
    }
  }

  public boolean hidden(String line) {
    String raw = line.strip(), clean = CrossReferences.clean(raw);
    return excluded.contains(raw)
        || excluded.contains(clean)
        || contains.stream().anyMatch(part -> raw.contains(part) || clean.contains(part));
  }

  public static boolean emptyDiaryTask(String line) {
    String clean = CrossReferences.clean(line).strip();
    if (!DiaryTasks.isDiary(clean)) return false;
    if (clean.startsWith("- [ ]")) return true;
    String normalized = clean.replace("#task/diary", "#task/dairy").replace("- [X]", "- [x]");
    return normalized.startsWith("- [x] #task/dairy/car Не ездил на машине")
        || normalized.startsWith("- [x] #task/dairy/diet не соблюдал диету");
  }

  public static boolean emptyDataviewKey(String line) {
    String clean = CrossReferences.clean(line).strip();
    var field = Markdown.FIELD.matcher(clean);
    if (!field.matches() || !field.group(2).isBlank()) return false;
    String key = field.group(1);
    return !Set.of("ФотоПогоды", "ShortDescr", "ShortDescrWeek").contains(key);
  }

  public boolean countable(String line) {
    String clean = CrossReferences.clean(line).strip();
    return !clean.isBlank() && !hidden(line) && !emptyDiaryTask(line) && !emptyDataviewKey(line);
  }

  public record View(List<MarkdownLines.Line> lines, String raw, String text, boolean counted) {}

  public record Item(Markdown.Block original, boolean projection, View view) {}

  public View view(String source) {
    List<MarkdownLines.Line> visible = new ArrayList<>();
    boolean counted = false, generatedCallout = false;
    for (var line : MarkdownLines.scan(source)) {
      if (!line.literal() && hidden(line.text())) continue;
      visible.add(line);
      if (line.text().startsWith("> [!info]- Дополнения к событию")) generatedCallout = true;
      boolean rootLink = line.start() == 0 && line.text().matches("^\\[\\[[^\\n]*#\\^[^\\n]*]]$");
      if (!generatedCallout
          && !rootLink
          && (line.literal() && !line.text().isBlank() || countable(line.text()))) counted = true;
    }
    String raw = String.join("\n", visible.stream().map(MarkdownLines.Line::text).toList()).strip();
    return new View(List.copyOf(visible), raw, CrossReferences.clean(raw), counted);
  }
}
