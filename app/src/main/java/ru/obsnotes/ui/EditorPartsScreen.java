package ru.obsnotes.ui;

import android.widget.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.*;
import ru.obsnotes.*;

/** Simultaneous paragraph controls; the text selection never chooses a routing panel. */
final class EditorPartsScreen {
  private EditorPartsScreen() {}

  /** One text field, with every paragraph's routing controls visible at the same time. */
  static void show(Ui ui, EditorState owner, Markdown template, EditorScreen.Actions actions) {
    ui.page(owner.fresh ? "Новая заметка" : "Текстовый блок", actions::back);
    ui.action(ui.bar, ru.obsnotes.R.drawable.ic_today, "Сегодня", actions::today);
    ui.action(ui.bar, ru.obsnotes.R.drawable.ic_save, "Сохранить", actions::save);
    EditText input = ui.field(ui.body, "Текст", owner.editableText(), true, null);
    LinearLayout panels = ui.column();
    ui.body.addView(panels);
    Runnable[] render = new Runnable[1];
    Runnable refresh =
        () -> {
          panels.removeAllViews();
          highlightParts(input, owner);
          if (!owner.parts.error.isEmpty()) panels.addView(ui.text(owner.parts.error, 12, Ui.RED));
          for (int i = 0; i < owner.parts.parts.size(); i++) {
            int index = i;
            var part = owner.parts.parts.get(i);
            EditorState form = part.form();
            var catalog = form.routingCatalog(template);
            Runnable select =
                () -> {
                  owner.cursor = Math.max(0, input.getSelectionStart());
                  form.cursor =
                      Math.max(
                          0, Math.min(form.editableText().length(), owner.cursor - part.start()));
                  actions.part(index);
                };
            EditorScreen.Actions scoped = new PartActions(actions, select);
            LinearLayout card = ui.card(panels);
            LinearLayout row = ui.row();
            String preview = form.editableText().strip().replace('\n', ' ');
            if (preview.length() > 36) preview = preview.substring(0, 36) + "…";
            TextView label = ui.text((index + 1) + " · " + preview, 13, Ui.MUTED);
            label.setMaxLines(2);
            ui.weighted(row, label);
            var choose = ui.button("Разделы", scoped::sections);
            choose.setContentDescription("Разделы части " + (index + 1));
            row.addView(choose);
            card.addView(row);
            ChipGroup chips = new ChipGroup(ui.activity);
            chips.setChipSpacingHorizontal(ui.dp(4));
            chips.setChipSpacingVertical(0);
            card.addView(chips);
            EditorScreen.metadata(ui, form, catalog, chips, scoped, List.of());
            if (owner.parts.linked && owner.parts.parts.size() > 1) {
              Chip relation = EditorScreen.chip(ui, index == 0 ? "Корень" : "К части 1", Ui.MUTED);
              relation.setCloseIconVisible(false);
              chips.addView(relation);
            } else if (!Events.rootId(form.text).isEmpty()) {
              Chip relation = EditorScreen.chip(ui, "Корень", Ui.MUTED);
              relation.setCloseIconVisible(false);
              relation.setOnClickListener(v -> scoped.related());
              chips.addView(relation);
            }
            if (form.kind == Markdown.Kind.TASK) {
              MaterialCheckBox withText = new MaterialCheckBox(ui.activity);
              withText.setText("С текстом");
              withText.setTextColor(Ui.INK);
              withText.setChecked(form.appendTaskText);
              withText.setOnCheckedChangeListener(
                  (v, checked) -> {
                    form.appendTaskText = checked;
                    scoped.changed();
                  });
              card.addView(withText);
            }
          }
          if (owner.parts.parts.size() > 1) {
            MaterialCheckBox linked = new MaterialCheckBox(ui.activity);
            linked.setText("Связать части с первой");
            linked.setTextColor(Ui.INK);
            linked.setMinHeight(ui.dp(48));
            linked.setChecked(owner.parts.linked);
            linked.setOnCheckedChangeListener(
                (v, checked) -> {
                  owner.parts.linked = checked;
                  try {
                    owner.parts.validate();
                  } catch (IllegalArgumentException error) {
                    owner.parts.linked = false;
                    Toast.makeText(ui.activity, error.getMessage(), Toast.LENGTH_LONG).show();
                  }
                  actions.changed();
                  panels.post(render[0]);
                });
            panels.addView(linked);
            panels.addView(
                ui.text(
                    owner.parts.linked
                        ? "1 — корень; остальные — дополнения к нему."
                        : "Пустая строка начинает новую часть. У каждой — свои разделы.",
                    12,
                    Ui.MUTED));
          }
        };
    render[0] = refresh;
    ui.watch(
        input,
        value -> {
          owner.edit(value);
          owner.cursor = Math.max(0, input.getSelectionStart());
          actions.changed();
          refresh.run();
        });
    input.setOnFocusChangeListener(
        (v, focus) -> owner.cursor = Math.max(0, input.getSelectionStart()));
    refresh.run();
    LinearLayout tools = ui.row();
    ui.weighted(
        tools,
        ui.button(
            DiaryLayout.fileName(owner.destinationPath).substring(0, 10), actions::destination));
    Runnable selectCaret =
        () -> {
          owner.cursor = Math.max(0, input.getSelectionStart());
          int index = 0;
          for (int i = 0; i < owner.parts.parts.size(); i++)
            if (owner.parts.parts.get(i).start() <= owner.cursor) index = i;
          actions.part(index);
          var part = owner.parts.parts.get(index);
          part.form().cursor =
              Math.max(
                  0, Math.min(part.form().editableText().length(), owner.cursor - part.start()));
        };
    ui.weighted(
        tools,
        ui.button(
            "На строку",
            () -> {
              selectCaret.run();
              actions.tags(true, false, owner.activePart().cursor);
            }));
    ui.action(
        tools,
        ru.obsnotes.R.drawable.ic_person,
        "Тег человека для блока или строки",
        () -> {
          selectCaret.run();
          new com.google.android.material.dialog.MaterialAlertDialogBuilder(ui.activity)
              .setItems(
                  new String[] {"Человек · весь блок", "Человек · текущая строка"},
                  (d, which) -> actions.tags(which == 1, true, owner.activePart().cursor))
              .show();
        });
    ui.body.addView(tools);
    LinearLayout relations = ui.row();
    ui.weighted(
        relations,
        ui.button(
            "Сделать корневым",
            () -> {
              selectCaret.run();
              actions.root();
            }));
    ui.weighted(
        relations,
        ui.button(
            "Связать",
            () -> {
              selectCaret.run();
              actions.link();
            }));
    ui.action(
        relations,
        ru.obsnotes.R.drawable.ic_link,
        "Показать связи",
        () -> {
          selectCaret.run();
          actions.related();
        });
    ui.body.addView(relations);
    ui.body.addView(
        ui.text(
            "Изменения сохраняются при выходе. Метки относятся к указанной части.", 12, Ui.MUTED));
    input.setSelection(Math.min(owner.cursor, input.length()));
    ui.focusInput(input);
  }

