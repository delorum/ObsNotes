package ru.obsnotes;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Private, per-vault hints. Statistics never participate in a note transaction. */
public final class UsageHistory {
  private static final Object LOCK = new Object();
  private final SharedPreferences prefs;

  public UsageHistory(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  private static String key(String vault) {
    return "section-usage-" + UUID.nameUUIDFromBytes(vault.getBytes(StandardCharsets.UTF_8));
  }

  public SectionUsage load(String vault) {
    Map<String, SectionUsage.Count> counts = new LinkedHashMap<>();
    try {
      JSONObject data = new JSONObject(prefs.getString(key(vault), "{}"));
      for (var keys = data.keys(); keys.hasNext(); ) {
        String heading = keys.next();
        JSONObject value = data.optJSONObject(heading);
        if (value != null)
          counts.put(
              heading,
              new SectionUsage.Count(value.optLong("primary", 0), value.optLong("additional", 0)));
      }
    } catch (JSONException malformed) {
      // A damaged hint file must not prevent selecting or saving notes.
    }
    return new SectionUsage(counts);
  }

  public void record(String vault, Collection<SectionUsage.Use> uses) {
    if (uses.isEmpty()) return;
    synchronized (LOCK) {
      SectionUsage next = load(vault).record(uses);
      JSONObject data = new JSONObject();
      try {
        for (var entry : next.snapshot().entrySet()) {
          JSONObject value = new JSONObject();
          value.put("primary", entry.getValue().primary());
          value.put("additional", entry.getValue().additional());
          data.put(entry.getKey(), value);
        }
        prefs.edit().putString(key(vault), data.toString()).apply();
      } catch (JSONException invalid) {
        // Ranking is optional; a successful note save must remain successful.
      }
    }
  }
}
