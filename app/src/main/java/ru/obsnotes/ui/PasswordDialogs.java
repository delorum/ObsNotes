package ru.obsnotes.ui;

import android.app.Dialog;
import android.text.InputType;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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

  private static EditText field(Ui ui, LinearLayout body, String label, boolean visible) {
    EditText field = ui.field(body, label, "", false, null);
    field.setSaveEnabled(false);
    field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    field.setContentDescription(label);
    field.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (visible
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
    field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    if (visible) field.setTransformationMethod(null);
    ui.watch(
        field,
        value -> {
          if (field.getCompoundDrawablesRelative()[2] != null)
            field.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        });
    return field;
  }

  /** A dialog's onShow callback can run before its window can accept IME requests. */
  private static void focus(Ui ui, Dialog dialog, EditText field) {
    var window = dialog.getWindow();
    if (window != null) {
      window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
      window.setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
              | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    field.requestFocus();
    if (field.hasWindowFocus()) {
      ui.focusInput(field);
      return;
    }
    var listener =
        new ViewTreeObserver.OnWindowFocusChangeListener() {
          public void onWindowFocusChanged(boolean focused) {
            if (!focused) return;
            var observer = field.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
            if (dialog.isShowing() && field.hasFocus()) ui.focusInput(field);
          }
        };
    field.getViewTreeObserver().addOnWindowFocusChangeListener(listener);
    dialog.setOnDismissListener(
        d -> {
          var observer = field.getViewTreeObserver();
          if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(listener);
        });
  }

  private static LinearLayout body(Ui ui) {
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(24), 0, ui.dp(24), 0);
    body.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    return body;
  }

  public static void unlock(Ui ui, Consumer<String> submit) {
    LinearLayout body = body(ui);
    EditText password = field(ui, body, "Пинкод", false);
    password.setImeOptions(
        (password.getImeOptions() & ~EditorInfo.IME_MASK_ACTION) | EditorInfo.IME_ACTION_DONE);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Пинкод шифрования")
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
                    String value = password.getText().toString();
                    if (value.isEmpty()) {
                      mark(password);
                      return;
                    }
                    password.setText("");
                    dialog.dismiss();
                    submit.accept(value);
                  });
          password.setOnEditorActionListener(
              (v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                dialog.getButton(-1).performClick();
                return true;
              });
          focus(ui, dialog, password);
        });
    dialog.show();
  }

  /** 0 create, 1 change unlock password, 2 change encryption password, 3 delete. */
  public static void settings(Ui ui, int operation, BiConsumer<String, String> submit) {
    LinearLayout body = body(ui);
    String[] titles = {"Создать пароль", "Изменить пинкод", "Изменить пароль", "Удалить пароль"};
    EditText first = field(ui, body, operation == 0 ? "Пароль" : "Старый пинкод", operation == 0);
    EditText firstRepeat = operation == 0 ? field(ui, body, "Повторите пароль", true) : null;
    EditText second =
        operation == 3
            ? null
            : field(
                ui,
                body,
                operation == 0 ? "Пинкод" : operation == 1 ? "Новый пинкод" : "Новый пароль",
                true);
    EditText secondRepeat =
        operation == 3
            ? null
            : field(
                ui,
                body,
                operation == 0
                    ? "Повторите пинкод"
                    : operation == 1 ? "Повторите новый пинкод" : "Повторите новый пароль",
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
                    if (a.isEmpty()) {
                      mark(first);
                      return;
                    }
                    if (firstRepeat != null && !a.equals(firstRepeat.getText().toString())) {
                      mark(firstRepeat);
                      return;
                    }
                    if (second != null && b.isEmpty()) {
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
          focus(ui, dialog, first);
        });
    dialog.show();
  }
}
