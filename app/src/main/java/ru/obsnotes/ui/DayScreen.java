package ru.obsnotes.ui;

import android.view.View;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import ru.obsnotes.*;

public final class DayScreen {
  private DayScreen() {}

  public interface Actions {
    void settings();

    void pickVault();

    void navigate(int delta);

    void chooseDate();

    void today();

    void add();

    void edit(Markdown.Block block);

    void refresh();

    void delete(Markdown.Block block, boolean projection);

    void toggle(DiaryTasks.Task task);

    void weather();

    void searchChanged(String query);

    void checklist(Consumer<List<DailyChecklist.Row>> loaded, Consumer<String> failed);
  }

  public static void show(
      Ui ui,
      LocalDate date,
      Markdown document,
      Markdown template,
      boolean connected,
      Set<String> expanded,
      String query,
      Actions actions) {
    var previous = ui.activity.getCurrentFocus();
    boolean searchFocused = previous != null && "section-search".equals(previous.getTag());
    ui.page("ObsNotes", null);
    if (connected) ui.action(ui.bar, ru.obsnotes.R.drawable.ic_today, "Сегодня", actions::today);
    if (connected)
      ui.action(ui.bar, ru.obsnotes.R.drawable.ic_refresh, "Обновить", actions::refresh);
    ui.action(ui.bar, ru.obsnotes.R.drawable.ic_settings, "Настройки", actions::settings);
    if (!connected) {
      ui.body.addView(ui.text("Выберите папку хранилища в настройках", 16, Ui.MUTED));
      ui.body.addView(ui.button("Выбрать хранилище", actions::pickVault));
      return;
    }
    LinearLayout dates = ui.row();
    ui.action(dates, ru.obsnotes.R.drawable.ic_left, "Предыдущий день", () -> actions.navigate(-1));
    TextView day =
        ui.bold(
            date.format(DateTimeFormatter.ofPattern("d MMMM yyyy · EEE", new Locale("ru"))),
            17,
            Ui.INK);
    day.setGravity(android.view.Gravity.CENTER);
    day.setMinHeight(ui.dp(48));
    day.setMaxLines(2);
    day.setOnClickListener(v -> actions.chooseDate());
    day.setOnLongClickListener(
        v -> {
          actions.today();
          return true;
        });
    day.setContentDescription("Выбрать дату. Удерживайте, чтобы открыть сегодня");
    ui.weighted(dates, day);
    ui.action(dates, ru.obsnotes.R.drawable.ic_right, "Следующий день", () -> actions.navigate(1));
    LinearLayout.LayoutParams dateLayout = new LinearLayout.LayoutParams(-1, -2);
    dateLayout.bottomMargin = ui.dp(8);
    ui.body.addView(dates, dateLayout);
    if (document == null) {
      ui.error(DayNavigation.NOT_FOUND);
      return;
    }
    var outline = DayOutline.sections(document, template);
    var catalog = new Routing.Catalog(document, template);
    EditText search = ui.field(ui.body, "Поиск разделов", query, false, null);
    search.setTag("section-search");
    LinearLayout results = ui.column();
    ui.body.addView(results);
    Map<String, android.view.View> headers = new HashMap<>();
    Runnable[] render = {null};
    Consumer<String> reveal =
        heading -> {
          expanded.addAll(DayOutline.ancestors(outline, heading));
          ui.hideKeyboard();
          if (!search.getText().toString().isEmpty()) search.setText("");
          else render[0].run();
          ui.scroll.post(
              () -> {
                var target = headers.get(heading);
                if (target != null) {
                  int y = 0;
                  android.view.View v = target;
                  while (v != ui.body && v.getParent() instanceof android.view.View) {
                    y += v.getTop();
                    v = (android.view.View) v.getParent();
                  }
                  ui.scroll.smoothScrollTo(0, y);
                }
              });
        };
    render[0] =
        () -> {
          results.removeAllViews();
          headers.clear();
          for (var section : DayOutline.search(outline, catalog, search.getText().toString()))
            section(ui, results, section, expanded, actions, headers, reveal);
          if (results.getChildCount() == 0)
            results.addView(ui.text("Разделы не найдены", 14, Ui.MUTED));
        };
    ui.watch(
        search,
        value -> {
          actions.searchChanged(value);
          render[0].run();
        });
    render[0].run();
    if (searchFocused) {
      search.requestFocus();
      search.setSelection(search.length());
    }
  }

  private static void section(
      Ui ui,
      LinearLayout parent,
      DayOutline.Section section,
      Set<String> expanded,
      Actions actions,
      Map<String, android.view.View> headers,
      Consumer<String> reveal) {
    String heading = section.heading();
    LinearLayout card = ui.card(parent), header = ui.row(), content = ui.column();
    headers.put(heading, card);
    boolean open = expanded.contains(heading);
    boolean[] populated = {false};
    Runnable populate =
        () -> {
          if (populated[0]) return;
          populated[0] = true;
          if (heading.equals("## Чеклист")) ChecklistTable.add(ui, content, actions, reveal);
          for (var b : section.blocks())
            DayBlockView.add(
                ui, content, b, CrossReferences.clean(b.text()), false, expanded, actions);
          for (var p : section.projections())
            DayBlockView.add(ui, content, p.origin(), p.text(), true, expanded, actions);
          for (var child : section.children())
            section(ui, content, child, expanded, actions, headers, reveal);
          if (section.count() == 0 && section.children().isEmpty() && !heading.equals("## Чеклист"))
            content.addView(ui.text("Пусто", 14, Ui.MUTED));
        };
    var disclosure =
        ui.icon(
            open ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right,
            "Развернуть раздел " + section.title(),
            () -> {});
    header.addView(disclosure, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    ui.weighted(
        header,
        ui.bold(
            section.title() + " · " + section.count(),
            16,
            section.count() == 0 && heading.equals("## Заметки") ? Ui.GREEN : Ui.INK));
    if (heading.equals("## Заметки"))
      ui.action(header, ru.obsnotes.R.drawable.ic_add, "Новая заметка", actions::add);
    if (heading.equals("## Погода"))
      ui.action(
          header, ru.obsnotes.R.drawable.ic_weather, "Погода и температура", actions::weather);
    Runnable toggle =
        () -> {
          ui.hideKeyboard();
          boolean visible = content.getVisibility() != View.VISIBLE;
          if (visible) {
            populate.run();
            expanded.add(heading);
          } else expanded.remove(heading);
          content.setVisibility(visible ? View.VISIBLE : View.GONE);
          disclosure.setIcon(
              androidx.appcompat.content.res.AppCompatResources.getDrawable(
                  ui.activity,
                  visible ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right));
        };
    header.setOnClickListener(v -> toggle.run());
    disclosure.setOnClickListener(v -> toggle.run());
    card.addView(header);
    LinearLayout.LayoutParams contentLayout = new LinearLayout.LayoutParams(-1, -2);
    contentLayout.topMargin = ui.dp(8);
    card.addView(content, contentLayout);
    content.setVisibility(open ? View.VISIBLE : View.GONE);
    if (open) populate.run();
  }
}
