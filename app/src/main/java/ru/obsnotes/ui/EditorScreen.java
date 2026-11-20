package ru.obsnotes.ui;

import android.widget.*;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.*;
import ru.obsnotes.*;

public final class EditorScreen {
  private EditorScreen() {}

  public interface Actions {
    void part(int index);

    void back();

    void save();

    void changed();

    void sections();

    void routesChanged();

    void replaceRoute(int index);

    void valueTarget();

    void replaceTag(int index);

    void destination();

    void tags(boolean oneLine, boolean person, int cursor);

    void root();

    void link();

    void editLink(String key);

    void deleteRoot();

    void related();

    void secret();

    void weatherLocation();

    void fetchWeather();
  }

  public static void show(
      Ui ui, EditorState state, Markdown template, String weatherCity, Actions actions) {
    if (state.parts != null) {
      EditorPartsScreen.show(ui, state, template, weatherCity, actions);
      return;
    }
    String title =
        state.weatherForm
            ? "Погода"
            : state.taskAppend
                ? "Дополнение к задаче"
                : state.fieldCount() == 1
                    ? state.fieldName(0)
                    : state.fieldCount() > 1
                        ? "Поля"
                        : state.kind == Markdown.Kind.FIELD
                            ? state.routingCatalog(template).resolve(state.target()).label()
                            : state.fresh ? "Новая заметка" : "Текстовый блок";
    ui.page(title, actions::back);
    EditorState.InputCaret caret = state.inputCaret();
    ui.action(ui.bar, ru.obsnotes.R.drawable.ic_save, "Сохранить", actions::save);
    Routing.Catalog catalog = state.routingCatalog(template);
    ChipGroup metadata = new ChipGroup(ui.activity);
    metadata.setChipSpacingHorizontal(ui.dp(4));
    metadata.setChipSpacingVertical(0);
    List<EditText> edits = new ArrayList<>();
    if (state.fieldCount() > 0)
      for (int i = 0; i < state.fieldCount(); i++) {
        EditText field = ui.field(ui.body, state.fieldName(i), state.fieldValue(i), true, null);
        if (state.weatherForm) {
          field.setMinLines(1);
          field.setMaxLines(4);
        }
        edits.add(field);
      }
    else
      edits.add(
          ui.field(
              ui.body,
              state.taskAppend
                  ? "Дополнение"
                  : state.kind == Markdown.Kind.FIELD ? "Значение" : "Текст",
              state.editableText(),
              true,
              null));
    Runnable refresh =
        () -> {
          if (state.weatherForm) highlight(edits, state);
          else metadata(ui, state, catalog, metadata, actions, edits);
        };
    for (int i = 0; i < edits.size(); i++) {
      int index = i;
      EditText edit = edits.get(i);
      ui.watch(
          edit,
          value -> {
            if (state.fieldCount() > 0) state.editField(index, value);
            else state.edit(value);
            actions.changed();
            refresh.run();
          });
      edit.setOnFocusChangeListener(
          (v, focused) -> {
            state.cursor = offset(edits, index) + Math.max(0, edit.getSelectionStart());
          });
    }
    EditText active = edits.get(caret.field());
    active.setSelection(caret.offset());
    ui.focusInput(active);
    if (state.weatherForm) {
      refresh.run();
      weatherRow(ui, state, weatherCity, actions);
      return;
    }
    ui.body.addView(metadata);
    refresh.run();
    for (int i = 0; i < state.fieldCount(); i++)
      if (state.fieldName(i).equals("Погода") || state.fieldName(i).equals("Температура")) {
        weatherRow(ui, state, weatherCity, actions);
        break;
      }
    TextView hint = ui.text(hint(state), 12, Ui.MUTED);
    LinearLayout dates = ui.row();
    ui.weighted(
        dates,
        ui.button(
            RelativeDates.label(state.destinationPath, state.sourcePath), actions::destination));
    if (state.kind == Markdown.Kind.TASK && !state.taskAppend) {
      dates.addView(
          TaskTextMode.button(
              ui,
              state,
              () -> {
                actions.changed();
                hint.setText(hint(state));
              }));
    }
    ui.body.addView(dates);
    LinearLayout tags = ui.row();
    ui.weighted(
        tags,
        ui.button(
            "Разделы",
            () -> {
              state.cursor = cursor(edits, state.cursor);
              actions.sections();
            }));
    ui.weighted(
        tags,
        ui.button(
            "На строку",
            () -> {
              state.cursor = cursor(edits, state.cursor);
              actions.tags(true, false, state.cursor);
            }));
    ui.action(
        tags,
        ru.obsnotes.R.drawable.ic_person,
        "Тег человека для блока или строки",
        () -> {
          state.cursor = cursor(edits, state.cursor);
          new com.google.android.material.dialog.MaterialAlertDialogBuilder(ui.activity)
              .setItems(
                  new String[] {"Человек · весь блок", "Человек · текущая строка"},
                  (d, which) -> actions.tags(which == 1, true, state.cursor))
              .show();
        });
    ui.body.addView(tags);
    if (state.taskAppend) {
      ui.body.addView(hint);
      return;
    }
    LinearLayout relations = ui.row();
    ui.weighted(
        relations,
        ui.button(
            Events.rootId(state.text).isEmpty() ? "Сделать корневым" : "Корневой блок",
            actions::root));
    ui.weighted(relations, ui.button("Связать", actions::link));
    ui.action(relations, ru.obsnotes.R.drawable.ic_link, "Показать связи", actions::related);
    ui.body.addView(relations);
    ui.body.addView(hint);
  }

