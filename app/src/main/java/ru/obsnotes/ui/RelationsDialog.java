package ru.obsnotes.ui;

import android.app.Dialog;
import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.FuzzySearch;
import ru.obsnotes.RelationSearch;

/** Results are usable while the optional archive back-link search continues. Dismiss cancels it. */
public final class RelationsDialog {
  private final Dialog dialog;
  private final EditText search;
  private final TextView status;
  private final ArrayAdapter<String> adapter;
  private List<RelationSearch.Related> items = List.of();
  private List<RelationSearch.Related> filtered = List.of();

  public RelationsDialog(Ui ui, Runnable cancelled, Consumer<RelationSearch.Related> selected) {
    LinearLayout layout = ui.column();
    layout.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
    search = ui.field(layout, "Поиск", "", false, null);
    ListView list = new ListView(ui.activity);
    list.setDivider(null);
    adapter =
        new ArrayAdapter<>(ui.activity, android.R.layout.simple_list_item_1, new ArrayList<>());
    list.setAdapter(adapter);
    int height =
        Math.max(
            100, Math.min(300, ui.activity.getResources().getConfiguration().screenHeightDp - 260));
    layout.addView(list, new LinearLayout.LayoutParams(-1, ui.dp(height)));
    status = ui.text("Поиск связей…", 12, Ui.MUTED);
    layout.addView(status);
    dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Связи")
            .setView(layout)
            .setNegativeButton("Закрыть", null)
            .create();
    dialog.setOnDismissListener(ignored -> cancelled.run());
    list.setOnItemClickListener(
        (parent, view, at, id) -> {
          var item = filtered.get(at);
          dialog.dismiss();
          selected.accept(item);
        });
    ui.watch(search, value -> filter());
    dialog.show();
    if (dialog.getWindow() != null)
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }

  private void filter() {
    String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
    filtered = FuzzySearch.ranked(items, query, RelationSearch.Related::label);
    adapter.setNotifyOnChange(false);
    adapter.clear();
    adapter.addAll(
        filtered.stream()
            .map(RelationSearch.Related::label)
            .collect(java.util.stream.Collectors.toList()));
    adapter.notifyDataSetChanged();
  }

  public void update(RelationSearch.Snapshot result) {
    if (!dialog.isShowing()) return;
    if (!items.equals(result.items())) {
      items = result.items();
      filter();
    }
    String message =
        result.complete()
            ? (items.isEmpty() ? "Связей нет" : "Найдено: " + items.size())
            : "Найдено: " + items.size() + " · Поиск в других днях: " + result.checked();
    if (result.unreadable() > 0) message += " · Недоступно файлов: " + result.unreadable();
    status.setText(message);
  }

  public void failed(String message) {
    if (dialog.isShowing()) status.setText(message);
  }

  public void dismiss() {
    dialog.dismiss();
  }
}
