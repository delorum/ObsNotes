package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class DayNavigationTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
  private static final DayNavigation.View CURRENT =
      new DayNavigation.View(DATE, DiaryLayout.path(DATE), new Markdown("## Заметки\nпример\n"));

  private static class Storage implements VaultStorage {
    List<String> reads = new ArrayList<>();
    IOException error;
    String text = "## Заметки\nраньше\n";

    public String read(String path) throws IOException {
      reads.add(path);
      if (error != null) throw error;
      return text;
    }

    public void write(String path, String value) {
      fail("Navigation must not write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Navigation must not enumerate");
    }
  }

  @Test
  public void missingDayRetainsExactCurrentViewAndNextNavigationStillWorks() throws Exception {
    var s = new Storage();
    s.error = new FileNotFoundException("long provider error");
    var result = DayNavigation.open(s, CURRENT, DATE.plusDays(1));
    assertSame(CURRENT, result.view());
    assertFalse(result.opened());
    assertEquals("Заметка не найдена", result.error());
    s.error = null;
    result = DayNavigation.open(s, result.view(), DATE.minusDays(1));
    assertTrue(result.opened());
    assertEquals(DATE.minusDays(1), result.view().date());
    assertEquals(
        List.of(DiaryLayout.path(DATE.plusDays(1)), DiaryLayout.path(DATE.minusDays(1))), s.reads);
  }

  @Test
  public void storageFallbackDistinguishesEmptyExistingFilesMissingFilesAndPermissionErrors()
      throws Exception {
    var storage = new Storage();
    storage.text = "";
    String next = DiaryLayout.path(DATE.plusDays(1));
    assertTrue(storage.exists(next));
    storage.error = new IOException("provider", new FileNotFoundException("missing"));
    assertFalse(storage.exists(next));
    storage.error = new IOException("Нет доступа");
    assertSame(storage.error, assertThrows(IOException.class, () -> storage.exists(next)));
    assertEquals(Collections.nCopies(3, next), storage.reads);
  }

  @Test
  public void wrappedMissingIsShortButOtherFailuresArePreserved() throws Exception {
    var s = new Storage();
    s.error = new IOException("provider", new FileNotFoundException("missing"));
    assertSame(CURRENT, DayNavigation.open(s, CURRENT, DATE).view());
    assertEquals(DayNavigation.NOT_FOUND, DayNavigation.message(s.error));
    s.error = new IOException("Нет доступа");
    assertSame(
        s.error, assertThrows(IOException.class, () -> DayNavigation.open(s, CURRENT, DATE)));
    assertEquals("Нет доступа", DayNavigation.message(s.error));
  }
}
