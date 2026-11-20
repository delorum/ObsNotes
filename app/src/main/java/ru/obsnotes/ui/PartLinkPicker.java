package ru.obsnotes.ui;

import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import ru.obsnotes.*;

/** Add a parent or replace exactly the tapped parent, keeping every other reference. */
final class PartLinkPicker {
  private PartLinkPicker() {}

  static void show(
      Ui ui, EditorState owner, int index, String replacing, Runnable external, Runnable changed) {
    List<String> labels = new ArrayList<>();
    List<Integer> targets = new ArrayList<>();
    var refs = owner.parts.references(index);
    EventLink current =
        refs.stream().filter(ref -> ref.key().equals(replacing)).findFirst().orElse(null);
    int selected = -1;
    for (int i = 0; i < owner.parts.parts.size(); i++)
      if (i != index && !WorkDaily.selected(owner.parts.parts.get(i).form())) {
        String id = owner.parts.parts.get(i).form().partId;
        if (refs.stream()
            .anyMatch(ref -> ref.local() && ref.part().equals(id) && !ref.key().equals(replacing)))
          continue;
        if (current != null && current.local() && current.part().equals(id))
          selected = labels.size();
        String preview = owner.parts.parts.get(i).form().editableText().strip().replace('\n', ' ');
        if (preview.length() > 50) preview = preview.substring(0, 50) + "…";
        labels.add("К части " + (i + 1) + " · " + preview);
        targets.add(i);
      }
    if (labels.isEmpty()) {
      external.run();
      return;
    }
    if (current != null && !current.local()) selected = labels.size();
    labels.add("Из другой заметки…");
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle(replacing == null ? "Добавить связь" : "Изменить связь")
        .setSingleChoiceItems(
            labels.toArray(String[]::new),
            selected,
            (dialog, which) -> {
              if (which == labels.size() - 1) {
                dialog.dismiss();
                external.run();
                return;
              }
              try {
                owner.parts.addInternal(index, targets.get(which), replacing);
                dialog.dismiss();
                changed.run();
              } catch (IllegalArgumentException error) {
                Toast.makeText(ui.activity, error.getMessage(), Toast.LENGTH_LONG).show();
              }
            })
        .show();
  }
}
