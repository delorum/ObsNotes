package ru.obsnotes;

import android.security.keystore.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Protects internal recovery snapshots containing secret contexts, not Markdown vault files. */
final class RecoveryCipher {
  private static final String ALIAS = "obsnotes-editor-recovery-v1";

  private SecretKey key() throws GeneralSecurityException {
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build());
      return generator.generateKey();
    } catch (java.io.IOException e) {
      throw new GeneralSecurityException(e);
    }
  }

  String seal(String json) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
    byte[] payload = new byte[12 + encrypted.length];
    System.arraycopy(cipher.getIV(), 0, payload, 0, 12);
    System.arraycopy(encrypted, 0, payload, 12, encrypted.length);
    return Base64.getEncoder().encodeToString(payload);
  }

  String open(String base64) throws GeneralSecurityException {
    byte[] payload = Base64.getDecoder().decode(base64);
    if (payload.length < 28) throw new GeneralSecurityException("Truncated recovery snapshot");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
    byte[] plain = cipher.doFinal(payload, 12, payload.length - 12);
    try {
      return new String(plain, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }
}
