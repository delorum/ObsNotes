package ru.obsnotes;

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
    byte[] iv = new byte[16], salt = new byte[16];
    RANDOM.nextBytes(iv);
    RANDOM.nextBytes(salt);
    return encrypt(text, password, iv, salt);
  }

  static String encrypt(String text, char[] password, byte[] iv, byte[] salt) {
    byte[] key = null, plain = text.getBytes(StandardCharsets.UTF_8);
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
      Arrays.fill(plain, (byte) 0);
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  // Android's default Conscrypt GCM requires a 12-byte IV; the Obsidian format uses 16.
  private static byte[] gcm(
      boolean encrypt, byte[] key, byte[] iv, byte[] input, int offset, int length)
      throws GeneralSecurityException {
    var cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
    cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, iv));
    byte[] output = new byte[cipher.getOutputSize(length)];
    try {
      int count = cipher.processBytes(input, offset, length, output, 0);
      count += cipher.doFinal(output, count);
      return count == output.length ? output : Arrays.copyOf(output, count);
    } catch (InvalidCipherTextException error) {
      Arrays.fill(output, (byte) 0);
      throw new GeneralSecurityException(error);
    }
  }

  public static String decrypt(String encoded, char[] password) {
    byte[] key = null, plain = null;
    try {
      byte[] payload = Base64.getDecoder().decode(encoded);
      if (payload.length < 48) throw new GeneralSecurityException("Truncated secret");
      key = key(password, Arrays.copyOfRange(payload, 16, 32));
      plain = gcm(false, key, Arrays.copyOf(payload, 16), payload, 32, payload.length - 32);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Не удалось расшифровать: неверный пароль или повреждённый secret", e);
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
      if (plain != null) Arrays.fill(plain, (byte) 0);
    }
  }
}
