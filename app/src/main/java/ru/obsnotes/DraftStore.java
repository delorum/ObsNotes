package ru.obsnotes;

import android.content.SharedPreferences;
import org.json.*;

/**
 * App-private draft includes the original snapshots, so restarting cannot silently rebase edits.
 */
public final class DraftStore {
  private final SharedPreferences prefs;

  public DraftStore(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  public void save(EditorState s, String vault) {
    try {
      JSONObject j = new JSONObject();
      j.put("vault", vault);
      j.put("sourcePath", s.sourcePath);
      j.put("destinationPath", s.destinationPath);
      j.put("original", s.original);
      j.put("text", s.text);
      j.put("heading", s.heading);
      j.put("targetSource", s.targetSource);
      j.put("start", s.start);
      j.put("end", s.end);
      j.put("cursor", s.cursor);
      j.put("targetLine", s.targetLine);
      j.put("kind", s.kind.name());
      j.put("fresh", s.fresh);
      j.put("sourceSnapshot", s.sourceSnapshot);
      j.put("destinationSnapshot", s.destinationSnapshot);
      j.put("rootPath", s.rootPath);
      j.put("rootOriginal", s.rootOriginal);
      j.put("rootReplacement", s.rootReplacement);
      j.put("rootSnapshot", s.rootSnapshot);
      prefs.edit().putString(key(vault), j.toString()).apply();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public EditorState load(String vault) {
    String value = prefs.getString(key(vault), null);
    if (value == null) return null;
    try {
      JSONObject j = new JSONObject(value);
      if (!j.getString("vault").equals(vault)) return null;
      EditorState s = new EditorState();
      s.sourcePath = j.getString("sourcePath");
      s.destinationPath = j.getString("destinationPath");
      s.original = j.getString("original");
      s.text = j.getString("text");
      s.heading = j.getString("heading");
      s.targetSource = j.getString("targetSource");
      s.start = j.getInt("start");
      s.end = j.getInt("end");
      s.cursor = j.getInt("cursor");
      s.targetLine = j.getInt("targetLine");
      s.kind = Markdown.Kind.valueOf(j.getString("kind"));
      s.fresh = j.getBoolean("fresh");
      s.sourceSnapshot = j.getString("sourceSnapshot");
      s.destinationSnapshot = j.getString("destinationSnapshot");
      s.rootPath = j.optString("rootPath");
      s.rootOriginal = j.optString("rootOriginal");
      s.rootReplacement = j.optString("rootReplacement");
      s.rootSnapshot = j.optString("rootSnapshot");
      return s;
    } catch (JSONException | IllegalArgumentException e) {
      return null;
    }
  }

  private String key(String vault) {
    return "draft-"
        + java.util.UUID.nameUUIDFromBytes(vault.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public void clear(String vault) {
    prefs.edit().remove(key(vault)).apply();
  }
}
