package ru.obsnotes.ui;

import android.view.*;
import android.widget.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.util.*;
import ru.obsnotes.*;

final class DayBlockView {
  private DayBlockView() {}

  static void add(
      Ui ui,
      LinearLayout parent,
      DayContentRules.Item item,
      Set<String> expanded,
      DayScreen.Actions actions,
      BlockSelection selection,
      String dailyPath,
      BlockDrag drag,
      SelectionControls controls) {
    Markdown.Block block = item.original();
    String text = item.view().text();
    boolean projection = item.projection();
    String physicalPath = item.path().isEmpty() ? dailyPath : item.path();
    boolean selectable = EditorBatch.selectable(block, !item.path().isEmpty());
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
    FrameLayout frame = SwipeToDelete.wrap(ui, box);
    List<View> gestures = new ArrayList<>();
    var tasks =
        item.path().isEmpty()
            ? DiaryTasks.in(block.text(), block.start())
            : List.<DiaryTasks.Task>of();
    if (!tasks.isEmpty()
        && item.view().lines().stream()
            .anyMatch(
                line ->
                    projection
                        ? DiaryTasks.projectedLine(tasks, line.text()).isPresent()
                        : tasks.stream()
                            .anyMatch(t -> t.start() == block.start() + line.start()))) {
      Map<Integer, DiaryTasks.Task> byOffset = new HashMap<>();
      for (var task : tasks) byOffset.put(task.start(), task);
      for (var line : item.view().lines()) {
        if (line.text().isBlank()) continue;
        var task =
            projection
                ? DiaryTasks.projectedLine(tasks, line.text()).orElse(null)
                : byOffset.get(block.start() + line.start());
        if (task == null) {
          TextView note = ui.text(CrossReferences.clean(line.text()), 15, Ui.INK);
          note.setOnClickListener(v -> actions.edit(block, item.path()));
          box.addView(note);
          gestures.add(note);
          continue;
        }
        LinearLayout row = ui.row();
        MaterialCheckBox check = new MaterialCheckBox(ui.activity);
        check.setChecked(task.checked());
        check.setContentDescription(
            (task.checked() ? "Снять отметку: " : "Отметить: ") + task.label());
        row.addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView label =
            ui.text(
                task.label() + (projection ? " ↳ " + block.section().title() : ""),
                15,
                task.checked() ? Ui.MUTED : projection ? Ui.ACCENT : Ui.INK);
        label.setMinHeight(ui.dp(48));
        label.setGravity(Gravity.CENTER_VERTICAL);
        ui.weighted(row, label);
        Runnable toggle =
            () -> {
              check.setChecked(task.checked());
              actions.toggle(task);
            };
        check.setOnClickListener(v -> toggle.run());
        label.setContentDescription("Редактировать задачу: " + task.label());
        label.setOnClickListener(v -> actions.appendTask(task));
        box.addView(row);
        gestures.add(check);
        gestures.add(label);
      }
    } else {
      String key =
          item.path() + ":" + block.section().heading() + ":" + block.start() + ":" + projection;
      boolean open = expanded.contains(key);
      LinearLayout row = ui.row();
      TextView full =
          ui.text(text + (projection ? "\n↳ " + block.section().title() : ""), 16, Ui.INK);
      full.setTextIsSelectable(false);
      full.setVisibility(open ? View.VISIBLE : View.GONE);
      var expand =
          ui.icon(
              open ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right,
              "Развернуть текстовый блок",
              () -> {});
      expand.setOnClickListener(
          v -> {
            if (selectable && controls.active()) {
              controls.toggle(item);
              return;
            }
            boolean visible = full.getVisibility() != View.VISIBLE;
            full.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) expanded.add(key);
            else expanded.remove(key);
            expand.setTag(null);
            expand.setIcon(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    ui.activity,
                    visible ? ru.obsnotes.R.drawable.ic_down : ru.obsnotes.R.drawable.ic_right));
          });
      row.addView(expand, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
      TextView preview =
          ui.text(
              CrossReferences.clean(text).lines().findFirst().orElse("Запись"),
              15,
              projection ? Ui.ACCENT : Ui.INK);
      preview.setMaxLines(2);
      preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
      preview.setMinHeight(ui.dp(48));
      preview.setGravity(Gravity.CENTER_VERTICAL);
      ui.weighted(row, preview);
      Runnable tap =
          () -> {
            if (selectable && controls.active()) controls.toggle(item);
            else actions.edit(block, item.path());
          };
      preview.setOnClickListener(v -> tap.run());
      full.setOnClickListener(v -> tap.run());
      box.setOnClickListener(v -> tap.run());
      gestures.add(box);
      controls.watch(
          () -> {
            boolean selected = selection.contains(physicalPath, block);
            if (box.isSelected() != selected) {
              box.setSelected(selected);
              var background = ui.background(Ui.CARD, 10);
              if (selected) background.setStroke(ui.dp(1), Ui.INK);
              box.setBackground(background);
              frame.setContentDescription(
                  selected ? "Выбранная запись: " + text.lines().findFirst().orElse("") : null);
            }
            expand.setContentDescription(
                selectable && controls.active()
                    ? selected ? "Снять выбор записи" : "Выбрать запись"
                    : "Развернуть текстовый блок");
            int icon =
                selected
                    ? ru.obsnotes.R.drawable.ic_task
                    : full.getVisibility() == View.VISIBLE
                        ? ru.obsnotes.R.drawable.ic_down
                        : ru.obsnotes.R.drawable.ic_right;
            if (!Integer.valueOf(icon).equals(expand.getTag())) {
              expand.setTag(icon);
              expand.setIconResource(icon);
            }
          });
      box.addView(row);
      box.addView(full);
      gestures.add(expand);
      gestures.add(preview);
      gestures.add(full);
    }
    Runnable remove = () -> actions.delete(block, projection, item.path());
    for (View gesture : gestures) {
      SwipeToDelete.bind(
          ui, gesture, frame, box, remove, selectable ? () -> controls.toggle(item) : null);
      if (selectable) drag.bind(gesture, frame, item);
    }
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.bottomMargin = ui.dp(8);
    layout.leftMargin = layout.rightMargin = ui.dp(10);
    parent.addView(frame, layout);
  }
}
