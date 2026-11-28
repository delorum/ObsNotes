package ru.obsnotes;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Only the wrapped encryption password is persisted; the short-lived session is never serialized.
 */
public final class PasswordStore implements AutoCloseable {
  private final SharedPreferences prefs;
  public final PasswordSession session = new PasswordSession(SystemClock::elapsedRealtime);

  public PasswordStore(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  private String key(String vault) {
    return "crypto-password-" + UUID.nameUUIDFromBytes(vault.getBytes(StandardCharsets.UTF_8));
  }

  public boolean exists(String vault) {
    return !vault.isEmpty() && prefs.contains(key(vault));
  }

  public void unlock(String vault, String unlockPassword) {
    session.unlock(prefs.getString(key(vault), ""), unlockPassword);
  }

  public void create(String vault, String password, String unlockPassword) {
    save(vault, PasswordSession.create(password, unlockPassword));
  }

  private void save(String vault, String wrapped) {
    if (!prefs.edit().putString(key(vault), wrapped).commit())
      throw new IllegalArgumentException("Не удалось сохранить пароль");
    close();
  }

  public void change(String vault, String oldUnlockPassword, String value, boolean unlockOnly) {
    save(
        vault,
        PasswordSession.change(
            prefs.getString(key(vault), ""), oldUnlockPassword, value, unlockOnly));
  }

  public void delete(String vault, String oldUnlockPassword) {
    PasswordSession.verify(prefs.getString(key(vault), ""), oldUnlockPassword);
    if (!prefs.edit().remove(key(vault)).commit())
      throw new IllegalArgumentException("Не удалось удалить пароль");
    close();
  }

  @Override
  public void close() {
    session.close();
  }
}
