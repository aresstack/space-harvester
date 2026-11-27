package com.aresstack.askai.research.search.security;

public final class EncryptedSecret {

    private int version = 1;
    private String algorithm;
    private String initializationVector;
    private String cipherText;

    public EncryptedSecret() {
    }

    public EncryptedSecret(
            int version,
            String algorithm,
            String initializationVector,
            String cipherText) {

        this.version = version;
        this.algorithm = algorithm;
        this.initializationVector = initializationVector;
        this.cipherText = cipherText;
    }

    public int getVersion() {
        return version;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getInitializationVector() {
        return initializationVector;
    }

    public String getCipherText() {
        return cipherText;
    }

    public boolean isPresent() {
        return algorithm != null
                && initializationVector != null
                && cipherText != null;
    }
}
