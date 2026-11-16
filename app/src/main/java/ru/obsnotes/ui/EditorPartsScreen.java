package ru.obsnotes.ui;

import android.widget.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.*;
import ru.obsnotes.*;

/** All metadata stays visible; common controls act on the paragraph at the caret. */
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
    Routing.Catalog catalog = owner.routingCatalog(template);
    Runnable[] render = new Runnable[1];
    Runnable refresh =
        () -> {
          panels.removeAllViews();
          highlightParts(input, owner);
          if (!owner.parts.error.isEmpty()) panels.addView(ui.text(owner.parts.error, 12, Ui.RED));
          int colorOffset = 0;
          for (int i = 0; i < owner.parts.parts.size(); i++) {
            int index = i;
            var part = owner.parts.parts.get(i);
            EditorState form = part.form();
            Runnable select =
                () -> {
                  owner.cursor = Math.max(0, input.getSelectionStart());
                  form.cursor =
                      Math.max(
                          0, Math.min(form.editableText().length(), owner.cursor - part.start()));
                  actions.part(index);
                };
            EditorScreen.Actions scoped = new PartActions(actions, select, () -> render[0].run());
            LinearLayout card = ui.card(panels);
            card.setContentDescription("Назначения части " + (i + 1));
            ChipGroup chips = new ChipGroup(ui.activity);
            chips.setChipSpacingHorizontal(ui.dp(4));
            chips.setChipSpacingVertical(0);
            card.addView(chips);
            EditorScreen.metadata(ui, form, catalog, chips, scoped, List.of(), colorOffset, false);
            colorOffset += LineMarks.of(form.editableText(), form.tags()).size();
            Chip relation = EditorScreen.chip(ui, owner.parts.relationLabel(i), Ui.MUTED);
            relation.setContentDescription("Связь части " + (i + 1));
            relation.setCloseIconVisible(false);
            relation.setChipIcon(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    ui.activity, ru.obsnotes.R.drawable.ic_link));
            relation.setChipIconTint(android.content.res.ColorStateList.valueOf(Ui.MUTED));
            relation.setChipIconVisible(true);
            relation.setOnClickListener(
                v -> {
                  select.run();
                  PartLinkPicker.show(ui, owner, index, scoped::link, scoped::routesChanged);
                });
            if (owner.parts.linksEnabled() || !owner.parts.relationLabel(i).equals("Связь")) {
              chips.addView(relation);
              chips.setVisibility(android.view.View.VISIBLE);
            }
            card.setVisibility(
                chips.getChildCount() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
            if (form.kind == Markdown.Kind.TASK) {
              Chip withText = EditorScreen.chip(ui, "С текстом", Ui.INK);
              withText.setCloseIconVisible(false);
              withText.setCheckable(true);
              withText.setChecked(form.appendTaskText);
              withText.setOnCheckedChangeListener(
                  (v, checked) -> {
                    form.appendTaskText = checked;
                    scoped.changed();
                  });
              chips.addView(withText);
            }
          }
          if (owner.parts.parts.size() > 1) {
            MaterialCheckBox linked = new MaterialCheckBox(ui.activity);
            linked.setText("Связать части");
            linked.setTextColor(Ui.INK);
            linked.setMinHeight(ui.dp(48));
            linked.setChecked(owner.parts.linksEnabled());
            linked.setOnCheckedChangeListener(
                (v, checked) -> {
                  try {
                    if (checked)
                      for (var part : owner.parts.parts)
                        if (part.form().kind != Markdown.Kind.SECTION)
                          throw new IllegalArgumentException(
                              "Связи доступны для разделов, без задач и Dataview-ключей");
                    owner.parts.linkAll(checked);
                    actions.changed();
                  } catch (IllegalArgumentException error) {
                    Toast.makeText(ui.activity, error.getMessage(), Toast.LENGTH_LONG).show();
                  }
                  panels.post(render[0]);
                });
            panels.addView(linked);
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
    var date =
        ui.button(
            RelativeDates.label(owner.destinationPath, owner.sourcePath), actions::destination);
    date.setContentDescription("День для всех частей");
    ui.body.addView(date, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout tools = ui.row();
    Runnable selectCaret =
        () -> {
          owner.cursor = Math.max(0, input.getSelectionStart());
          int index = owner.parts.selectAt(owner.cursor);
          actions.part(index);
          var part = owner.parts.parts.get(index);
          part.form().cursor =
              Math.max(
                  0, Math.min(part.form().editableText().length(), owner.cursor - part.start()));
        };
    ui.weighted(
        tools,
        ui.button(
            "Разделы",
            () -> {
              selectCaret.run();
              actions.sections();
            }));
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
              PartLinkPicker.show(
                  ui, owner, owner.parts.selected, actions::link, actions::routesChanged);
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
    for (var mark : LineMarks.of(owner.editableText(), owner.tags())) {
      int start = Math.min(text.length(), mark.start()), end = Math.min(text.length(), mark.end());
      if (start < end)
        text.setSpan(
            new android.text.style.ForegroundColorSpan(EditorScreen.lineColor(mark.colorIndex())),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
  }

  private record PartActions(EditorScreen.Actions parent, Runnable select, Runnable refresh)
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
      refresh.run();
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
