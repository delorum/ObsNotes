package ru.obsnotes;

import java.util.*;
import java.util.function.LongSupplier;

/** Fixed three-minute lifetime from successful password unlock, using a monotonic clock. */
public final class PasswordSession implements AutoCloseable {
  public static final long LIFETIME_MS = 180000;
  private final LongSupplier clock;
  private char[] password;
  private long deadline;

  public PasswordSession(LongSupplier clock) {
    this.clock = clock;
  }

  public static void validateUnlockPassword(String unlockPassword) {
    if (unlockPassword.isEmpty()) throw new IllegalArgumentException("Введите пинкод");
  }

  public static String create(String password, String unlockPassword) {
    validateUnlockPassword(unlockPassword);
    if (password.isEmpty()) throw new IllegalArgumentException("Введите пароль");
    char[] p = unlockPassword.toCharArray();
    try {
      return InlineCrypto.encrypt(password, p);
    } finally {
      Arrays.fill(p, '\0');
    }
  }

  /**
   * Credential changes always authenticate the supplied old credential, even with an active
   * session.
   */
  public static String change(
      String wrapped, String currentUnlockPassword, String value, boolean unlockOnly) {
    validateUnlockPassword(currentUnlockPassword);
    if (unlockOnly) validateUnlockPassword(value);
    else if (value.isEmpty()) throw new IllegalArgumentException("Введите пароль");
    char[] current = currentUnlockPassword.toCharArray();
    String clear;
    try {
      clear = InlineCrypto.decrypt(wrapped, current);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Неверный старый пинкод", error);
    } finally {
      Arrays.fill(current, '\0');
    }
    return create(unlockOnly ? clear : value, unlockOnly ? value : currentUnlockPassword);
  }

  public synchronized void unlock(String wrapped, String unlockPassword) {
    validateUnlockPassword(unlockPassword);
    char[] p = unlockPassword.toCharArray();
    try {
      String clear = InlineCrypto.decrypt(wrapped, p);
      close();
      password = clear.toCharArray();
      deadline = clock.getAsLong() + LIFETIME_MS;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Неверный пинкод", e);
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