  private static String hint(EditorState state) {
    if (state.taskAppend)
      return "Дополнение сохранится при выходе, задача будет отмечена датой заметки.";
    return state.taskOnly()
        ? (state.fresh
            ? "Будет сохранена только отметка выполнения задачи."
            : "Задача будет отмечена без текста. Исходный блок уберётся из прежнего раздела.")
        : "Изменения сохраняются при выходе. Метки и связи — под текстом.";
  }

  static void weatherRow(Ui ui, EditorState state, String city, Actions actions) {
    LinearLayout row = ui.row();
    String name =
        WeatherSummary.LOCATIONS.stream()
            .filter(l -> l.code().equals(city))
            .map(WeatherSummary.Location::name)
            .findFirst()
            .orElse(city);
    ui.weighted(
        row,
        ui.button(
            name + " · " + DiaryLayout.fileName(state.destinationPath).substring(0, 10),
            actions::weatherLocation));
    ui.action(
        row, ru.obsnotes.R.drawable.ic_weather, "Добавить погоду nuipogoda", actions::fetchWeather);
    ui.body.addView(row);
  }

  private static int offset(List<EditText> edits, int index) {
    int at = 0;
    for (int i = 0; i < index; i++) at += edits.get(i).length() + 1;
    return at;
  }

  private static int cursor(List<EditText> edits, int fallback) {
    for (int i = 0; i < edits.size(); i++)
      if (edits.get(i).hasFocus())
        return offset(edits, i) + Math.max(0, edits.get(i).getSelectionStart());
    return fallback;
  }

  static void metadata(
      Ui ui,
      EditorState state,
      Routing.Catalog catalog,
      ChipGroup group,
      Actions actions,
      List<EditText> edits) {
    metadata(ui, state, catalog, group, actions, edits, 0, true);
  }

