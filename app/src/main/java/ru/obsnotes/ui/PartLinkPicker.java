package ru.obsnotes.ui;

import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import ru.obsnotes.*;

/** One tap changes the selected paragraph's parent; previews live only in this chooser. */
final class PartLinkPicker {
  private PartLinkPicker() {}

  static void show(Ui ui, EditorState owner, int index, Runnable external, Runnable changed) {
    List<String> labels = new ArrayList<>(List.of("Корень", "Без родителя"));
    List<Integer> targets = new ArrayList<>();
    for (int i = 0; i < owner.parts.parts.size(); i++)
      if (i != index) {
        String preview = owner.parts.parts.get(i).form().editableText().strip().replace('\n', ' ');
        if (preview.length() > 50) preview = preview.substring(0, 50) + "…";
        labels.add("К части " + (i + 1) + " · " + preview);
        targets.add(i);
      }
    labels.add("Из другой заметки…");
    String currentParent =
        owner.parts.effectiveLinks().parents.get(owner.parts.parts.get(index).form().partId);
    int selected = owner.parts.relationLabel(index).equals("Корень") ? 0 : 1;
    if (currentParent == null && !owner.parts.parts.get(index).form().rootLinkLabel().isEmpty())
      selected = labels.size() - 1;
    for (int i = 0; i < targets.size(); i++)
      if (owner.parts.parts.get(targets.get(i)).form().partId.equals(currentParent))
        selected = i + 2;
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle("Связь части " + (index + 1))
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
                if (which == 0) owner.parts.makeRoot(index);
                else if (which == 1) owner.parts.independent(index);
                else owner.parts.linkTo(index, targets.get(which - 2));
                dialog.dismiss();
                changed.run();
              } catch (IllegalArgumentException error) {
                Toast.makeText(ui.activity, error.getMessage(), Toast.LENGTH_LONG).show();
              }
            })
        .show();
  }
}
