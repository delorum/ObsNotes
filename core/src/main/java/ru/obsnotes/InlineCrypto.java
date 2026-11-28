package ru.obsnotes;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/** Wire format of the vault's inline-encrypter CryptoFactory (WebCrypto). */
public final class InlineCrypto {
  private InlineCrypto() {}

  public static final int ITERATIONS = 262144;
  private static final SecureRandom RANDOM = new SecureRandom();

  private static byte[] key(char[] password, byte[] salt) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }

  public static String encrypt(String text, char[] password) {
    byte[] plain = text.getBytes(StandardCharsets.UTF_8);
    try {
      return encryptBytes(plain, password);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  // The caller owns and wipes the plaintext.
  static String encryptBytes(byte[] plain, char[] password) {
    byte[] iv = new byte[16], salt = new byte[16];
    RANDOM.nextBytes(iv);
    RANDOM.nextBytes(salt);
    return encryptBytes(plain, password, iv, salt);
  }

  static String encrypt(String text, char[] password, byte[] iv, byte[] salt) {
    byte[] plain = text.getBytes(StandardCharsets.UTF_8);
    try {
      return encryptBytes(plain, password, iv, salt);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  private static String encryptBytes(byte[] plain, char[] password, byte[] iv, byte[] salt) {
    byte[] key = null;
    try {
      key = key(password, salt);
      byte[] encrypted = gcm(true, key, iv, plain, 0, plain.length),
          payload = new byte[32 + encrypted.length];
      System.arraycopy(iv, 0, payload, 0, 16);
      System.arraycopy(salt, 0, payload, 16, 16);
      System.arraycopy(encrypted, 0, payload, 32, encrypted.length);
      return Base64.getEncoder().encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Не удалось зашифровать текст", e);
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  // Android's default Conscrypt GCM requires a 12-byte IV; the Obsidian format uses 16.
  private static byte[] gcm(
      boolean encrypt, byte[] key, byte[] iv, byte[] input, int offset, int length)
      throws GeneralSecurityException {
    var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
    var keyParameter = new KeyParameter(key);
    byte[] output = null;
    boolean returned = false;
    try {
      cipher.init(encrypt, new AEADParameters(keyParameter, 128, iv));
      output = new byte[cipher.getOutputSize(length)];
      int count = cipher.processBytes(input, offset, length, output, 0);
      count += cipher.doFinal(output, count);
      if (count != output.length) return Arrays.copyOf(output, count);
      returned = true;
      return output;
    } catch (InvalidCipherTextException error) {
      throw new GeneralSecurityException(error);
    } finally {
      Arrays.fill(keyParameter.getKey(), (byte) 0);
      if (!returned && output != null) Arrays.fill(output, (byte) 0);
    }
  }

  public static String decrypt(String encoded, char[] password) {
    byte[] plain = decryptBytes(encoded, password);
    try {
      return new String(plain, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  /** Transfer the decoded password to the caller without creating immutable plaintext. */
  static char[] decryptChars(String encoded, char[] password) {
    byte[] plain = decryptBytes(encoded, password);
    char[] scratch = null;
    try {
      // UTF-8 never needs more UTF-16 code units than input bytes.
      scratch = new char[plain.length];
      var chars = CharBuffer.wrap(scratch);
      var decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE);
      decoder.decode(ByteBuffer.wrap(plain), chars, true);
      decoder.flush(chars);
      if (chars.position() != scratch.length) return Arrays.copyOf(scratch, chars.position());
      char[] result = scratch;
      scratch = null;
      return result;
    } finally {
      Arrays.fill(plain, (byte) 0);
      if (scratch != null) Arrays.fill(scratch, '\0');
    }
  }

  /** Transfer the authenticated plaintext buffer to the caller, who must wipe it. */
  static byte[] decryptBytes(String encoded, char[] password) {
    byte[] key = null;
    try {
      byte[] payload = Base64.getDecoder().decode(encoded);
      if (payload.length < 48) throw new GeneralSecurityException("Truncated secret");
      key = key(password, Arrays.copyOfRange(payload, 16, 32));
      return gcm(false, key, Arrays.copyOf(payload, 16), payload, 32, payload.length - 32);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Не удалось расшифровать: неверный пароль или повреждённый secret", e);
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }
}
