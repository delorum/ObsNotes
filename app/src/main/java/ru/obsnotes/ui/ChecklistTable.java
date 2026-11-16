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
    content.addView(table);
    actions.checklist(
        rows -> {
          table.removeAllViews();
          LinearLayout header = ui.row();
          ui.weighted(header, ui.bold("Раздел", 14, Ui.INK));
          ui.weighted(header, ui.bold("Статус", 14, Ui.INK));
          table.addView(header);
          for (var row : rows) {
            LinearLayout cells = ui.row();
            TextView label = ui.text(row.label(), 14, Ui.INK);
            label.setMinHeight(ui.dp(40));
            ui.weighted(cells, label);
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
            ui.weighted(cells, status);
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
}
