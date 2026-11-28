package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class InlineCryptoTest {
  static final String PASSWORD = "Пароль🔑e\u0301",
      CLEAR = "Личная заметка\nВторая строка\n\nТретий абзац 🦉";
  // Produced by the actual vault plugin's CryptoFactory/WebCrypto with fixed synthetic IV and salt.
  static final String VECTOR =
      "EBESExQVFhcYGRobHB0eHwABAgMEBQYHCAkKCwwNDg+SvAKmNxt8xaF97m7ICV4XVx7HgryWtf4wjkW92N79rurW61mrEMLilDPJDAl/h6ZK8a4ppchW/hJgKZWsnBMokaiYxVRbCNcODRy47Pxakg7DSz+/iJlI1IHV2g80xSiogCA=";

  private static void assertCachedPassword(PasswordSession session, String expected) {
    assertTrue(
        session.usePassword(password -> assertArrayEquals(expected.toCharArray(), password)));
  }

  @Test
  public void decryptsActualWebCryptoVectorWithUnicodePasswordAndBlankLines() {
    assertEquals(CLEAR, InlineCrypto.decrypt(VECTOR, PASSWORD.toCharArray()));
  }

  @Test
  public void encryptsExactlyLikeThePluginAtTheByteLevel() {
    byte[] iv = new byte[16], salt = new byte[16];
    for (int i = 0; i < 16; i++) {
      iv[i] = (byte) (16 + i);
      salt[i] = (byte) i;
    }
    assertEquals(VECTOR, InlineCrypto.encrypt(CLEAR, PASSWORD.toCharArray(), iv, salt));
  }

  @Test
  public void randomizedEncryptionAndEmptyUtf8RoundTrip() {
    var a = InlineCrypto.encrypt("", PASSWORD.toCharArray());
    var b = InlineCrypto.encrypt("", PASSWORD.toCharArray());
    assertNotEquals(a, b);
    assertEquals("", InlineCrypto.decrypt(a, PASSWORD.toCharArray()));
    assertEquals(48, Base64.getDecoder().decode(a).length);
  }

  @Test
  public void wrongPasswordAndTamperedIvSaltCiphertextOrTagNeverReturnPlaintext() {
    assertThrows(
        IllegalArgumentException.class, () -> InlineCrypto.decrypt(VECTOR, "wrong".toCharArray()));
    for (int offset : new int[] {0, 16, 35, Base64.getDecoder().decode(VECTOR).length - 1}) {
      byte[] payload = Base64.getDecoder().decode(VECTOR);
      payload[offset] ^= 1;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              InlineCrypto.decrypt(
                  Base64.getEncoder().encodeToString(payload), PASSWORD.toCharArray()));
    }
    for (String payload : List.of("!", "", Base64.getEncoder().encodeToString(new byte[47])))
      assertThrows(
          IllegalArgumentException.class,
          () -> InlineCrypto.decrypt(payload, PASSWORD.toCharArray()));
  }

  @Test
  public void passwordIsStoredOnlyInAuthenticatedWrappedForm() {
    String wrapped = PasswordSession.create(PASSWORD, "0123");
    assertFalse(wrapped.contains(PASSWORD));
    var session = new PasswordSession(() -> 0);
    session.unlock(wrapped, "0123");
    assertCachedPassword(session, PASSWORD);
    assertThrows(IllegalArgumentException.class, () -> session.unlock(wrapped, "0124"));
    session.close();
    assertFalse(session.isUnlocked());
  }

  @Test
  public void sessionExpiresAtExactlyThreeMinutesAndUseDoesNotExtendIt() {
    AtomicLong time = new AtomicLong(100);
    var s = new PasswordSession(time::get);
    s.unlock(PasswordSession.create(PASSWORD, "1234"), "1234");
    time.addAndGet(179999);
    assertTrue(s.isUnlocked());
    assertEquals(1, s.remaining());
    time.incrementAndGet();
    assertFalse(s.isUnlocked());
    assertEquals(0, s.remaining());
  }

  @Test
  public void bothPasswordsMustBeNonempty() {
    assertThrows(IllegalArgumentException.class, () -> PasswordSession.create("password", ""));
    assertThrows(IllegalArgumentException.class, () -> PasswordSession.create("", "unlock"));
    assertThrows(IllegalArgumentException.class, () -> new PasswordSession(() -> 0).unlock("", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> PasswordSession.change(LEGACY_WRAPPED, "0123", "", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> PasswordSession.change(LEGACY_WRAPPED, "0123", "", false));
  }

  // Fixed synthetic envelope produced with the old four-digit credential and existing wire format.
  private static final String LEGACY_WRAPPED =
      "EBESExQVFhcYGRobHB0eHwABAgMEBQYHCAkKCwwNDg8q4xjxH8s3NYo/WJbKzfrMPhUudADf5mZ5sddoRShD0CwefAI6";
  private static final String LEGACY_MASTER = "Fixture legacy master";

  @Test
  public void pincodeCanBeTextUnicodeShortOrLongWithoutShapeRestrictions() {
    for (String unlock :
        List.of("x", "a123", "１２３４", "слово 🌲 e\u0301", " leading trailing ", "z".repeat(256))) {
      String wrapped = PasswordSession.create(PASSWORD, unlock);
      try (var session = new PasswordSession(() -> 0)) {
        session.unlock(wrapped, unlock);
        assertCachedPassword(session, PASSWORD);
      }
    }
  }

  @Test
  public void legacyFourDigitWrappedPasswordStillUnlocks() {
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(LEGACY_WRAPPED, "0123");
      assertCachedPassword(session, LEGACY_MASTER);
    }
  }

  @Test
  public void changingPincodeKeepsEncryptionPasswordAndRejectsTheOldKey() {
    String key = "New access phrase!2026";
    String changed = PasswordSession.change(LEGACY_WRAPPED, "0123", key, true);
    assertNotEquals(LEGACY_WRAPPED, changed);
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(changed, key);
      assertCachedPassword(session, LEGACY_MASTER);
      assertThrows(IllegalArgumentException.class, () -> session.unlock(changed, "0123"));
    }
  }

  @Test
  public void changingEncryptionPasswordKeepsTheSamePincode() {
    String changed = PasswordSession.change(LEGACY_WRAPPED, "0123", "New master 🌲", false);
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(changed, "0123");
      assertCachedPassword(session, "New master 🌲");
    }
    assertEquals(LEGACY_MASTER, InlineCrypto.decrypt(LEGACY_WRAPPED, "0123".toCharArray()));
  }

  @Test
  public void activeSessionCannotBypassTheCurrentPincodeForCredentialChanges() {
    try (var session = new PasswordSession(() -> 0)) {
      session.unlock(LEGACY_WRAPPED, "0123");
      assertTrue(session.isUnlocked());
      assertThrows(
          IllegalArgumentException.class,
          () -> PasswordSession.change(LEGACY_WRAPPED, "wrong current", "New unlock", true));
      assertThrows(
          IllegalArgumentException.class,
          () -> PasswordSession.change(LEGACY_WRAPPED, "wrong current", "New master", false));
      assertCachedPassword(session, LEGACY_MASTER);
    }
  }

  @Test
  public void pincodeIsUsedExactlyWithoutTrimmingOrUnicodeNormalization() {
    String key = " secret e\u0301 ";
    String wrapped = PasswordSession.create(PASSWORD, key);
    try (var session = new PasswordSession(() -> 0)) {
      for (String other : List.of(key.strip(), key.toUpperCase(Locale.ROOT), " secret é "))
        assertThrows(IllegalArgumentException.class, () -> session.unlock(wrapped, other));
      session.unlock(wrapped, key);
      assertCachedPassword(session, PASSWORD);
    }
  }
}
