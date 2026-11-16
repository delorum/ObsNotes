package ru.obsnotes;

import android.content.SharedPreferences;
import org.json.*;

/** Internal recovery snapshot for interrupted or failed saves; no separate draft workflow. */
public final class EditorRecovery {
  private final SharedPreferences prefs;

  public EditorRecovery(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  public void save(EditorState s, String vault) {
    try {
      if (s.parts != null) s.parts.sync();
      JSONObject j = encode(s);
      j.put("vault", vault);
      prefs.edit().putString(key(vault), j.toString()).remove(legacyKey(vault)).apply();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public EditorState load(String vault) {
    String value = prefs.getString(key(vault), prefs.getString(legacyKey(vault), null));
    if (value == null) return null;
    try {
      JSONObject j = new JSONObject(value);
      if (!j.getString("vault").equals(vault)) return null;
      return decode(j);
    } catch (JSONException | IllegalArgumentException e) {
      return null;
    }
  }

  private JSONObject encode(EditorState s) throws JSONException {
    JSONObject j = new JSONObject();

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
    j.put("appendTaskText", s.appendTaskText);
    j.put("editFields", s.editFields);
    j.put("weatherForm", s.weatherForm);
    j.put("taskAppend", s.taskAppend);
    j.put("routesInitialized", s.routesInitialized);
    j.put("routeChosen", s.routeChosen);
    j.put("fallbackHeading", s.fallbackHeading);
    JSONArray routes = new JSONArray();
    for (var choice : s.routes) {
      var t = choice.target();
      JSONObject route = new JSONObject();
      route.put("label", t.label());
      route.put("heading", t.heading());
      route.put("line", t.line());
      route.put("source", t.source());
      route.put("kind", t.kind().name());
      route.put("crossId", choice.crossId());
      routes.put(route);
    }
    j.put("routes", routes);
    j.put("sourceSnapshot", s.sourceSnapshot);
    j.put("destinationSnapshot", s.destinationSnapshot);
    j.put("rootPath", s.rootPath);
    j.put("rootOriginal", s.rootOriginal);
    j.put("rootReplacement", s.rootReplacement);
    j.put("rootSnapshot", s.rootSnapshot);
    if (s.parts != null) {
      JSONObject split = new JSONObject();
      split.put("value", s.parts.value);
      split.put("baseline", s.parts.baseline);
      split.put("linked", s.parts.linked);
      split.put("selected", s.parts.selected);
      split.put("error", s.parts.error);
      JSONArray items = new JSONArray();
      for (var part : s.parts.parts) {
        JSONObject item = encode(part.form());
        item.put("partStart", part.start());
        item.put("partEnd", part.end());
        items.put(item);
      }
      split.put("items", items);
      j.put("parts", split);
    }
    return j;
  }

  private EditorState decode(JSONObject j) throws JSONException {
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
    s.appendTaskText = j.optBoolean("appendTaskText", true);
    s.editFields = j.optBoolean("editFields");
    s.weatherForm = j.optBoolean("weatherForm");
    s.taskAppend = j.optBoolean("taskAppend");
    s.routesInitialized = j.optBoolean("routesInitialized");
    s.routeChosen = j.optBoolean("routeChosen");
    s.fallbackHeading = j.optString("fallbackHeading");
    JSONArray routes = j.optJSONArray("routes");
    java.util.List<Routing.Choice> choices = new java.util.ArrayList<>();
    if (routes != null)
      for (int i = 0; i < routes.length(); i++) {
        JSONObject route = routes.getJSONObject(i);
        choices.add(
            new Routing.Choice(
                new Markdown.Target(
                    route.getString("label"),
                    route.getString("heading"),
                    route.getInt("line"),
                    route.getString("source"),
                    Markdown.Kind.valueOf(route.getString("kind"))),
                route.getString("crossId")));
      }
    s.routes = java.util.List.copyOf(choices);
    s.sourceSnapshot = j.getString("sourceSnapshot");
    s.destinationSnapshot = j.getString("destinationSnapshot");
    s.rootPath = j.optString("rootPath");
    s.rootOriginal = j.optString("rootOriginal");
    s.rootReplacement = j.optString("rootReplacement");
    s.rootSnapshot = j.optString("rootSnapshot");
    JSONObject split = j.optJSONObject("parts");
    if (split != null) {
      s.parts = new EditorParts(s);
      s.parts.parts.clear();
      JSONArray items = split.getJSONArray("items");
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        s.parts.parts.add(
            new EditorParts.Part(item.getInt("partStart"), item.getInt("partEnd"), decode(item)));
      }
      if (s.parts.parts.isEmpty()) throw new JSONException("Empty parts");
      s.parts.value = split.getString("value");
      s.parts.baseline = split.getString("baseline");
      s.parts.error = split.optString("error");
      s.parts.linked = split.optBoolean("linked");
      s.parts.selected = split.optInt("selected");
    }
    return s;
  }

  private String key(String vault) {
    return "editor-"
        + java.util.UUID.nameUUIDFromBytes(vault.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String legacyKey(String vault) {
    return key(vault).replaceFirst("^editor-", "draft-");
  }

  public void clear(String vault) {
    prefs.edit().remove(key(vault)).remove(legacyKey(vault)).apply();
  }
}
