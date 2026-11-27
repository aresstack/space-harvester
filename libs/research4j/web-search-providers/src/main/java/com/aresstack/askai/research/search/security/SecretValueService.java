package com.aresstack.askai.research.search.security;

public final class SecretValueService {

    private final SecretCipher secretCipher;

    public SecretValueService(SecretCipher secretCipher) {
        if (secretCipher == null) {
            throw new IllegalArgumentException(
                    "secretCipher must not be null");
        }
        this.secretCipher = secretCipher;
    }

    public EncryptedSecret encrypt(char[] value) {
        return secretCipher.encrypt(value);
    }

    public char[] decrypt(EncryptedSecret value) {
        return secretCipher.decrypt(value);
    }
}
