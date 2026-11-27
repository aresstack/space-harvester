package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.aresstack.serp4j.core.port.credential.CredentialKey;
import com.aresstack.serp4j.core.port.credential.CredentialProvider;
import com.aresstack.serp4j.core.port.credential.SecretValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Serp4J {@link CredentialProvider} backed by AskAI's own secret storage. Configuration and AES-GCM-encrypted
 * secrets stay in AskAI (the host); Serp4J only asks for a secret by {@link CredentialKey} at search time and
 * receives a fresh {@link SecretValue} that the provider closes after use. Nothing is decrypted up front and
 * no plaintext is kept in this object.
 */
public final class HostCredentialProvider implements CredentialProvider {

    private final SecretValueService secretValueService;
    private final Map<String, EncryptedSecret> encrypted;
    private final Map<String, String> plain;

    private HostCredentialProvider(Builder builder) {
        this.secretValueService = builder.secretValueService;
        this.encrypted = Collections.unmodifiableMap(new HashMap<String, EncryptedSecret>(builder.encrypted));
        this.plain = Collections.unmodifiableMap(new HashMap<String, String>(builder.plain));
    }

    public static Builder builder(SecretValueService secretValueService) {
        return new Builder(secretValueService);
    }

    @Override
    public Optional<SecretValue> resolve(CredentialKey key) {
        if (key == null) {
            return Optional.empty();
        }
        EncryptedSecret secret = encrypted.get(key.getName());
        if (secret != null) {
            char[] chars = secretValueService.decrypt(secret);
            if (chars == null || chars.length == 0) {
                return Optional.empty();
            }
            // SecretValue.of(char[]) takes ownership; the provider closes (and wipes) it after the request.
            return Optional.of(SecretValue.of(chars));
        }
        String value = plain.get(key.getName());
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(SecretValue.of(value.toCharArray()));
    }

    @Override
    public String toString() {
        return "HostCredentialProvider" + new java.util.TreeSet<String>(keys());
    }

    private java.util.Set<String> keys() {
        java.util.Set<String> names = new java.util.HashSet<String>(encrypted.keySet());
        names.addAll(plain.keySet());
        return names;
    }

    public static final class Builder {
        private final SecretValueService secretValueService;
        private final Map<String, EncryptedSecret> encrypted = new HashMap<String, EncryptedSecret>();
        private final Map<String, String> plain = new HashMap<String, String>();

        private Builder(SecretValueService secretValueService) {
            if (secretValueService == null) {
                throw new IllegalArgumentException("secretValueService must not be null");
            }
            this.secretValueService = secretValueService;
        }

        /** An AES-GCM-encrypted secret, decrypted only when Serp4J resolves {@code key}. */
        public Builder encrypted(CredentialKey key, EncryptedSecret secret) {
            if (secret != null && secret.isPresent()) {
                encrypted.put(key.getName(), secret);
            }
            return this;
        }

        /** A non-secret identifier that Serp4J still resolves through the credential port (e.g. a login name). */
        public Builder plain(CredentialKey key, String value) {
            if (value != null) {
                plain.put(key.getName(), value);
            }
            return this;
        }

        public HostCredentialProvider build() {
            return new HostCredentialProvider(this);
        }
    }
}
