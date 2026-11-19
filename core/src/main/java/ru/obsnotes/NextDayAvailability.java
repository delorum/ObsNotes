package ru.obsnotes;

import java.time.LocalDate;

/** UI-thread cache for asynchronous next-day probes, using a monotonic millisecond clock. */
public final class NextDayAvailability {
  private static final long REFRESH_MS = 5000;

  public record Request(String vault, LocalDate date, long version) {}

  private Request current;
  private long version, checkedAt;
  private boolean checking, available;

  public Request request(String vault, LocalDate date, long now) {
    boolean same = matches(vault, date);
    if (same && (checking || now - checkedAt < REFRESH_MS)) return null;
    if (!same) available = false;
    current = new Request(vault, date, ++version);
    checking = true;
    return current;
  }

  public boolean complete(Request request, boolean result, long now) {
    if (!request.equals(current)) return false;
    checking = false;
    available = result;
    checkedAt = now;
    return true;
  }

  public boolean available(String vault, LocalDate date) {
    return matches(vault, date) && available;
  }

  public void clear() {
    current = null;
    checking = available = false;
  }

  private boolean matches(String vault, LocalDate date) {
    return current != null && current.vault().equals(vault) && current.date().equals(date);
  }
}