  static void metadata(
      Ui ui,
      EditorState state,
      Routing.Catalog catalog,
      ChipGroup group,
      Actions actions,
      List<EditText> edits,
      int colorOffset,
      boolean rootChip) {
    highlight(edits, state);
    group.removeAllViews();
    for (int i = 0; i < state.routes.size(); i++) {
      int index = i;
      var route = state.routes.get(i);
      Chip chip =
          chip(
              ui,
              route.target().kind() == Markdown.Kind.FIELD
                  ? route.label() + "::"
                  : route.target().kind() == Markdown.Kind.TASK
                      ? "Задача: " + route.label()
                      : route.label(),
              i == 0 ? Ui.INK : Ui.ACCENT);
      chip.setCloseIconContentDescription("Удалить раздел: " + route.label());
      chip.setOnClickListener(
          v -> {
            state.cursor = cursor(edits, state.cursor);
            actions.replaceRoute(index);
          });
      chip.setOnCloseIconClickListener(
          v -> {
            state.cursor = cursor(edits, state.cursor);
            state.removeRoute(index, catalog);
            actions.routesChanged();
          });
      group.addView(chip);
    }
    List<TaggedText.Tag> tags = state.tags();
    String clean = state.editableText();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < tags.size(); i++) order.add(i);
    order.sort(Comparator.comparingInt(i -> tags.get(i).oneLine() ? 1 : 0));
    for (int i : order) {
      int index = i;
      var tag = tags.get(i);
      String label = state.unmanagedCrossIds(tag, catalog);
      if (label.isEmpty()) continue;
      int color = Ui.ACCENT;
      for (var mark : LineMarks.of(clean, tags))
        if (tag.oneLine() && mark.line() == tag.line(clean))
          color = lineColor(colorOffset + mark.colorIndex());
      Chip chip = chip(ui, label, color);
      chip.setContentDescription(
          label + (tag.oneLine() ? ", строка " + tag.line(clean) : ", весь блок"));
      chip.setOnClickListener(
          v -> {
            state.cursor = cursor(edits, state.cursor);
            actions.replaceTag(index);
          });
      chip.setOnCloseIconClickListener(
          v -> {
            state.removeUnmanagedTag(index, catalog);
            actions.changed();
            metadata(ui, state, catalog, group, actions, edits, colorOffset, rootChip);
          });
      group.addView(chip);
    }
    if (rootChip) {
      var refs = state.parentLinks();
      for (int i = 0; i < refs.size(); i++) {
        int index = i;
        Chip chip = chip(ui, "К корню: " + refs.get(i).label(), Ui.ACCENT);
        chip.setContentDescription("Связь " + (i + 1));
        chip.setOnClickListener(v -> actions.editLink("raw:" + index));
        chip.setOnCloseIconClickListener(
            v -> {
              state.removeRootLink(index);
              actions.changed();
              metadata(ui, state, catalog, group, actions, edits, colorOffset, true);
            });
        group.addView(chip);
      }
      if (!Events.rootId(state.text).isEmpty()) {
        Chip root = chip(ui, "Корень", Ui.MUTED);
        root.setOnClickListener(v -> actions.related());
        root.setCloseIconContentDescription("Удалить корень");
        root.setOnCloseIconClickListener(v -> actions.deleteRoot());
        group.addView(root);
      }
    }
    group.setVisibility(
        group.getChildCount() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
  }

  static int lineColor(int index) {
    return LineColors.argb(index);
  }

  private static void highlight(List<EditText> edits, EditorState state) {
    for (int i = 0; i < edits.size(); i++) {
      android.text.Editable text = edits.get(i).getText();
      for (var span : text.getSpans(0, text.length(), android.text.style.ForegroundColorSpan.class))
        text.removeSpan(span);
      int offset = offset(edits, i);
      for (var mark : LineMarks.of(state.editableText(), state.tags())) {
        int start = Math.max(0, mark.start() - offset),
            end = Math.min(text.length(), mark.end() - offset);
        if (start < end)
          text.setSpan(
              new android.text.style.ForegroundColorSpan(lineColor(mark.colorIndex())),
              start,
              end,
              android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
  }

  static Chip chip(Ui ui, String label, int color) {
    Chip chip = new Chip(ui.activity);
    chip.setText(label);
    chip.setMaxWidth(ui.dp(310));
    chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
    chip.setTextColor(color);
    chip.setChipBackgroundColor(
        android.content.res.ColorStateList.valueOf(
            androidx.core.graphics.ColorUtils.blendARGB(Ui.CARD, color, .18f)));
    chip.setCloseIcon(
        androidx.appcompat.content.res.AppCompatResources.getDrawable(
            ui.activity, ru.obsnotes.R.drawable.ic_delete));
    chip.setCloseIconVisible(true);
    chip.setCloseIconTint(android.content.res.ColorStateList.valueOf(color));
    chip.setCloseIconContentDescription("Удалить метку " + label);
    chip.setEnsureMinTouchTargetSize(true);
    return chip;
  }
}
