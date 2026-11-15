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
  }

  public static void show(
      Ui ui,
      LocalDate date,
      Markdown document,
      Markdown template,
      boolean connected,
      Set<String> expanded,
      Actions actions) {
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
    for (DayOutline.Section section : DayOutline.sections(document, template))
      section(ui, ui.body, section, expanded, actions);
  }

  private static void section(
      Ui ui,
      LinearLayout parent,
      DayOutline.Section section,
      Set<String> expanded,
      Actions actions) {
    String heading = section.heading();
    LinearLayout card = ui.card(parent), header = ui.row(), content = ui.column();
    boolean open = expanded.contains(heading);
    boolean[] populated = {false};
    Runnable populate =
        () -> {
          if (populated[0]) return;
          populated[0] = true;
          for (var b : section.blocks())
            block(ui, content, b, CrossReferences.clean(b.text()), false, expanded, actions);
          for (var p : section.projections())
            block(ui, content, p.origin(), p.text(), true, expanded, actions);
          for (var child : section.children()) section(ui, content, child, expanded, actions);
          if (section.count() == 0 && section.children().isEmpty())
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
    Runnable toggle =
        () -> {
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

  private static void block(
      Ui ui,
      LinearLayout parent,
      Markdown.Block block,
      String text,
      boolean projection,
      Set<String> expanded,
      Actions actions) {
    String key = block.section().heading() + ":" + block.start() + ":" + projection;
    LinearLayout box = ui.column(), row = ui.row();
    boolean open = expanded.contains(key);
    TextView full =
        ui.text(text + (projection ? "\n↳ " + block.section().title() : ""), 16, Ui.INK);
    full.setTextIsSelectable(true);
    full.setVisibility(open ? View.VISIBLE : View.GONE);
    var expand =
        ui.icon(
            open ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right,
            "Развернуть текстовый блок",
            () -> {});
    expand.setOnClickListener(
        v -> {
          boolean visible = full.getVisibility() != View.VISIBLE;
          full.setVisibility(visible ? View.VISIBLE : View.GONE);
          if (visible) expanded.add(key);
          else expanded.remove(key);
          expand.setIcon(
              androidx.appcompat.content.res.AppCompatResources.getDrawable(
                  ui.activity,
                  visible ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right));
        });
    row.addView(expand, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    TextView preview =
        ui.text(
            CrossReferences.clean(text).lines().findFirst().orElse("Блок"),
            15,
            projection ? Ui.ACCENT : Ui.INK);
    preview.setMaxLines(2);
    preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
    preview.setMinHeight(ui.dp(48));
    preview.setGravity(android.view.Gravity.CENTER_VERTICAL);
    ui.weighted(row, preview);
    preview.setOnClickListener(v -> actions.edit(block));
    box.addView(row);
    box.addView(full);
    full.setOnClickListener(v -> actions.edit(block));
    LinearLayout.LayoutParams blockLayout = new LinearLayout.LayoutParams(-1, -2);
    blockLayout.bottomMargin = ui.dp(8);
    parent.addView(box, blockLayout);
  }
}
