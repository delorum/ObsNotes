package ru.obsnotes.ui;

import android.widget.*;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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
    ChipGroup metadata = new ChipGroup(ui.activity);
    metadata.setChipSpacingHorizontal(ui.dp(4));
    metadata.setChipSpacingVertical(0);
    EditText edit = ui.field(ui.body, "Текст", state.editableText(), true, null);
    Runnable refreshMetadata = () -> metadata(ui, state, metadata, actions, edit);
    ui.watch(
        edit,
        value -> {
          state.edit(value);
          actions.changed();
          refreshMetadata.run();
        });
    edit.setSelection(Math.min(Math.max(0, state.cursor), edit.length()));
    ui.body.addView(metadata);
    refreshMetadata.run();
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
    ui.body.addView(
        ui.text("Назад оставляет черновик. Метки и связи — под текстом.", 12, Ui.MUTED));
    edit.setOnFocusChangeListener(
        (v, focused) -> {
          state.cursor = edit.getSelectionStart();
          actions.changed();
        });
  }

  private static void metadata(
      Ui ui, EditorState state, ChipGroup group, Actions actions, EditText edit) {
    highlight(edit, state);
    group.removeAllViews();
    List<TaggedText.Tag> tags = state.tags();
    String clean = state.editableText();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < tags.size(); i++) order.add(i);
    order.sort(java.util.Comparator.comparingInt(i -> tags.get(i).oneLine() ? 1 : 0));
    for (int i : order) {
      int index = i;
      var tag = tags.get(i);
      String label = tag.ids();
      int color = Ui.ACCENT;
      for (var mark : LineMarks.of(clean, tags))
        if (tag.oneLine() && mark.line() == tag.line(clean)) color = lineColor(mark.colorIndex());
      Chip chip = chip(ui, label, color);
      if (tag.oneLine()) chip.setContentDescription(tag.ids() + ", строка " + tag.line(clean));
      else chip.setContentDescription(tag.ids() + ", весь блок");
      chip.setOnCloseIconClickListener(
          v -> {
            state.removeTag(index);
            actions.changed();
            metadata(ui, state, group, actions, edit);
          });
      group.addView(chip);
    }
    String root = state.rootLinkLabel();
    if (!root.isEmpty()) {
      Chip chip = chip(ui, "К корню: " + root, Ui.ACCENT);
      chip.setOnClickListener(v -> actions.related());
      chip.setOnCloseIconClickListener(
          v -> {
            state.removeRootLink();
            actions.changed();
            metadata(ui, state, group, actions, edit);
          });
      group.addView(chip);
    }
    group.setVisibility(
        group.getChildCount() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
  }

  private static final int[] LINE_COLORS = {
    Ui.GREEN, 0xffe8be63, 0xff7cb4ed, 0xffef9ba5, 0xff6bcfc3, 0xffe6a66c
  };

  private static int lineColor(int index) {
    return LINE_COLORS[index % LINE_COLORS.length];
  }

  private static void highlight(EditText edit, EditorState state) {
    android.text.Editable text = edit.getText();
    for (var span : text.getSpans(0, text.length(), android.text.style.ForegroundColorSpan.class))
      text.removeSpan(span);
    for (var mark : LineMarks.of(state.editableText(), state.tags())) {
      int start = Math.min(mark.start(), text.length()), end = Math.min(mark.end(), text.length());
      if (end == start && end < text.length()) end++;
      if (end > start)
        text.setSpan(
            new android.text.style.ForegroundColorSpan(lineColor(mark.colorIndex())),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
  }

  private static Chip chip(Ui ui, String label, int color) {
    Chip chip = new Chip(ui.activity);
    chip.setText(label);
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
