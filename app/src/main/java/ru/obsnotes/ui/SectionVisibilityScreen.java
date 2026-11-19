package ru.obsnotes.ui;

import android.view.Gravity;
import android.widget.*;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.function.Consumer;
import ru.obsnotes.*;

/** Apply visibility immediately while preserving the search field, keyboard and scroll. */
public final class SectionVisibilityScreen {
  private SectionVisibilityScreen() {}

  public static void show(
      Ui ui,
      HiddenSections initial,
      Routing.Catalog catalog,
      Runnable back,
      Consumer<HiddenSections> changed) {
    ui.page("Видимость разделов", back);
    ui.body.addView(
        ui.text(
            "Включённые разделы видны на главном экране и в выборе. Скрытые — в начале списка.",
            14,
            Ui.MUTED));
    var search = ui.searchField(ui.body, "Поиск разделов", "");
    var rows = ui.column();
    ui.body.addView(rows);
    HiddenSections[] state = {initial};
    Runnable[] render = {null};
    render[0] =
        () -> {
          rows.removeAllViews();
          var entries =
              FuzzySearch.ranked(
                  state[0].entries(),
                  search.getText().toString(),
                  entry -> entry.title() + "\n" + catalog.sectionSearchLabel(entry.heading()));
          for (var entry : entries) {
            var row = ui.row();
            var label =
                ui.text(
                    entry.title() + (entry.inherited() ? " · скрыт родитель" : ""),
                    16,
                    entry.hidden() ? Ui.MUTED : Ui.INK);
            label.setMinHeight(ui.dp(48));
            label.setGravity(Gravity.CENTER_VERTICAL);
            ui.weighted(row, label);
            var toggle = new MaterialSwitch(ui.activity);
            toggle.setChecked(!entry.hidden());
            toggle.setContentDescription("Показывать раздел " + entry.title());
            row.addView(toggle, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)));
            Runnable apply =
                () -> {
                  int y = ui.scroll.getScrollY();
                  state[0] = state[0].show(entry.heading(), entry.hidden());
                  changed.accept(state[0]);
                  render[0].run();
                  ui.afterLayout(rows, () -> ui.scroll.scrollTo(0, y));
                };
            toggle.setOnClickListener(v -> apply.run());
            label.setOnClickListener(v -> apply.run());
            rows.addView(row);
          }
          if (entries.isEmpty()) rows.addView(ui.text("Разделы не найдены", 14, Ui.MUTED));
        };
    ui.watch(search, ignored -> render[0].run());
    render[0].run();
  }
}
