package ru.obsnotes.ui;

import android.view.*;
import android.widget.*;
import androidx.core.view.ViewCompat;
import ru.obsnotes.LeftSwipe;

/** Horizontal swipes never claim vertical scrolling or ordinary taps. */
public final class SwipeToDelete {
  private SwipeToDelete() {}

  public static FrameLayout wrap(Ui ui, LinearLayout content) {
    FrameLayout frame = new FrameLayout(ui.activity);
    TextView hint = ui.bold("Удалить", 15, Ui.RED);
    hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    hint.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    hint.setBackground(ui.background(0xff442b32, 10));
    hint.setVisibility(View.INVISIBLE);
    frame.addView(hint, new FrameLayout.LayoutParams(-1, -1));
    content.setBackground(ui.background(Ui.CARD, 10));
    frame.addView(content);
    return frame;
  }

  // Normal taps retain View.onTouchEvent/performClick; only swipes are consumed here.
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  public static void bind(Ui ui, View touch, FrameLayout frame, View content, Runnable remove) {
    bind(ui, touch, frame, content, remove, null);
  }

  @android.annotation.SuppressLint("ClickableViewAccessibility")
  public static void bind(
      Ui ui, View touch, FrameLayout frame, View content, Runnable remove, Runnable select) {
    int slop = ViewConfiguration.get(ui.activity).getScaledTouchSlop();
    LeftSwipe swipe = new LeftSwipe(slop, ui.dp(64)), right = new LeftSwipe(slop, ui.dp(64));
    touch.setOnTouchListener(
        new View.OnTouchListener() {
          boolean horizontal, selecting;

          public boolean onTouch(View view, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                swipe.down(event.getRawX(), event.getRawY());
                right.down(-event.getRawX(), event.getRawY());
                horizontal = selecting = false;
                break;
              case MotionEvent.ACTION_MOVE:
                if (!horizontal
                    && (swipe.move(event.getRawX(), event.getRawY())
                        || select != null && right.move(-event.getRawX(), event.getRawY()))) {
                  selecting = swipe.translation() == 0;
                  horizontal = true;
                  view.cancelLongPress();
                  view.getParent().requestDisallowInterceptTouchEvent(true);
                  TextView hint = (TextView) frame.getChildAt(0);
                  hint.setText(selecting ? "Выбрать" : "Удалить");
                  hint.setTextColor(selecting ? Ui.GREEN : Ui.RED);
                  hint.setGravity(
                      (selecting ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
                  hint.setVisibility(View.VISIBLE);
                }
                if (horizontal) {
                  swipe.move(event.getRawX(), event.getRawY());
                  right.move(-event.getRawX(), event.getRawY());
                  content.setTranslationX(selecting ? -right.translation() : swipe.translation());
                  return true;
                }
                break;
              case MotionEvent.ACTION_UP:
              case MotionEvent.ACTION_CANCEL:
                content.setTranslationX(0);
                frame.getChildAt(0).setVisibility(View.INVISIBLE);
                boolean choose =
                    right.end(
                        -event.getRawX(),
                        event.getRawY(),
                        event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                boolean delete =
                    swipe.end(
                        event.getRawX(),
                        event.getRawY(),
                        event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                if (horizontal) {
                  view.setPressed(false);
                  view.getParent().requestDisallowInterceptTouchEvent(false);
                  if (selecting ? choose : delete) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (selecting) select.run();
                    else remove.run();
                  }
                  return true;
                }
            }
            return false;
          }
        });
    if (select != null)
      ViewCompat.addAccessibilityAction(
          touch,
          "Выбрать запись",
          (v, args) -> {
            select.run();
            return true;
          });
    ViewCompat.addAccessibilityAction(
        touch,
        "Удалить запись",
        (v, args) -> {
          remove.run();
          return true;
        });
  }
}
