package ru.obsnotes.ui;

import android.app.Dialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.Consumer;

public final class ConflictDialog {
  private ConflictDialog() {}

  public static Dialog show(Ui ui, boolean canCopy, Consumer<Boolean> resolve) {
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Файл изменился извне")
            .setMessage(
                canCopy
                    ? "Можно сохранить копию текста отдельной записью в «Заметки» или отменить свою"
                          + " правку и открыть свежую версию. Внешние изменения сохранятся."
                    : "Можно отменить свою правку и открыть свежую версию. В редакторе нет текста"
                          + " для копии. Внешние изменения сохранятся.")
            .setNegativeButton("Отменить правку", (d, w) -> resolve.accept(false))
            .setNeutralButton("Остаться", null);
    if (canCopy) dialog.setPositiveButton("Сохранить копию", (d, w) -> resolve.accept(true));
    return dialog.show();
  }
}
