package ru.obsnotes.ui;

import android.util.TypedValue;
import android.widget.LinearLayout;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import ru.obsnotes.EditorState;
import ru.obsnotes.R;
import ru.obsnotes.RelativeDates;

final class EditorDateRow {
  final LinearLayout view;
  private final MaterialButton date;
  private String path;

  EditorDateRow(Ui ui, EditorState state, EditorScreen.Actions actions) {
    var row = view = ui.row();
    var layout = new LinearLayout.LayoutParams(-1, -2);
    layout.bottomMargin = ui.dp(8);
    row.setLayoutParams(layout);
    ui.action(row, R.drawable.ic_double_left, "На два дня назад", () -> actions.destination(-2));
    ui.action(row, R.drawable.ic_left, "На день назад", () -> actions.destination(-1));
    date = ui.button("", actions::destination);
    date.setContentDescription("Дата выбранного блока");
    update(state);
    date.setSingleLine(true);
    date.setPadding(0, 0, 0, 0);
    date.setInsetTop(0);
    date.setInsetBottom(0);
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
        date, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
    row.addView(date, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
    ui.action(row, R.drawable.ic_right, "На день вперёд", () -> actions.destination(1));
    ui.action(row, R.drawable.ic_double_right, "На два дня вперёд", () -> actions.destination(2));
  }

  void update(EditorState state) {
    if (state.destinationPath.equals(path)) return;
    path = state.destinationPath;
    date.setText(RelativeDates.buttonLabel(path));
  }
}
