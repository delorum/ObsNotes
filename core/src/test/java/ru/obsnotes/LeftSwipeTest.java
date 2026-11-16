package ru.obsnotes;

import static org.junit.Assert.*;

import org.junit.Test;

public class LeftSwipeTest {
  @Test
  public void longLeftSwipeRequestsDeletionAndResets() {
    var gesture = new LeftSwipe(8, 64);
    gesture.down(200, 100);
    assertTrue(gesture.move(180, 103));
    assertEquals(-20, gesture.translation(), 0);
    assertTrue(gesture.end(80, 104, false));
    assertEquals(0, gesture.translation(), 0);
    gesture.down(50, 50);
    assertFalse(gesture.end(50, 50, false));
  }

  @Test
  public void tapRightShortAndCanceledGesturesCannotDelete() {
    for (float dx : new float[] {0, 100, -50}) {
      var g = new LeftSwipe(8, 64);
      g.down(100, 100);
      g.move(100 + dx, 100);
      assertFalse(g.end(100 + dx, 100, false));
    }
    var g = new LeftSwipe(8, 64);
    g.down(100, 100);
    g.move(0, 100);
    assertFalse(g.end(0, 100, true));
  }

  @Test
  public void verticalScrollCannotTurnIntoDeleteLaterInSameGesture() {
    var g = new LeftSwipe(8, 64);
    g.down(200, 100);
    assertFalse(g.move(198, 130));
    assertFalse(g.move(20, 140));
    assertFalse(g.end(0, 140, false));
  }
}
