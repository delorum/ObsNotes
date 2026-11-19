package ru.obsnotes.ui;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.*;

/** A month loads incrementally; stale callbacks cannot recolor another month's cells. */
public final class DayCalendarDialog {
  private DayCalendarDialog() {}

  private static final Locale RU = new Locale("ru");
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", RU);

  public interface Loader {
    Runnable load(
        YearMonth month,
        Set<LocalDate> known,
        Consumer<CalendarDays.Day> loaded,
        Consumer<String> failed);
  }

  public static Dialog show(
      Ui ui,
      LocalDate selected,
      CalendarDays.Day current,
      Loader loader,
      Consumer<LocalDate> choose) {
    LinearLayout content = ui.column();
    content.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
    content.setBackground(ui.background(Ui.CARD, 14));
    var dialog = new MaterialAlertDialogBuilder(ui.activity).setView(content).create();
    LinearLayout header = ui.row();
    content.addView(header);
    YearMonth[] month = {YearMonth.from(selected)};
    Map<LocalDate, CalendarDays.Day> cache = new HashMap<>();
    if (current != null) cache.put(current.date(), current);
    Runnable[] cancel = {() -> {}};
    Runnable[] render = {null};
    long[] generation = {0};
    DatePickerDialog[] jump = {null};
    var previous =
        ui.icon(
            ru.obsnotes.R.drawable.ic_left,
            "Предыдущий месяц",
            () -> {
              month[0] = month[0].minusMonths(1);
              render[0].run();
            });
    header.addView(previous, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    var title =
        ui.button(
            "",
            () -> {
              var shown = month[0];
              jump[0] =
                  new DatePickerDialog(
                      ui.activity,
                      (view, year, m, day) -> {
                        if (!dialog.isShowing()) return;
                        month[0] = YearMonth.of(year, m + 1);
                        render[0].run();
                      },
                      shown.getYear(),
                      shown.getMonthValue() - 1,
                      Math.min(selected.getDayOfMonth(), shown.lengthOfMonth()));
              jump[0].show();
            });
    title.setContentDescription("Выбрать месяц и год");
    title.setMaxLines(2);
    title.setTextSize(17);
    ui.weighted(header, title);
    var next =
        ui.icon(
            ru.obsnotes.R.drawable.ic_right,
            "Следующий месяц",
            () -> {
              month[0] = month[0].plusMonths(1);
              render[0].run();
            });
    header.addView(next, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    LinearLayout weekdays = gridRow(ui);
    for (String day : List.of("пн", "вт", "ср", "чт", "пт", "сб", "вс")) {
      TextView label = ui.text(day, 12, Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      weekdays.addView(label, new LinearLayout.LayoutParams(0, ui.dp(28), 1));
    }
    content.addView(weekdays);
    LinearLayout dates = ui.column();
    content.addView(dates);
    render[0] =
        () -> {
          cancel[0].run();
          long request = ++generation[0];
          YearMonth shown = month[0];
          String caption = shown.format(MONTH);
          title.setText(Character.toUpperCase(caption.charAt(0)) + caption.substring(1));
          previous.setEnabled(!shown.equals(YearMonth.of(1, 1)));
          next.setEnabled(!shown.equals(YearMonth.of(9999, 12)));
          previous.setIconTint(
              android.content.res.ColorStateList.valueOf(
                  previous.isEnabled() ? Ui.INK : Ui.DISABLED));
          next.setIconTint(
              android.content.res.ColorStateList.valueOf(next.isEnabled() ? Ui.INK : Ui.DISABLED));
          dates.removeAllViews();
          Map<LocalDate, TextView> cells = new HashMap<>();
          int offset = shown.atDay(1).getDayOfWeek().getValue() - 1;
          int count = ((offset + shown.lengthOfMonth() + 6) / 7) * 7;
          for (int index = 0; index < count; index += 7) {
            LinearLayout row = gridRow(ui);
            dates.addView(row);
            for (int column = 0; column < 7; column++) {
              int day = index + column - offset + 1;
              if (day < 1 || day > shown.lengthOfMonth()) {
                row.addView(new View(ui.activity), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
                continue;
              }
              LocalDate date = shown.atDay(day);
              TextView cell = ui.bold(String.valueOf(day), 17, Ui.DISABLED);
              cell.setGravity(Gravity.CENTER);
              cell.setPadding(0, 0, 0, 0);
              cell.setMinHeight(ui.dp(48));
              if (date.equals(selected)) {
                var border = ui.background(Color.TRANSPARENT, 14);
                border.setStroke(ui.dp(1), Ui.INK);
                cell.setBackground(border);
                cell.setSelected(true);
              }
              status(cell, date, cache.get(date));
              cell.setOnClickListener(
                  v -> {
                    var state = cache.get(date);
                    if (state == null || state.state() == CalendarDays.State.MISSING) return;
                    dialog.dismiss();
                    choose.accept(date);
                  });
              row.addView(cell, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
              cells.put(date, cell);
            }
          }
          Set<LocalDate> known = new HashSet<>(cache.keySet());
          // The displayed note is available immediately, then checked against the current file too.
          if (shown.equals(YearMonth.from(selected))) known.remove(selected);
          cancel[0] =
              loader.load(
                  shown,
                  Set.copyOf(known),
                  loaded -> {
                    if (!dialog.isShowing() || request != generation[0]) return;
                    cache.put(loaded.date(), loaded);
                    var cell = cells.get(loaded.date());
                    if (cell != null) status(cell, loaded.date(), loaded);
                  },
                  error -> {
                    if (dialog.isShowing() && request == generation[0])
                      Toast.makeText(ui.activity, error, Toast.LENGTH_SHORT).show();
                  });
        };
    dialog.setOnDismissListener(
        ignored -> {
          ++generation[0];
          cancel[0].run();
          if (jump[0] != null) jump[0].dismiss();
          cache.clear();
        });
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog
          .getWindow()
          .setLayout(
              ui.activity.getResources().getDisplayMetrics().widthPixels - ui.dp(16),
              ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    render[0].run();
    return dialog;
  }

  private static LinearLayout gridRow(Ui ui) {
    LinearLayout row = new LinearLayout(ui.activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setBaselineAligned(false);
    return row;
  }

  private static void status(TextView cell, LocalDate date, CalendarDays.Day day) {
    var state = day == null ? null : day.state();
    boolean exists = state != null && state != CalendarDays.State.MISSING;
    cell.setEnabled(exists);
    cell.setTextColor(
        state == CalendarDays.State.REVIEW
            ? Ui.RED
            : state == CalendarDays.State.COMPLETE ? Ui.INK : Ui.DISABLED);
    cell.setContentDescription(
        "Дата "
            + date
            + " · "
            + (state == null
                ? "Проверка"
                : state == CalendarDays.State.MISSING
                    ? "Заметки нет"
                    : state == CalendarDays.State.REVIEW ? "Нужно разобрать" : "Заполнено"));
  }
}
