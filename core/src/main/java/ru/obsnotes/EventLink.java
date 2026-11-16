package ru.obsnotes;

import java.util.UUID;

/** One independently editable parent reference; private key is stable across UI/recovery. */
public record EventLink(
    String key, String part, String path, String id, String label, String raw, RootEdit edit) {
  public record RootEdit(String path, String original, String replacement, String snapshot) {}

  public static EventLink local(String part) {
    return new EventLink(UUID.randomUUID().toString(), part, "", "", "", "", null);
  }

  public static EventLink external(Events.Leading link, RootEdit edit) {
    return new EventLink(
        UUID.randomUUID().toString(),
        "",
        link.parent().path(),
        link.parent().id(),
        link.label(),
        link.raw(),
        edit);
  }

  public boolean local() {
    return !part.isEmpty();
  }

  public String identity() {
    return local() ? "part:" + part : "file:" + path + "#^" + id;
  }

  public EventLink key(String value) {
    return new EventLink(value, part, path, id, label, raw, edit);
  }
}
