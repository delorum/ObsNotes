package ru.obsnotes.ui;

import android.view.View;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import ru.obsnotes.R;

public final class SettingsScreen {
  private SettingsScreen() {}

  public interface Actions {
    void back();

    void chooseVault();

    void draft();

    void deleteDraft();

    void recover();

    void keepFiles();
  }

  public static void show(
      Ui ui, String folder, boolean hasDraft, boolean hasRepository, Actions actions) {
    ui.page("Настройки", actions::back);
    ui.body.addView(
        ui.button(
            folder.isEmpty() ? "Выбрать хранилище" : "Изменить папку хранилища",
            actions::chooseVault));
    if (!folder.isEmpty()) ui.body.addView(ui.text(folder, 12, Ui.MUTED));
    ui.body.addView(
        ui.text(
            "Нужна корневая папка Obsidian с Дневник/Хроника и Шаблоны. Доступ сохраняется после"
                + " перезапуска телефона.",
            14,
            Ui.MUTED));
    ui.body.addView(
        ui.text(
            "ObsNotes "
                + ru.obsnotes.BuildConfig.VERSION_NAME
                + " · Things\n"
                + "Работает только с существующими днями. Синхронизацией занимается Syncthing.",
            14,
            Ui.MUTED));
    if (hasDraft) {
      LinearLayout row = ui.row();
      ui.weighted(row, ui.button("Открыть черновик", actions::draft));
      ui.action(
          row,
          R.drawable.ic_delete,
          "Удалить черновик",
          () ->
              new MaterialAlertDialogBuilder(ui.activity)
                  .setMessage("Удалить несохранённый черновик?")
                  .setPositiveButton("Удалить", (d, w) -> actions.deleteDraft())
                  .setNegativeButton("Оставить", null)
                  .show());
      ui.body.addView(row);
    }
    if (hasRepository) {
      LinearLayout recovery = ui.column();
      recovery.setVisibility(View.GONE);
      ui.body.addView(
          ui.button(
              "Восстановление",
              () ->
                  recovery.setVisibility(
                      recovery.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
      recovery.addView(ui.button("Продолжить прерванное сохранение", actions::recover));
      recovery.addView(
          ui.button(
              "Принять текущее состояние файлов",
              () ->
                  new MaterialAlertDialogBuilder(ui.activity)
                      .setMessage(
                          "Завершить журнал без изменения файлов? При прерванном переносе блок"
                              + " может остаться в обоих днях. Резервные копии и черновик"
                              + " сохранятся.")
                      .setPositiveButton("Принять", (d, w) -> actions.keepFiles())
                      .setNegativeButton("Отмена", null)
                      .show()));
      ui.body.addView(recovery);
    }
  }
}
