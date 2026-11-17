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
  public void passwordIsStoredOnlyInAuthenticatedPinEncryptedForm() {
    String wrapped = PasswordSession.create(PASSWORD, "0123");
    assertFalse(wrapped.contains(PASSWORD));
    var session = new PasswordSession(() -> 0);
    session.unlock(wrapped, "0123");
    assertArrayEquals(PASSWORD.toCharArray(), session.copy());
    assertThrows(IllegalArgumentException.class, () -> session.unlock(wrapped, "0124"));
    session.close();
    assertNull(session.copy());
  }

  @Test
  public void sessionExpiresAtExactlyThreeMinutesAndUseDoesNotExtendIt() {
    AtomicLong time = new AtomicLong(100);
    var s = new PasswordSession(time::get);
    s.unlock(PasswordSession.create(PASSWORD, "1234"), "1234");
    time.addAndGet(179999);
    assertNotNull(s.copy());
    assertEquals(1, s.remaining());
    time.incrementAndGet();
    assertNull(s.copy());
    assertEquals(0, s.remaining());
  }

  @Test
  public void pinIsExactlyFourAsciiDigitsAndPasswordCannotBeEmpty() {
    for (String pin : List.of("123", "12345", "１２３４", "a123", "12 3"))
      assertThrows(IllegalArgumentException.class, () -> PasswordSession.create("password", pin));
    assertThrows(IllegalArgumentException.class, () -> PasswordSession.create("", "1234"));
  }
}
