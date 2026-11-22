package ru.obsnotes.ui;

import android.view.Gravity;
import android.widget.LinearLayout;
import com.google.android.material.button.MaterialButton;
import java.util.*;
import ru.obsnotes.*;

/** Updates selection affordances in place; a gesture never rebuilds the day or its search index. */
final class SelectionControls {
  private final Ui ui;
  private final BlockSelection selection;
  private final DayScreen.Actions actions;
  private final boolean connected;
  private final List<Runnable> rows = new ArrayList<>();
  private boolean active;
  private MaterialButton count;

  SelectionControls(Ui ui, BlockSelection selection, DayScreen.Actions actions, boolean connected) {
    this.ui = ui;
    this.selection = selection;
    this.actions = actions;
    this.connected = connected;
    toolbar();
  }

  boolean active() {
    return selection.size() > 0;
  }

  void watch(Runnable update) {
    rows.add(update);
    update.run();
  }

  void clearRows() {
    rows.clear();
  }

  void toggle(DayContentRules.Item item) {
    actions.select(item);
    refresh();
  }

  private void refresh() {
    if (active != active()) toolbar();
    else if (count != null) count.setText(Integer.toString(selection.size()));
    for (var row : rows) row.run();
  }

  private void toolbar() {
    active = active();
    // The title is the first child; only replace the actions when entering/leaving selection mode.
    ui.bar.removeViews(1, ui.bar.getChildCount() - 1);
    count = null;
    if (active) {
      count = ui.button(Integer.toString(selection.size()), actions::editSelection);
      count.setContentDescription("Редактировать выбранные записи");
      count.setGravity(Gravity.CENTER);
      count.setPadding(0, 0, 0, 0);
      count.setInsetTop(0);
      count.setInsetBottom(0);
      ui.bar.addView(count, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
      ui.action(
          ui.bar,
          ru.obsnotes.R.drawable.ic_clear,
          "Сбросить выбор",
          () -> {
            actions.selectionReset();
            refresh();
          });
    } else {
      if (connected) ui.action(ui.bar, ru.obsnotes.R.drawable.ic_today, "Сегодня", actions::today);
      if (connected)
        ui.action(ui.bar, ru.obsnotes.R.drawable.ic_refresh, "Обновить", actions::refresh);
      ui.action(ui.bar, ru.obsnotes.R.drawable.ic_settings, "Настройки", actions::settings);
    }
  }
}
