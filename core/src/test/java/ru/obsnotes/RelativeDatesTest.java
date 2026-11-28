package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class RelativeDatesTest {
  @Test
  public void relativeLabelsUseFileDayAcrossYearBoundaries() {
    var origin = LocalDate.of(2025, 12, 31);
    assertEquals("Вчера", RelativeDates.label(origin.minusDays(1), origin));
    assertEquals("Позавчера", RelativeDates.label(origin.minusDays(2), origin));
    assertEquals("Завтра", RelativeDates.label(origin.plusDays(1), origin));
    assertEquals("Послезавтра", RelativeDates.label(origin.plusDays(2), origin));
    assertTrue(RelativeDates.label(origin, origin).startsWith("31.12.25"));
    assertTrue(RelativeDates.label(origin.plusDays(3), origin).startsWith("03.01.26"));
  }

  @Test
  public void relativeLabelsFollowTheChosenReferenceDay() {
    String origin = DiaryLayout.path(LocalDate.of(2011, 2, 28));
    assertEquals("Завтра", RelativeDates.label(DiaryLayout.path(LocalDate.of(2011, 3, 1)), origin));
    assertEquals("Вчера", RelativeDates.label(DiaryLayout.path(LocalDate.of(2011, 2, 27)), origin));
  }

  @Test
  public void onEachShortcutSelectionTheButtonShowsAnAbsoluteDate() {
    String destination = WorkDaily.path(LocalDate.of(2024, 3, 1));
    int[] offsets = {-2, -1, 1, 2};
    String[] dates = {"28.02.24", "29.02.24", "02.03.24", "03.03.24"};
    String[] labels = {"Позавчера", "Вчера", "Завтра", "Послезавтра"};
    for (int i = 0; i < offsets.length; i++) {
      LocalDate target = RelativeDates.shortcut(destination, offsets[i]);
      assertEquals(dates[i], RelativeDates.buttonLabel(DiaryLayout.path(target)));
      assertEquals(labels[i], RelativeDates.label(target, RelativeDates.date(destination)));
    }
  }

  @Test
  public void successiveShortcutsShiftTheSelectedDateAndCanReturnToTheOriginal() {
    LocalDate initial = LocalDate.of(2026, 1, 1);
    String path = DiaryLayout.path(initial);
    LocalDate yesterday = RelativeDates.shortcut(path, -1);
    String selected = DiaryLayout.path(yesterday);
    assertEquals(initial, RelativeDates.shortcut(selected, 1));
    assertEquals(initial.minusDays(3), RelativeDates.shortcut(selected, -2));
    assertEquals("Завтра", RelativeDates.label(initial, RelativeDates.date(selected)));
    assertEquals("31.12.25", RelativeDates.buttonLabel(selected));
  }

  @Test
  public void paletteDoesNotRepeatAfterSixOrBetweenParts() {
    Set<Integer> colors = new HashSet<>();
    for (int i = 0; i < 2048; i++) {
      int color = LineColors.argb(i);
      assertEquals(0xff000000, color & 0xff000000);
      assertTrue("Duplicate at " + i, colors.add(color));
      assertEquals(color, LineColors.argb(i));
    }
    assertEquals(0xffa7cd65, LineColors.argb(0));
    assertNotEquals(LineColors.argb(0), LineColors.argb(6));
  }
}
