package ru.obsnotes;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private per-vault presentation preferences; never written to Markdown. */
public final class SectionVisibilityPrefs {
  private final SharedPreferences prefs;

  public SectionVisibilityPrefs(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  private static String key(String vault) {
    return "hidden-sections-" + UUID.nameUUIDFromBytes(vault.getBytes(StandardCharsets.UTF_8));
  }

  public Set<String> load(String vault) {
    return Set.copyOf(prefs.getStringSet(key(vault), HiddenSections.DEFAULTS));
  }

  public void save(String vault, Collection<String> headings) {
    prefs.edit().putStringSet(key(vault), new HashSet<>(headings)).apply();
  }
}
