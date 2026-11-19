package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class NextDayAvailabilityTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);

  @Test
  public void redrawsAndPendingChecksDoNotRepeatQueriesAndExpiryUsesCompletionTime() {
    var cache = new NextDayAvailability();
    var request = cache.request("vault", DATE, 0);
    assertNotNull(request);
    assertFalse(cache.available("vault", DATE));
    assertNull(cache.request("vault", DATE, 50000));
    assertTrue(cache.complete(request, true, 60000));
    assertTrue(cache.available("vault", DATE));
    assertNull(cache.request("vault", DATE, 64999));
    var refresh = cache.request("vault", DATE, 65000);
    assertNotNull(refresh);
    assertTrue(cache.available("vault", DATE));
    assertTrue(cache.complete(refresh, false, 65001));
    assertFalse(cache.available("vault", DATE));
    assertNull(cache.request("vault", DATE, 65002));
  }

  @Test
  public void datesAndVaultsCannotReuseAvailabilityOrAcceptOutOfOrderResults() {
    var cache = new NextDayAvailability();
    var first = cache.request("one", DATE, 0);
    cache.complete(first, true, 1);
    assertFalse(cache.available("one", DATE.plusDays(1)));
    assertFalse(cache.available("two", DATE));
    var second = cache.request("one", DATE.plusDays(1), 2);
    assertFalse(cache.available("one", DATE.plusDays(1)));
    assertFalse(cache.complete(first, true, 3));
    assertNull(cache.request("one", DATE.plusDays(1), 4));
    var third = cache.request("two", DATE.plusDays(1), 5);
    assertFalse(cache.complete(second, true, 6));
    assertTrue(cache.complete(third, true, 7));
    assertTrue(cache.available("two", DATE.plusDays(1)));
  }

  @Test
  public void reconnectingSameVaultInvalidatesInFlightChecksAndCompletedCache() {
    var cache = new NextDayAvailability();
    var old = cache.request("one", DATE, 0);
    cache.clear();
    var fresh = cache.request("one", DATE, 1);
    assertFalse(cache.complete(old, true, 2));
    assertTrue(cache.complete(fresh, true, 3));
    cache.clear();
    assertFalse(cache.available("one", DATE));
    assertNotNull(cache.request("one", DATE, 4));
  }

  private static final class MetadataStorage implements VaultStorage {
    final List<String> probes = new ArrayList<>();
    boolean exists;
    IOException error;

    public boolean exists(String path) throws IOException {
      probes.add(path);
      if (error != null) throw error;
      return exists;
    }

    public String read(String path) {
      throw new AssertionError("Never read note contents");
    }

    public void write(String path, String text) {
      throw new AssertionError("Never write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Never enumerate");
    }
  }

  @Test
  public void probesOnlyNextDatesCalculatedPathIncludingMonthYearAndWeekBoundaries()
      throws Exception {
    var storage = new MetadataStorage();
    for (var date : List.of(DATE, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31))) {
      storage.exists = false;
      assertFalse(DayNavigation.hasNext(storage, date));
      storage.exists = true;
      assertTrue(DayNavigation.hasNext(storage, date));
      assertEquals(
          DiaryLayout.path(date.plusDays(1)), storage.probes.get(storage.probes.size() - 1));
    }
    assertEquals(6, storage.probes.size());
    storage.error = new IOException("Permission denied");
    assertSame(
        storage.error, assertThrows(IOException.class, () -> DayNavigation.hasNext(storage, DATE)));
  }

  @Test
  public void syncthingCreationAndDeletionBecomeVisibleAfterCacheRefresh() throws Exception {
    var cache = new NextDayAvailability();
    var storage = new MetadataStorage();
    var first = cache.request("one", DATE, 0);
    cache.complete(first, DayNavigation.hasNext(storage, DATE), 1);
    assertFalse(cache.available("one", DATE));
    storage.exists = true;
    assertNull(cache.request("one", DATE, 5000));
    var second = cache.request("one", DATE, 5001);
    cache.complete(second, DayNavigation.hasNext(storage, DATE), 5002);
    assertTrue(cache.available("one", DATE));
    storage.exists = false;
    var third = cache.request("one", DATE, 10002);
    cache.complete(third, DayNavigation.hasNext(storage, DATE), 10003);
    assertFalse(cache.available("one", DATE));
    assertEquals(3, storage.probes.size());
  }
}
