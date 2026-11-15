package ru.obsnotes.ui;

import android.widget.*;
import java.util.*;
import ru.obsnotes.*;

public final class EditorScreen {
  private EditorScreen() {}

  public interface Actions {
    void back();

    void save();

    void changed();

    void target();

    void destination();

    void tags(boolean oneLine, boolean person, int cursor);

    void root();

    void link();

    void related();
  }

  public static void show(Ui ui, EditorState state, Actions actions) {
    ui.page(state.fresh ? "Новая заметка" : "Текстовый блок", actions::back);
    ui.action(ui.bar, ru.obsnotes.R.drawable.ic_save, "Сохранить", actions::save);
    EditText edit =
        ui.field(
            ui.body,
            "Текст",
            state.editableText(),
            true,
            value -> {
              state.edit(value);
              actions.changed();
            });
    edit.setSelection(Math.min(Math.max(0, state.cursor), edit.length()));
    LinearLayout target = ui.row();
    var day =
        ui.button(
            DiaryLayout.fileName(state.destinationPath).substring(0, 10), actions::destination);
    ui.weighted(target, day);
    var heading =
        ui.button(
            state.kind == Markdown.Kind.SECTION
                ? state.heading.replaceFirst("^#+ ", "")
                : state.targetSource,
            actions::target);
    heading.setMaxLines(2);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    ui.weighted(target, heading);
    ui.body.addView(target);
    LinearLayout tags = ui.row();
    ui.weighted(
        tags,
        ui.button(
            "Допразделы",
            () -> {
              state.cursor = edit.getSelectionStart();
              actions.tags(false, false, state.cursor);
            }));
    ui.weighted(
        tags,
        ui.button(
            "На строку",
            () -> {
              state.cursor = edit.getSelectionStart();
              actions.tags(true, false, state.cursor);
            }));
    ui.action(
        tags,
        ru.obsnotes.R.drawable.ic_person,
        "Тег человека для блока или строки",
        () -> {
          state.cursor = edit.getSelectionStart();
          new com.google.android.material.dialog.MaterialAlertDialogBuilder(ui.activity)
              .setItems(
                  new String[] {"Человек · весь блок", "Человек · текущая строка"},
                  (d, which) -> actions.tags(which == 1, true, state.cursor))
              .show();
        });
    ui.body.addView(tags);
    LinearLayout relations = ui.row();
    ui.weighted(
        relations,
        ui.button(
            Events.id(state.text).isEmpty() ? "Сделать корневым" : "Корневой блок", actions::root));
    ui.weighted(relations, ui.button("Связать", actions::link));
    ui.action(relations, ru.obsnotes.R.drawable.ic_link, "Показать связи", actions::related);
    ui.body.addView(relations);
    String id = Events.id(state.text);
    if (state.text.contains("#^"))
      ui.body.addView(
          ui.text(
              state.text.lines().filter(s -> s.contains("#^")).findFirst().orElse(""),
              13,
              Ui.ACCENT));
    ui.body.addView(
        ui.text(
            "Назад оставляет черновик. Теги добавляются в текст; для удаления сотрите нужный тег.",
            12,
            Ui.MUTED));
    edit.setOnFocusChangeListener(
        (v, focused) -> {
          state.cursor = edit.getSelectionStart();
          actions.changed();
        });
  }
}
