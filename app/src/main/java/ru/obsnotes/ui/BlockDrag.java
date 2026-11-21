package ru.obsnotes.ui;

import android.content.ClipData;
import android.view.*;
import android.widget.FrameLayout;
import ru.obsnotes.*;

/** Local drag state accepts original blocks of exactly one file/heading; projections never move. */
final class BlockDrag {
  record Moving(DayContentRules.Item item) {}

  private final Ui ui;
  private final DayScreen.Actions actions;
  private final android.widget.ScrollView viewport;
  private boolean active;
  private int scrollStep;
  private final Runnable scroll =
      new Runnable() {
        public void run() {
          if (!active || !viewport.isAttachedToWindow()) {
            active = false;
            return;
          }
          if (scrollStep != 0) viewport.scrollBy(0, scrollStep);
          viewport.postDelayed(this, 24);
        }
      };

  BlockDrag(Ui ui, DayScreen.Actions actions) {
    this.ui = ui;
    this.actions = actions;
    this.viewport = ui.scroll;
    viewport.setOnDragListener(
        (v, e) -> {
          if (!(e.getLocalState() instanceof Moving)) return false;
          if (e.getAction() == DragEvent.ACTION_DRAG_STARTED) {
            active = true;
            viewport.post(scroll);
          }
          if (e.getAction() == DragEvent.ACTION_DRAG_LOCATION)
            scrollStep =
                e.getY() < ui.dp(64)
                    ? -ui.dp(12)
                    : e.getY() > viewport.getHeight() - ui.dp(64) ? ui.dp(12) : 0;
          if (e.getAction() == DragEvent.ACTION_DRAG_ENDED) {
            active = false;
            scrollStep = 0;
            viewport.removeCallbacks(scroll);
          }
          return true;
        });
  }

  void bind(View touch, FrameLayout frame, DayContentRules.Item item) {
    if (item.projection()) return;
    touch.setOnLongClickListener(
        v -> {
          ui.hideKeyboard();
          return frame.startDragAndDrop(
              ClipData.newPlainText("Запись", ""),
              new View.DragShadowBuilder(frame),
              new Moving(item),
              0);
        });
    frame.setOnDragListener(
        (v, event) -> {
          if (!(event.getLocalState() instanceof Moving moving)) return false;
          var from = moving.item();
          boolean same =
              !item.projection()
                  && from.path().equals(item.path())
                  && from.original().section().equals(item.original().section());
          if (!same) return false;
          switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED -> frame.setAlpha(.6f);
            case DragEvent.ACTION_DRAG_LOCATION -> {
              int[] blockPosition = new int[2], viewportPosition = new int[2];
              frame.getLocationOnScreen(blockPosition);
              viewport.getLocationOnScreen(viewportPosition);
              float y = blockPosition[1] + event.getY() - viewportPosition[1];
              scrollStep =
                  y < ui.dp(64) ? -ui.dp(12) : y > viewport.getHeight() - ui.dp(64) ? ui.dp(12) : 0;
            }
            case DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> frame.setAlpha(1f);
            case DragEvent.ACTION_DROP -> {
              frame.setAlpha(1f);
              if (from.original().start() != item.original().start())
                actions.reorder(from.original(), item.original(), item.path());
            }
          }
          return true;
        });
  }
}
