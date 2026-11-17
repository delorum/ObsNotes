package ru.obsnotes;

import java.util.*;
import java.util.function.LongSupplier;

/** Fixed three-minute lifetime from successful PIN unlock, using a monotonic clock. */
public final class PasswordSession implements AutoCloseable {
  public static final long LIFETIME_MS = 180000;
  private final LongSupplier clock;
  private char[] password;
  private long deadline;

  public PasswordSession(LongSupplier clock) {
    this.clock = clock;
  }

  public static void validatePin(String pin) {
    if (!pin.matches("[0-9]{4}")) throw new IllegalArgumentException("PIN — ровно 4 цифры");
  }

  public static String create(String password, String pin) {
    validatePin(pin);
    if (password.isEmpty()) throw new IllegalArgumentException("Введите пароль");
    char[] p = pin.toCharArray();
    try {
      return InlineCrypto.encrypt(password, p);
    } finally {
      Arrays.fill(p, '\0');
    }
  }

  public synchronized void unlock(String wrapped, String pin) {
    validatePin(pin);
    char[] p = pin.toCharArray();
    try {
      String clear = InlineCrypto.decrypt(wrapped, p);
      close();
      password = clear.toCharArray();
      deadline = clock.getAsLong() + LIFETIME_MS;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Неверный PIN", e);
    } finally {
      Arrays.fill(p, '\0');
    }
  }

  public synchronized char[] copy() {
    if (password != null && clock.getAsLong() >= deadline) close();
    return password == null ? null : password.clone();
  }

  public synchronized long remaining() {
    return password == null ? 0 : Math.max(0, deadline - clock.getAsLong());
  }

  @Override
  public synchronized void close() {
    if (password != null) Arrays.fill(password, '\0');
    password = null;
    deadline = 0;
  }
}
