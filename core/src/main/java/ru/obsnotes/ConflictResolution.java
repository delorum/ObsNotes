package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.util.*;

/** Explicit conflict choices always start from the current file, preserving external edits. */
public final class ConflictResolution {
  private ConflictResolution() {}

  public record Opened(String path, Markdown document) {}

  public static Opened resolve(
      VaultRepository repository,
      VaultStorage storage,
      EditorState state,
      Markdown template,
      boolean copy,
      LocalDateTime now)
      throws IOException {
    String text = copy ? state.conflictCopyText() : "";
    if (copy && text.isBlank()) throw new IllegalArgumentException("Нет текста для копии");
    // The explicit choice accepts any partial transfer as-is; WAL backups remain available.
    repository.keepCurrentFiles();
    String sourcePath =
        WorkDaily.isPath(state.sourcePath)
            ? DiaryLayout.path(WorkDaily.date(state.sourcePath))
            : state.sourcePath;
    Markdown latest;
    try {
      latest = new Markdown(storage.read(sourcePath));
    } catch (FileNotFoundException missing) {
      if (!copy) return new Opened(sourcePath, null);
      throw missing;
    }
    if (!copy) return new Opened(sourcePath, latest);
    String payload =
        (latest.timestamp(now) + text).replace("\r\n", "\n").replace("\n", latest.newline);
    var notes = latest.section("## Заметки");
    Markdown result;
    if (notes == null) result = latest.append("## Заметки", payload, template);
    else {
      String prefix = latest.text.substring(0, notes.end());
      String gap =
          prefix.endsWith(latest.newline + latest.newline)
              ? ""
              : prefix.endsWith(latest.newline) ? latest.newline : latest.newline + latest.newline;
      result =
          new Markdown(
              prefix
                  + gap
                  + payload
                  + latest.newline
                  + latest.newline
                  + latest.text.substring(notes.end()));
    }
    repository.commit(List.of(new VaultRepository.Change(sourcePath, latest.text, result.text)));
    return new Opened(sourcePath, result);
  }
}
