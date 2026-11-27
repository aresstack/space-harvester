package com.aresstack.askai.research.search.security;

import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

public final class AesGcmSecretCipherTest {

    @Test
    public void encryptAndDecryptSecret() {
        SecretKey key = new SecretKeySpec(
                new byte[16],
                "AES");
        AesGcmSecretCipher cipher =
                new AesGcmSecretCipher(
                        new FixedSecretKeyProvider(key));

        char[] plainText = "top-secret".toCharArray();
        EncryptedSecret encrypted = cipher.encrypt(plainText);
        char[] decrypted = cipher.decrypt(encrypted);

        assertFalse(
                encrypted.getCipherText().contains(
                        "top-secret"));
        assertArrayEquals(plainText, decrypted);

        SecretArrays.clear(plainText);
        SecretArrays.clear(decrypted);
    }

    private static final class FixedSecretKeyProvider
            implements SecretKeyProvider {

        private final SecretKey key;

        private FixedSecretKeyProvider(SecretKey key) {
            this.key = key;
        }

        @Override
        public SecretKey getOrCreateKey() {
            return key;
        }
    }
}
