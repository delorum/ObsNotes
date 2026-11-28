package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PasswordSessionTest {
  private static final String PASSWORD = "Пароль 🔑 e\u0301";
  private static final String PIN = "Synthetic pincode";

  private static char[] cachedBuffer(PasswordSession session) throws ReflectiveOperationException {
    var field = PasswordSession.class.getDeclaredField("password");
    field.setAccessible(true);
    return (char[]) field.get(session);
  }

  private static void assertWiped(char[] buffer) {
    assertNotNull(buffer);
    assertArrayEquals(new char[buffer.length], buffer);
  }

  @Test
  public void expiryCheckWipesTheActualCacheAndStopsFurtherUse() throws Exception {
    var time = new AtomicLong(100);
    try (var session = new PasswordSession(time::get)) {
      session.unlock(PasswordSession.create(PASSWORD, PIN), PIN);
      char[] cache = cachedBuffer(session);
      assertArrayEquals(PASSWORD.toCharArray(), cache);
      time.addAndGet(PasswordSession.LIFETIME_MS);
      assertEquals(0, session.remaining());
      assertWiped(cache);
      assertNull(cachedBuffer(session));
      assertFalse(session.usePassword(chars -> fail("Expired password was delivered")));
    }
  }

  @Test
  public void reUnlockAndCloseWipeThePreviousCache() throws Exception {
    String wrapped = PasswordSession.create(PASSWORD, PIN);
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(wrapped, PIN);
      char[] first = cachedBuffer(session);
      session.unlock(wrapped, PIN);
      assertWiped(first);
      char[] second = cachedBuffer(session);
      assertArrayEquals(PASSWORD.toCharArray(), second);
      session.close();
      assertWiped(second);
      assertFalse(session.isUnlocked());
    }
  }

  @Test
  public void scopedOperationWipesWorkingCopyOnSuccessAndOnException() throws Exception {
    var copy = new AtomicReference<char[]>();
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(PasswordSession.create(PASSWORD, PIN), PIN);
      assertTrue(session.usePassword(copy::set));
      assertWiped(copy.get());
      char[] cache = cachedBuffer(session);
      assertArrayEquals(PASSWORD.toCharArray(), cache);
      assertThrows(
          IllegalStateException.class,
          () ->
              session.usePassword(
                  working -> {
                    copy.set(working);
                    assertArrayEquals(PASSWORD.toCharArray(), working);
                    throw new IllegalStateException("Synthetic crypto error");
                  }));
      assertWiped(copy.get());
      assertArrayEquals(PASSWORD.toCharArray(), cache);
    }
  }

  @Test
  public void expiryDuringOperationWipesCacheAndWorkingCopyIsWipedOnReturn() throws Exception {
    var time = new AtomicLong();
    var copy = new AtomicReference<char[]>();
    try (var session = new PasswordSession(time::get)) {
      session.unlock(PasswordSession.create(PASSWORD, PIN), PIN);
      char[] cache = cachedBuffer(session);
      assertTrue(
          session.usePassword(
              working -> {
                copy.set(working);
                time.addAndGet(PasswordSession.LIFETIME_MS);
                assertFalse(session.isUnlocked());
                assertWiped(cache);
                assertArrayEquals(PASSWORD.toCharArray(), working);
              }));
      assertWiped(copy.get());
      assertFalse(session.isUnlocked());
    }
  }

  @Test
  public void verificationAuthenticatesWithoutCreatingACache() {
    String wrapped = PasswordSession.create(PASSWORD, PIN);
    PasswordSession.verify(wrapped, PIN);
    assertThrows(IllegalArgumentException.class, () -> PasswordSession.verify(wrapped, "wrong"));
  }
}
