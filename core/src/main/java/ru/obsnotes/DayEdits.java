package ru.obsnotes;

import java.util.*;

public final class DayEdits {
  private DayEdits() {}

  public static Markdown delete(Markdown source, Markdown.Block block) {
    return delete(source, block, CleanupRules.EMPTY);
  }

  public static Markdown delete(Markdown source, Markdown.Block block, CleanupRules rules) {
    return new Markdown(DiaryCleanup.compress(source.replace(block, "").text, rules));
  }

  public static List<VaultRepository.Change> change(String path, Markdown before, Markdown after) {
    return List.of(new VaultRepository.Change(path, before.text, after.text));
  }
}
