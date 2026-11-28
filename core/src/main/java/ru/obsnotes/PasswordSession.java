package ru.obsnotes;

import java.util.Arrays;
import java.util.function.Consumer;
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

  /** Credential changes always authenticate the supplied old credential. */
  public static String change(
      String wrapped, String currentUnlockPassword, String value, boolean unlockOnly) {
    if (unlockOnly) validateUnlockPassword(value);
    else if (value.isEmpty()) throw new IllegalArgumentException("Введите пароль");
    byte[] clear = unwrapBytes(wrapped, currentUnlockPassword, "Неверный старый пинкод");
    if (!unlockOnly) {
      Arrays.fill(clear, (byte) 0);
      return create(value, currentUnlockPassword);
    }
    char[] next = null;
    try {
      next = value.toCharArray();
      return InlineCrypto.encryptBytes(clear, next);
    } finally {
      Arrays.fill(clear, (byte) 0);
      if (next != null) Arrays.fill(next, '\0');
    }
  }

  public static void verify(String wrapped, String unlockPassword) {
    Arrays.fill(unwrapBytes(wrapped, unlockPassword, "Неверный пинкод"), (byte) 0);
  }

  private static byte[] unwrapBytes(String wrapped, String unlockPassword, String errorMessage) {
    validateUnlockPassword(unlockPassword);
    char[] key = unlockPassword.toCharArray();
    try {
      return InlineCrypto.decryptBytes(wrapped, key);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(errorMessage, error);
    } finally {
      Arrays.fill(key, '\0');
    }
  }

  public void unlock(String wrapped, String unlockPassword) {
    validateUnlockPassword(unlockPassword);
    char[] key = unlockPassword.toCharArray();
    char[] clear = null;
    try {
      clear = InlineCrypto.decryptChars(wrapped, key);
      synchronized (this) {
        long expires = clock.getAsLong() + LIFETIME_MS;
        close();
        password = clear;
        deadline = expires;
        clear = null;
      }
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Неверный пинкод", error);
    } finally {
      Arrays.fill(key, '\0');
      if (clear != null) Arrays.fill(clear, '\0');
    }
  }

  public boolean isUnlocked() {
    return remaining() > 0;
  }

  /** The operation borrows a fresh copy only for its duration. */
  public boolean usePassword(Consumer<char[]> operation) {
    char[] working;
    synchronized (this) {
      if (!isUnlocked()) return false;
      working = password.clone();
    }
    try {
      operation.accept(working);
      return true;
    } finally {
      Arrays.fill(working, '\0');
    }
  }

  public synchronized long remaining() {
    if (password == null) return 0;
    long time = deadline - clock.getAsLong();
    if (time > 0) return time;
    close();
    return 0;
  }

  @Override
  public synchronized void close() {
    if (password != null) Arrays.fill(password, '\0');
    password = null;
    deadline = 0;
  }
}
