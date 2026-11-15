package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class PastDaysTest {
  static class Storage implements VaultStorage {
    List<String> reads = new ArrayList<>();
    Set<String> missing = new HashSet<>();

    public String read(String path) throws IOException {
      reads.add(path);
      if (missing.contains(path)) throw new FileNotFoundException(path);
      return "## День\n\nShortDescr:: Прогулка [[Парк]]\n";
    }

    public void write(String p, String text) {
      throw new AssertionError("Listing must not write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Listing must not enumerate folders");
    }
  }

  @Test
  public void loadsFourteenExactPathsFromCurrentDateBackwards() throws IOException {
    Storage storage = new Storage();
    LocalDate start = LocalDate.of(2026, 11, 15);
    var page = PastDays.load(storage, start);
    assertEquals(14, page.days().size());
    assertEquals(14, storage.reads.size());
    assertEquals(start, page.days().get(0).date());
    assertEquals(start.minusDays(14), page.nextDate());
    assertEquals("Прогулка [[Парк]]", page.days().get(0).description());
    for (int i = 0; i < 14; i++)
      assertEquals(DiaryLayout.path(start.minusDays(i)), storage.reads.get(i));
  }

  @Test
  public void nextPageDoesNotRepeatDaysAndCrossesYearBoundary() throws IOException {
    Storage storage = new Storage();
    var first = PastDays.load(storage, LocalDate.of(2026, 1, 5));
    var next = PastDays.load(storage, first.nextDate());
    assertEquals(LocalDate.of(2025, 12, 22), next.days().get(0).date());
    assertEquals(28, new HashSet<>(storage.reads).size());
  }

  @Test
  public void missingDaysAreSkippedButStillAdvanceFourteenDates() throws IOException {
    Storage storage = new Storage();
    LocalDate start = LocalDate.of(2026, 11, 15);
    storage.missing.add(DiaryLayout.path(start.minusDays(1)));
    var page = PastDays.load(storage, start);
    assertEquals(13, page.days().size());
    assertEquals(start.minusDays(14), page.nextDate());
    assertEquals(14, storage.reads.size());
  }

  @Test
  public void ignoresCodeExamplesAndOtherShortDescriptionKeys() {
    assertEquals(
        "Настоящее",
        PastDays.description(
            "```\nShortDescr:: пример\n```\nShortDescrWeek:: неделя\n[ShortDescr:: Настоящее]\n"));
    assertEquals("", PastDays.description("ShortDescrWeek:: неделя\n"));
  }

  @Test
  public void permissionErrorsAreNotSilentlySkipped() {
    Storage s =
        new Storage() {
          public String read(String path) throws IOException {
            throw new IOException("no permission");
          }
        };
    assertEquals(
        "no permission",
        assertThrows(IOException.class, () -> PastDays.load(s, LocalDate.now())).getMessage());
  }
}
