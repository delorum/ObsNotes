package ru.obsnotes;

/** Gesture state shared by touch binding and tests; vertical motion cannot become deletion. */
public final class LeftSwipe {
  private final float slop, threshold;
  private float startX, startY, dx;
  private boolean horizontal, vertical;

  public LeftSwipe(float slop, float threshold) {
    this.slop = slop;
    this.threshold = threshold;
  }

  public void down(float x, float y) {
    startX = x;
    startY = y;
    dx = 0;
    horizontal = false;
    vertical = false;
  }

  public boolean move(float x, float y) {
    dx = x - startX;
    float dy = y - startY;
    if (!horizontal && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) vertical = true;
    if (!vertical && dx < -slop && Math.abs(dx) > 1.5f * Math.abs(dy)) horizontal = true;
    return horizontal;
  }

  public float translation() {
    return horizontal ? Math.min(0, dx) : 0;
  }

  public boolean end(float x, float y, boolean canceled) {
    move(x, y);
    boolean delete = !canceled && horizontal && dx < -threshold;
    horizontal = false;
    vertical = false;
    dx = 0;
    return delete;
  }
}
