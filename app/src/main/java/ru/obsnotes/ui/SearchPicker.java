package ru.obsnotes.ui;

import android.app.Dialog;
import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.*;

/** One searchable list for headings, tasks, dates, tags and events. */
public final class SearchPicker {
  private SearchPicker() {}

  public static <T> void show(
      Ui ui,
      String title,
      List<T> items,
      Function<T, String> label,
      boolean multi,
      Consumer<List<T>> select) {
    LinearLayout layout = ui.column();
    layout.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
    EditText search = ui.field(layout, "Поиск", "", false, null);
    ListView list = new ListView(ui.activity);
    list.setDivider(null);
    layout.addView(list, new LinearLayout.LayoutParams(-1, ui.dp(340)));
    LinkedHashSet<T> selected = new LinkedHashSet<>();
    List<T> filtered = new ArrayList<>();
    var builder = new MaterialAlertDialogBuilder(ui.activity).setTitle(title).setView(layout);
    if (multi)
      builder.setPositiveButton(
          "Вставить", (dialog, which) -> select.accept(List.copyOf(selected)));
    Dialog dialog = builder.create();
    Runnable filter =
        () -> {
          String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
          filtered.clear();
          for (T item : items)
            if (label.apply(item).toLowerCase(Locale.ROOT).contains(query)) filtered.add(item);
          list.setAdapter(
              new ArrayAdapter<>(
                  ui.activity,
                  multi
                      ? android.R.layout.simple_list_item_multiple_choice
                      : android.R.layout.simple_list_item_1,
                  filtered.stream().map(label).collect(java.util.stream.Collectors.toList())));
          if (multi) {
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            for (int i = 0; i < filtered.size(); i++)
              list.setItemChecked(i, selected.contains(filtered.get(i)));
          }
        };
    list.setOnItemClickListener(
        (parent, view, pos, id) -> {
          T item = filtered.get(pos);
          if (multi) {
            if (!selected.remove(item)) selected.add(item);
            list.setItemChecked(pos, selected.contains(item));
          } else {
            dialog.dismiss();
            select.accept(List.of(item));
          }
        });
    ui.watch(search, value -> filter.run());
    filter.run();
    dialog.show();
    if (dialog.getWindow() != null)
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }
}
