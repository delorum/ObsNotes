package ru.obsnotes.ui;

import android.content.Context;
import com.google.android.material.textfield.TextInputEditText;
import java.util.function.IntConsumer;

/** Reports taps, selection handles and hardware/IME cursor movements without changing focus. */
final class CursorEditText extends TextInputEditText {
  private IntConsumer selection;

  CursorEditText(Context context) {
    super(context);
  }

  void watchSelection(IntConsumer listener) {
    selection = listener;
  }

  @Override
  protected void onSelectionChanged(int start, int end) {
    super.onSelectionChanged(start, end);
    if (selection != null) selection.accept(Math.max(0, start));
  }
}
