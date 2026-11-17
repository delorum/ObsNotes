package ru.obsnotes.ui;

import android.text.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.*;

public final class PasswordDialogs {
  private PasswordDialogs() {}

  private static void mark(EditText field) {
    var icon =
        androidx.appcompat.content.res.AppCompatResources.getDrawable(
                field.getContext(), ru.obsnotes.R.drawable.ic_error)
            .mutate();
    icon.setTint(Ui.RED);
    field.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null);
  }

  private static EditText field(
      Ui ui, LinearLayout body, String label, boolean pin, boolean visible) {
    EditText field = ui.field(body, label, "", false, null);
    field.setSaveEnabled(false);
    field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    field.setImeOptions(
        field.getImeOptions()
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    field.setContentDescription(label);
    ui.watch(
        field,
        value -> field.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null));
    field.setInputType(
        pin
            ? InputType.TYPE_CLASS_NUMBER | (visible ? 0 : InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            : InputType.TYPE_CLASS_TEXT
                | (visible
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
    if (visible) field.setTransformationMethod(null);
    if (pin) field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
    return field;
  }

  private static LinearLayout body(Ui ui) {
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(24), 0, ui.dp(24), 0);
    body.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    return body;
  }

  public static void pin(Ui ui, String title, Consumer<String> submit) {
    LinearLayout body = body(ui);
    EditText pin = field(ui, body, "PIN", true, false);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть", null)
            .create();
    dialog.setOnShowListener(
        x -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    if (!pin.getText().toString().matches("[0-9]{4}")) {
                      mark(pin);
                      return;
                    }
                    String value = pin.getText().toString();
                    pin.setText("");
                    dialog.dismiss();
                    submit.accept(value);
                  });
          ui.focusInput(pin);
        });
    dialog.show();
  }

  /** 0 create, 1 change PIN, 2 change password, 3 delete; all changes require the old PIN. */
  public static void settings(Ui ui, int operation, BiConsumer<String, String> submit) {
    LinearLayout body = body(ui);
    String[] titles = {"Создать пароль", "Изменить PIN", "Изменить пароль", "Удалить пароль"};
    EditText first =
        field(ui, body, operation == 0 ? "Пароль" : "Старый PIN", operation != 0, operation == 0);
    EditText firstRepeat = operation == 0 ? field(ui, body, "Повторите пароль", false, true) : null;
    EditText second =
        operation == 3
            ? null
            : field(
                ui,
                body,
                operation == 0 ? "PIN (4 цифры)" : operation == 1 ? "Новый PIN" : "Новый пароль",
                operation != 2,
                true);
    EditText secondRepeat =
        operation == 3
            ? null
            : field(
                ui,
                body,
                operation == 2
                    ? "Повторите новый пароль"
                    : operation == 1 ? "Повторите новый PIN" : "Повторите PIN",
                operation != 2,
                true);
    if (operation == 2)
      body.addView(ui.text("Смена пароля не перешифровывает прежние записи.", 12, Ui.MUTED));
    ScrollView scroll = new ScrollView(ui.activity);
    scroll.addView(body);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(titles[operation])
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(operation == 3 ? "Удалить" : "Сохранить", null)
            .create();
    dialog.setOnShowListener(
        x -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    String a = first.getText().toString(),
                        b = second == null ? "" : second.getText().toString();
                    if (a.isEmpty() || (operation != 0 && !a.matches("[0-9]{4}"))) {
                      mark(first);
                      return;
                    }
                    if (firstRepeat != null && !a.equals(firstRepeat.getText().toString())) {
                      mark(firstRepeat);
                      return;
                    }
                    if (second != null
                        && (b.isEmpty() || operation != 2 && !b.matches("[0-9]{4}"))) {
                      mark(second);
                      return;
                    }
                    if (secondRepeat != null && !b.equals(secondRepeat.getText().toString())) {
                      mark(secondRepeat);
                      return;
                    }
                    first.setText("");
                    if (firstRepeat != null) firstRepeat.setText("");
                    if (second != null) second.setText("");
                    if (secondRepeat != null) secondRepeat.setText("");
                    dialog.dismiss();
                    submit.accept(a, b);
                  });
          ui.focusInput(first);
        });
    dialog.show();
  }
}