  private static void highlightParts(EditText input, EditorState owner) {
    var text = input.getText();
    for (var span : text.getSpans(0, text.length(), android.text.style.ForegroundColorSpan.class))
      text.removeSpan(span);
    if (!owner.parts.error.isEmpty()) return;
    for (var part : owner.parts.parts)
      for (var mark : LineMarks.of(part.form().editableText(), part.form().tags())) {
        int start = Math.min(text.length(), part.start() + mark.start()),
            end = Math.min(text.length(), part.start() + mark.end());
        if (start < end)
          text.setSpan(
              new android.text.style.ForegroundColorSpan(EditorScreen.lineColor(mark.colorIndex())),
              start,
              end,
              android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
  }

  private record PartActions(EditorScreen.Actions parent, Runnable select)
      implements EditorScreen.Actions {
    public void part(int i) {
      parent.part(i);
    }

    public void back() {
      parent.back();
    }

    public void save() {
      parent.save();
    }

    public void changed() {
      select.run();
      parent.changed();
    }

    public void sections() {
      select.run();
      parent.sections();
    }

    public void routesChanged() {
      select.run();
      parent.routesChanged();
    }

    public void replaceRoute(int i) {
      select.run();
      parent.replaceRoute(i);
    }

    public void replaceTag(int i) {
      select.run();
      parent.replaceTag(i);
    }

    public void today() {
      parent.today();
    }

    public void destination() {
      parent.destination();
    }

    public void tags(boolean line, boolean person, int cursor) {
      select.run();
      parent.tags(line, person, cursor);
    }

    public void root() {
      select.run();
      parent.root();
    }

    public void link() {
      select.run();
      parent.link();
    }

    public void related() {
      select.run();
      parent.related();
    }

    public void weatherLocation() {
      parent.weatherLocation();
    }

    public void fetchWeather() {
      parent.fetchWeather();
    }
  }
}
