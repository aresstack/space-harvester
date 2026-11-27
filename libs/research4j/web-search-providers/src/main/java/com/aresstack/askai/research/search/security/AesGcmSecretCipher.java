package com.aresstack.askai.research.search.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmSecretCipher
        implements SecretCipher {

    public static final String ALGORITHM =
            "AES/GCM/NoPadding";

    private static final int VERSION = 1;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeyProvider keyProvider;
    private final SecureRandom secureRandom;

    public AesGcmSecretCipher(
            SecretKeyProvider keyProvider) {

        if (keyProvider == null) {
            throw new IllegalArgumentException(
                    "keyProvider must not be null");
        }

        this.keyProvider = keyProvider;
        this.secureRandom = new SecureRandom();
    }

    @Override
    public EncryptedSecret encrypt(char[] plainText) {
        if (plainText == null || plainText.length == 0) {
            throw new IllegalArgumentException(
                    "plainText must not be empty");
        }

        byte[] plainBytes = encode(plainText);
        byte[] initializationVector =
                new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(initializationVector);

        try {
            Cipher cipher = createCipher(
                    Cipher.ENCRYPT_MODE,
                    initializationVector);

            byte[] cipherText =
                    cipher.doFinal(plainBytes);

            return new EncryptedSecret(
                    VERSION,
                    ALGORITHM,
                    Base64.getEncoder().encodeToString(
                            initializationVector),
                    Base64.getEncoder().encodeToString(
                            cipherText));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Could not encrypt secret",
                    exception);
        } finally {
            SecretArrays.clear(plainBytes);
            SecretArrays.clear(initializationVector);
        }
    }

    @Override
    public char[] decrypt(
            EncryptedSecret encryptedSecret) {

        requireSupported(encryptedSecret);

        byte[] initializationVector =
                Base64.getDecoder().decode(
                        encryptedSecret
                                .getInitializationVector());

        byte[] cipherText =
                Base64.getDecoder().decode(
                        encryptedSecret.getCipherText());

        byte[] plainBytes = null;

        try {
            Cipher cipher = createCipher(
                    Cipher.DECRYPT_MODE,
                    initializationVector);

            plainBytes = cipher.doFinal(cipherText);
            return decode(plainBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Could not decrypt secret",
                    exception);
        } finally {
            SecretArrays.clear(initializationVector);
            SecretArrays.clear(cipherText);
            SecretArrays.clear(plainBytes);
        }
    }

    private Cipher createCipher(
            int mode,
            byte[] initializationVector)
            throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(
                mode,
                keyProvider.getOrCreateKey(),
                new GCMParameterSpec(
                        TAG_LENGTH_BITS,
                        initializationVector));
        return cipher;
    }

    private void requireSupported(
            EncryptedSecret encryptedSecret) {

        if (encryptedSecret == null
                || !encryptedSecret.isPresent()) {

            throw new IllegalArgumentException(
                    "encryptedSecret must be present");
        }

        if (encryptedSecret.getVersion() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported encrypted secret version: "
                            + encryptedSecret.getVersion());
        }

        if (!ALGORITHM.equals(
                encryptedSecret.getAlgorithm())) {

            throw new IllegalArgumentException(
                    "Unsupported encryption algorithm: "
                            + encryptedSecret.getAlgorithm());
        }
    }

    private byte[] encode(char[] characters) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8
                    .newEncoder()
                    .encode(CharBuffer.wrap(characters));

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "Secret is not valid UTF-8",
                    exception);
        }
    }

    private char[] decode(byte[] bytes) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes));

            char[] characters =
                    new char[buffer.remaining()];
            buffer.get(characters);
            return characters;
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(
                    "Decrypted secret is not valid UTF-8",
                    exception);
        }
    }
}
