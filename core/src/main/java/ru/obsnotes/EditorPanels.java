package ru.obsnotes;

import java.util.*;

/** Visible metadata only: typing ordinary text does not invalidate every part's controls. */
public record EditorPanels(List<Part> parts, String error) {
  public record Mark(String kind, String ids, int line) {}

  public record Relation(String key, String label) {}

  public record Part(
      String id,
      List<Routing.Choice> routes,
      List<Mark> marks,
      List<Relation> relations,
      boolean root,
      boolean work,
      String valueLabel,
      String taskShell,
      Markdown.Kind kind,
      boolean taskAppend,
      boolean appendTaskText) {}

  public static EditorPanels of(EditorState owner) {
    List<Part> parts = new ArrayList<>();
    for (int i = 0; i < owner.parts.parts.size(); i++) {
      var form = owner.parts.parts.get(i).form();
      String text = form.editableText();
      var marks =
          form.tags().stream()
              .map(t -> new Mark(t.kind(), t.ids(), t.oneLine() ? t.line(text) : -1))
              .toList();
      var relations =
          owner.parts.references(i).stream()
              .map(r -> new Relation(r.key(), owner.parts.referenceLabel(r)))
              .toList();
      parts.add(
          new Part(
              form.partId,
              List.copyOf(form.routes),
              marks,
              relations,
              owner.parts.isRoot(i),
              WorkDaily.selected(form),
              form.valueLabel(),
              form.taskShell,
              form.kind,
              form.taskAppend,
              form.appendTaskText));
    }
    return new EditorPanels(List.copyOf(parts), owner.parts.error);
  }
}
