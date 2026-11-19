package ru.obsnotes;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Semantic editor contents, excluding cursor, widget state and generated paragraph identities. */
public final class EditorRevision {
  private EditorRevision() {}

  private static void add(StringBuilder key, Object value) {
    String text = String.valueOf(value);
    key.append(text.length()).append(':').append(text);
  }

  public static String contents(EditorState owner, Markdown template) {
    StringBuilder key = new StringBuilder();
    add(key, owner.fresh);
    add(key, owner.weatherForm);
    add(key, owner.taskAppend);
    add(key, owner.destinationPath);
    add(key, owner.editableText());
    List<EditorState> forms =
        owner.parts == null
            ? List.of(owner)
            : owner.parts.parts.stream().map(EditorParts.Part::form).toList();
    PartLinks links = owner.parts == null ? new PartLinks() : owner.parts.effectiveLinks();
    Map<String, Integer> indices = new HashMap<>();
    for (int i = 0; i < forms.size(); i++) indices.put(forms.get(i).partId, i);
    add(key, forms.size());
    for (EditorState form : forms) {
      add(key, form.destinationPath);
      add(key, form.kind);
      add(key, form.fieldPrefix);
      add(key, form.heading);
      if (form.kind != Markdown.Kind.SECTION) {
        add(key, form.targetLine);
        add(key, form.targetSource);
      }
      if (form.kind == Markdown.Kind.TASK) add(key, form.appendTaskText);
      add(key, form.editableText());
      var secrets = SecretText.scan(form.text);
      add(key, secrets.size());
      for (var secret : secrets) add(key, secret.raw());
      for (int i = 0; i < form.fieldCount(); i++) add(key, form.fieldName(i));
      add(key, form.fieldCount());
      var catalog = form.routingCatalog(template);
      List<String> tags = new ArrayList<>();
      Set<String> fullSections = new TreeSet<>();
      for (var tag : form.tags()) {
        String location =
            tag.oneLine()
                ? "@" + tag.line(form.editableText())
                : tag.person() ? "@" + tag.position() : "";
        for (var token : catalog.split(tag.ids())) {
          String id = token.text().toLowerCase(Locale.ROOT);
          if (tag.kind().equals("crossPeople") && token.choice() != null) {
            String heading = token.choice().target().heading();
            if (!heading.equals(form.heading)) fullSections.add(heading);
          } else tags.add(tag.kind() + location + "/" + id);
        }
      }
      Collections.sort(tags);
      add(key, tags.size());
      for (String tag : tags) add(key, tag);
      add(key, fullSections.size());
      for (String section : fullSections) add(key, section);
      List<EventLink.RootEdit> pending =
          owner.parts == null
              ? form.pendingRoots()
              : links.refs(form.partId).stream()
                  .map(EventLink::edit)
                  .filter(Objects::nonNull)
                  .distinct()
                  .toList();
      add(key, pending.size());
      for (var edit : pending) {
        add(key, edit.path());
        add(key, edit.original());
        add(key, edit.replacement());
      }
      add(key, Events.id(form.text));
      add(key, !Events.rootId(form.text).isEmpty() || links.rootIds().contains(form.partId));
      List<EventLink> refs =
          owner.parts == null
              ? form.parentLinks().stream().map(link -> EventLink.external(link, null)).toList()
              : links.refs(form.partId);
      add(key, refs.size());
      for (var ref : refs) {
        add(key, ref.local() ? "part:" + indices.get(ref.part()) : ref.path() + "#^" + ref.id());
      }
    }
    var removed =
        owner.removedRoots.stream().map(root -> root.path() + "#^" + root.id()).sorted().toList();
    add(key, removed.size());
    for (String root : removed) add(key, root);
    try {
      return "v4:"
          + Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256")
                      .digest(key.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static void remember(EditorState state, Markdown template) {
    state.initialRevision = contents(state, template);
  }

  /** Migrate old recovery records from their original source, never from the current edits. */
  public static void recoverBaseline(EditorState state, Markdown template) {
    if (state.initialRevision.startsWith("v4:")) return;
    try {
      Markdown source = new Markdown(state.sourceSnapshot);
      EditorState initial;
      if (state.weatherForm) initial = EditorState.weather(state.sourcePath, source);
      else if (!state.fresh)
        initial =
            EditorState.editing(state.sourcePath, source, template, state.originalBlock(source));
      else return;
      if (initial.weatherForm) remember(initial, template);
      else initial.enableParts(template);
      state.initialRevision = initial.initialRevision;
    } catch (IllegalArgumentException invalidOriginal) {
      // Existing conflict handling retains edits when an old snapshot cannot be reconstructed.
    }
  }

  public static boolean unchanged(EditorState state, Markdown template) {
    boolean taskOnly =
        state.parts == null
            ? state.taskOnly()
            : state.parts.parts.stream().anyMatch(p -> p.form().taskOnly());
    if (state.fresh && !taskOnly && state.emptyNote()) return true;
    recoverBaseline(state, template);
    return !state.initialRevision.isEmpty()
        && (state.parts == null || state.parts.error.isEmpty())
        && state.initialRevision.equals(contents(state, template));
  }
}
