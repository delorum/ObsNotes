package ru.obsnotes.ui;

import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.*;

final class ChecklistTable {
  private ChecklistTable() {}

  static void add(Ui ui, LinearLayout content, DayScreen.Actions actions, Consumer<String> reveal) {
    LinearLayout table = ui.column();
    TextView loading = ui.text("Загрузка чеклиста…", 14, Ui.MUTED);
    table.addView(loading);
    var layout = new LinearLayout.LayoutParams(-1, -2);
    layout.leftMargin = layout.rightMargin = ui.dp(10);
    content.addView(table, layout);
    actions.checklist(
        rows -> {
          table.removeAllViews();
          LinearLayout header = ui.row();
          header.setGravity(android.view.Gravity.TOP);
          ui.weighted(header, ui.bold("Раздел", 14, Ui.INK));
          ui.weighted(header, ui.bold("Статус", 14, Ui.INK));
          table.addView(header);
          for (var row : rows) {
            LinearLayout cells = ui.row();
            cells.setGravity(android.view.Gravity.TOP);
            cells.setContentDescription("Строка чеклиста " + row.label());
            TextView label = ui.text(row.label(), 14, Ui.INK);
            label.setContentDescription("Раздел чеклиста " + row.label());
            cell(ui, cells, label);
            SpannableStringBuilder value = new SpannableStringBuilder();
            for (var part : row.status()) {
              if (value.length() > 0) value.append(" | ");
              int at = value.length();
              value.append(part.text());
              value.setSpan(
                  new ForegroundColorSpan(part.ok() ? Ui.GREEN : Ui.RED),
                  at,
                  value.length(),
                  Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView status = ui.text("", 14, Ui.INK);
            status.setText(value);
            status.setStateDescription(value.toString());
            status.setContentDescription("Статус чеклиста " + row.label());
            cell(ui, cells, status);
            if (!row.heading().isEmpty()) {
              cells.setOnClickListener(v -> reveal.accept(row.heading()));
              label.setOnClickListener(v -> reveal.accept(row.heading()));
              status.setOnClickListener(v -> reveal.accept(row.heading()));
            }
            table.addView(cells);
          }
        },
        error -> {
          loading.setText(error);
          loading.setTextColor(Ui.RED);
          if (loading.getParent() == null) table.addView(loading);
        });
  }

  private static void cell(Ui ui, LinearLayout row, TextView text) {
    text.setMinHeight(ui.dp(40));
    text.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
    row.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
  }
}
