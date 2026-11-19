package ru.obsnotes.ui;

import android.view.View;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SettingsScreen {
  private SettingsScreen() {}

  public interface Actions {
    void back();

    void chooseVault();

    void recover();

    void keepFiles();

    void password(int operation);

    void sections();
  }

  public static void show(
      Ui ui,
      String folder,
      boolean hasRepository,
      boolean hasPassword,
      int hiddenCount,
      Actions actions) {
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
    if (!folder.isEmpty()) {
      ui.body.addView(ui.button("Скрытые разделы · " + hiddenCount, actions::sections));
      ui.body.addView(ui.bold("Шифрование", 16, Ui.INK));
      if (!hasPassword) ui.body.addView(ui.button("Создать пароль", () -> actions.password(0)));
      else {
        LinearLayout passwords = ui.row();
        ui.weighted(passwords, ui.button("Изменить PIN", () -> actions.password(1)));
        ui.weighted(passwords, ui.button("Изменить пароль", () -> actions.password(2)));
        ui.action(
            passwords,
            ru.obsnotes.R.drawable.ic_delete,
            "Удалить пароль",
            () -> actions.password(3));
        ui.body.addView(passwords);
      }
      ui.body.addView(
          ui.text(
              "Пароль хранится зашифрованным PIN-кодом. После ввода PIN замок работает 3 минуты без"
                  + " повторного запроса.",
              12,
              Ui.MUTED));
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
                              + " может остаться в обоих днях. Резервные копии и текст редактора"
                              + " сохранятся.")
                      .setPositiveButton("Принять", (d, w) -> actions.keepFiles())
                      .setNegativeButton("Отмена", null)
                      .show()));
      ui.body.addView(recovery);
    }
  }
}
