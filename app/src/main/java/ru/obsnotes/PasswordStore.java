package ru.obsnotes;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only the PIN-encrypted password is persisted; the short-lived session is never serialized. */
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

  public void unlock(String vault, String pin) {
    session.unlock(prefs.getString(key(vault), ""), pin);
  }

  public void create(String vault, String password, String pin) {
    String wrapped = PasswordSession.create(password, pin);
    if (!prefs.edit().putString(key(vault), wrapped).commit())
      throw new IllegalArgumentException("Не удалось сохранить пароль");
    close();
  }

  public void change(String vault, String oldPin, String value, boolean pinOnly) {
    char[] pin = oldPin.toCharArray();
    String clear;
    PasswordSession.validatePin(oldPin);
    try {
      clear = InlineCrypto.decrypt(prefs.getString(key(vault), ""), pin);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Неверный старый PIN");
    } finally {
      Arrays.fill(pin, '\0');
    }
    create(vault, pinOnly ? clear : value, pinOnly ? value : oldPin);
  }

  public void delete(String vault, String oldPin) {
    PasswordSession check = new PasswordSession(SystemClock::elapsedRealtime);
    try {
      check.unlock(prefs.getString(key(vault), ""), oldPin);
    } finally {
      check.close();
    }
    if (!prefs.edit().remove(key(vault)).commit())
      throw new IllegalArgumentException("Не удалось удалить пароль");
    close();
  }

  @Override
  public void close() {
    session.close();
  }
}
