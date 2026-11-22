package ru.obsnotes.ui;

import com.google.android.material.button.MaterialButton;
import ru.obsnotes.EditorState;

/** Explicit two-state wording, shared by ordinary and multi-part task destinations. */
final class TaskTextMode {
  private TaskTextMode() {}

  static MaterialButton button(Ui ui, EditorState state, Runnable changed) {
    return button(ui, () -> state, changed);
  }

  static MaterialButton button(
      Ui ui, java.util.function.Supplier<EditorState> current, Runnable changed) {
    MaterialButton button = ui.button(label(current.get()), () -> {});
    button.setOnClickListener(
        v -> {
          var state = current.get();
          state.appendTaskText = !state.appendTaskText;
          button.setText(label(state));
          changed.run();
        });
    return button;
  }

  private static String label(EditorState state) {
    return state.appendTaskText ? "С текстом" : "Без текста";
  }
}
