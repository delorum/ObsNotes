package ru.obsnotes.ui;

import android.app.Dialog;
import android.view.*;
import android.widget.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.*;
import ru.obsnotes.*;

/**
 * Label tap applies immediately; checkboxes stage ordered multi-selection. Apply stays above IME.
 */
public final class SearchPicker {
  private SearchPicker() {}

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      Consumer<List<T>> select) {
    show(ui, title, items, label, multi, List.of(), ignored -> null, select);
  }

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      List<T> initial,
      Function<List<T>, String> validate,
      Consumer<List<T>> select) {
    show(ui, title, items, label, multi, initial, validate, false, select);
  }

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      List<T> initial,
      Function<List<T>, String> validate,
      boolean replaceOnLabel,
      Consumer<List<T>> select) {
    show(ui, title, items, label, multi, initial, validate, replaceOnLabel, label, select);
  }

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      List<T> initial,
      Function<List<T>, String> validate,
      Function<T, String> searchLabel,
      Consumer<List<T>> select) {
    show(ui, title, items, label, multi, initial, validate, false, searchLabel, select);
  }

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      List<T> initial,
      Function<List<T>, String> validate,
      boolean replaceOnLabel,
      Function<T, String> searchLabel,
      Consumer<List<T>> select) {
    LinearLayout layout = ui.column();
    layout.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
    PickerSelection<T> selection = new PickerSelection<>(initial);
    Dialog dialog = new MaterialAlertDialogBuilder(ui.activity).setView(layout).create();
    Consumer<List<T>> apply =
        chosen -> {
          String error = validate.apply(chosen);
          if (error != null) {
            Toast.makeText(ui.activity, error, Toast.LENGTH_SHORT).show();
            return;
          }
          dialog.dismiss();
          select.accept(chosen);
        };
    if (multi) {
      LinearLayout header = ui.row();
      TextView caption = ui.bold(title, 20, Ui.INK);
      caption.setMaxLines(2);
      ui.weighted(header, caption);
      ui.action(
          header,
          ru.obsnotes.R.drawable.ic_save,
          "Применить выбранное",
          () -> apply.accept(selection.selected()));
      layout.addView(header);
    } else dialog.setTitle(title);
    EditText search = ui.searchField(layout, "Поиск", "");
    ListView list = new ListView(ui.activity);
    list.setDivider(null);
    int height =
        Math.max(
            100, Math.min(340, ui.activity.getResources().getConfiguration().screenHeightDp - 230));
    layout.addView(list, new LinearLayout.LayoutParams(-1, ui.dp(height)));
    List<T> filtered = new ArrayList<>();
    BaseAdapter adapter =
        new BaseAdapter() {
          public int getCount() {
            return filtered.size();
          }

          public T getItem(int at) {
            return filtered.get(at);
          }

          public long getItemId(int at) {
            return at;
          }

          public View getView(int at, View old, ViewGroup parent) {
            T item = filtered.get(at);
            LinearLayout row = ui.row();
            row.setMinimumHeight(ui.dp(48));
            TextView text = ui.text(label.apply(item), 16, Ui.INK);
            text.setMinHeight(ui.dp(48));
            text.setGravity(Gravity.CENTER_VERTICAL);
            ui.weighted(row, text);
            Runnable quick =
                () -> {
                  apply.accept(selection.quick(item, !multi || replaceOnLabel));
                };
            row.setOnClickListener(v -> quick.run());
            text.setOnClickListener(v -> quick.run());
            if (multi) {
              MaterialCheckBox check = new MaterialCheckBox(ui.activity);
              check.setChecked(selection.contains(item));
              check.setContentDescription("Выбрать: " + label.apply(item));
              check.setOnClickListener(
                  v -> {
                    List<T> chosen = new ArrayList<>(selection.selected());
                    if (!chosen.remove(item)) chosen.add(item);
                    String error = validate.apply(chosen);
                    if (error != null) {
                      check.setChecked(selection.contains(item));
                      Toast.makeText(ui.activity, error, Toast.LENGTH_SHORT).show();
                      return;
                    }
                    selection.set(chosen);
                  });
              row.addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            } else if (!initial.isEmpty()) {
              var radio =
                  new com.google.android.material.radiobutton.MaterialRadioButton(ui.activity);
              radio.setChecked(selection.contains(item));
              radio.setContentDescription("Выбрать: " + label.apply(item));
              radio.setOnClickListener(v -> quick.run());
              row.addView(radio, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            }
            return row;
          }
        };
    list.setAdapter(adapter);
    Runnable filter =
        () -> {
          filtered.clear();
          String query = search.getText().toString();
          filtered.addAll(FuzzySearch.ranked(items, query, searchLabel));
          adapter.notifyDataSetChanged();
        };
    ui.watch(search, value -> filter.run());
    filter.run();
    dialog.show();
    if (dialog.getWindow() != null)
      dialog
          .getWindow()
          .setSoftInputMode(
              WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                  | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    ui.focusInput(search);
  }
}
