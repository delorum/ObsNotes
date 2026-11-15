package ru.obsnotes.ui;

import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.PastDays;

public final class PastDayPicker {
  private PastDayPicker() {}

  public interface Loader {
    void load(LocalDate from, Consumer<PastDays.Page> loaded, Consumer<String> failed);
  }

  public static void show(Ui ui, LocalDate start, Loader loader, Consumer<PastDays.Day> selected) {
    List<PastDays.Day> days = new ArrayList<>();
    ListView list = new ListView(ui.activity);
    list.setDivider(null);
    TextView footer = ui.text("Загрузка…", 14, Ui.MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setMinHeight(ui.dp(48));
    list.addFooterView(footer, null, false);
    BaseAdapter adapter =
        new BaseAdapter() {
          public int getCount() {
            return days.size();
          }

          public PastDays.Day getItem(int at) {
            return days.get(at);
          }

          public long getItemId(int at) {
            return at;
          }

          public View getView(int at, View old, ViewGroup parent) {
            var day = days.get(at);
            LinearLayout row = ui.row();
            row.setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(5));
            row.setMinimumHeight(ui.dp(56));
            TextView date =
                ui.bold(
                    day.date()
                        .format(DateTimeFormatter.ofPattern("dd.MM.yy · EEE", new Locale("ru"))),
                    14,
                    Ui.INK);
            row.addView(date);
            TextView description =
                ui.text(
                    day.description().isBlank() ? "Без краткого описания" : day.description(),
                    15,
                    Ui.MUTED);
            description.setMaxLines(2);
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ui.weighted(row, description);
            return row;
          }
        };
    list.setAdapter(adapter);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Перенести в другой день")
            .setView(list)
            .create();
    LocalDate[] next = {start};
    boolean[] loading = {false};
    boolean[] scrolling = {false};
    boolean[] autoLoad = {true};
    Runnable request =
        () -> {
          if (loading[0] || next[0] == null || !dialog.isShowing()) return;
          loading[0] = true;
          footer.setText("Загрузка…");
          loader.load(
              next[0],
              page -> {
                if (!dialog.isShowing()) return;
                loading[0] = false;
                next[0] = page.nextDate();
                autoLoad[0] = !page.days().isEmpty();
                days.addAll(page.days());
                adapter.notifyDataSetChanged();
                footer.setText(
                    next[0] == null
                        ? "Больше дней нет"
                        : page.days().isEmpty()
                            ? "За эти 14 дней заметок нет · Ещё 14 дней"
                            : "Ещё 14 дней");
              },
              error -> {
                if (!dialog.isShowing()) return;
                loading[0] = false;
                autoLoad[0] = false;
                footer.setText(error + " · Повторить");
              });
        };
    footer.setOnClickListener(
        v -> {
          autoLoad[0] = true;
          request.run();
        });
    list.setOnScrollListener(
        new AbsListView.OnScrollListener() {
          public void onScrollStateChanged(AbsListView view, int state) {
            scrolling[0] = state != SCROLL_STATE_IDLE;
          }

          public void onScroll(AbsListView view, int first, int visible, int total) {
            if (autoLoad[0] && scrolling[0] && total > 1 && first + visible >= total - 3)
              request.run();
          }
        });
    list.setOnItemClickListener(
        (parent, view, at, id) -> {
          if (at >= days.size()) return;
          var day = days.get(at);
          dialog.dismiss();
          selected.accept(day);
        });
    dialog.show();
    request.run();
  }
}
