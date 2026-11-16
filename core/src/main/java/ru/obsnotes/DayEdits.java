package ru.obsnotes;

import java.util.*;

public final class DayEdits {
  private DayEdits() {}

  public static Markdown delete(Markdown source, Markdown.Block block) {
    return new Markdown(DiaryCleanup.compress(source.replace(block, "").text));
  }

  public static List<VaultRepository.Change> change(String path, Markdown before, Markdown after) {
    return List.of(new VaultRepository.Change(path, before.text, after.text));
  }
}
