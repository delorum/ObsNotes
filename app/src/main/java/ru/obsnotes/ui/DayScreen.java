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

    void draft();

    void refresh();
  }

  public static void show(
      Ui ui,
      LocalDate date,
      Markdown document,
      Markdown template,
      boolean connected,
      boolean draft,
      Set<String> expanded,
      Actions actions) {
    ui.page("ObsNotes", null);
    if (draft)
      ui.action(ui.bar, ru.obsnotes.R.drawable.ic_draft, "Открыть черновик", actions::draft);
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
    day.setOnClickListener(v -> actions.chooseDate());
    day.setOnLongClickListener(
        v -> {
          actions.today();
          return true;
        });
    day.setContentDescription("Выбрать дату. Удерживайте, чтобы открыть сегодня");
    ui.weighted(dates, day);
    ui.action(dates, ru.obsnotes.R.drawable.ic_right, "Следующий день", () -> actions.navigate(1));
    ui.body.addView(dates);
    if (document == null) {
      ui.body.addView(ui.text("За этот день заметка ещё не создана", 16, Ui.MUTED));
      return;
    }
    LinkedHashMap<String, String> sections = new LinkedHashMap<>();
    sections.put("## Заметки", "Заметки");
    for (Markdown.Section s : template.sections) sections.put(s.heading(), s.title());
    for (Markdown.Section s : document.sections) sections.putIfAbsent(s.heading(), s.title());
    for (var section : sections.entrySet()) {
      String heading = section.getKey();
      List<Markdown.Block> blocks =
          document.blocks.stream()
              .filter(b -> b.section().heading().equals(heading))
              .collect(java.util.stream.Collectors.toList());
      List<CrossReferences.Projection> projected =
          CrossReferences.project(document, template, heading);
      LinearLayout card = ui.card(ui.body), header = ui.row(), content = ui.column();
      boolean open = expanded.contains(heading);
      var disclosure =
          ui.icon(
              open ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right,
              "Развернуть раздел " + section.getValue(),
              () -> {});
      header.addView(disclosure, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
      TextView title =
          ui.bold(
              section.getValue() + " · " + (blocks.size() + projected.size()),
              16,
              blocks.isEmpty() && heading.equals("## Заметки") ? Ui.GREEN : Ui.INK);
      ui.weighted(header, title);
      if (heading.equals("## Заметки"))
        ui.action(header, ru.obsnotes.R.drawable.ic_add, "Новая заметка", actions::add);
      Runnable toggle =
          () -> {
            boolean visible = content.getVisibility() != View.VISIBLE;
            content.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) expanded.add(heading);
            else expanded.remove(heading);
            disclosure.setIcon(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    ui.activity,
                    visible ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right));
          };
      header.setOnClickListener(v -> toggle.run());
      disclosure.setOnClickListener(v -> toggle.run());
      card.addView(header);
      card.addView(content);
      content.setVisibility(open ? View.VISIBLE : View.GONE);
      for (Markdown.Block b : blocks)
        block(ui, content, b, CrossReferences.clean(b.text()), false, expanded, actions);
      for (var p : projected) block(ui, content, p.origin(), p.text(), true, expanded, actions);
      if (blocks.isEmpty() && projected.isEmpty()) content.addView(ui.text("Пусто", 14, Ui.MUTED));
    }
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
    parent.addView(box);
  }
}
