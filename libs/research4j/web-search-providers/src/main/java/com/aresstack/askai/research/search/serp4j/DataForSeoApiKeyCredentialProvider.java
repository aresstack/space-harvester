package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.aresstack.serp4j.core.port.credential.CredentialKey;
import com.aresstack.serp4j.core.port.credential.CredentialProvider;
import com.aresstack.serp4j.core.port.credential.SecretValue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Serp4J credentials for DataForSEO from ONE stored API key. DataForSEO's API key is Base64(login:password),
 * i.e. exactly the HTTP Basic token. Serp4J's provider asks for login and password separately and builds the
 * same header again, so the key is decrypted and split here, per request, in memory only. Nothing is logged
 * and every intermediate array is wiped.
 */
final class DataForSeoApiKeyCredentialProvider implements CredentialProvider {

    private final SecretValueService secretValueService;
    private final EncryptedSecret apiKey;
    private final CredentialKey loginKey;
    private final CredentialKey passwordKey;

    DataForSeoApiKeyCredentialProvider(SecretValueService secretValueService, EncryptedSecret apiKey,
            CredentialKey loginKey, CredentialKey passwordKey) {
        if (secretValueService == null) {
            throw new IllegalArgumentException("secretValueService must not be null");
        }
        this.secretValueService = secretValueService;
        this.apiKey = apiKey;
        this.loginKey = loginKey;
        this.passwordKey = passwordKey;
    }

    @Override
    public Optional<SecretValue> resolve(CredentialKey key) {
        if (key == null || apiKey == null || !apiKey.isPresent()) {
            return Optional.empty();
        }
        boolean login = key.getName().equals(loginKey.getName());
        if (!login && !key.getName().equals(passwordKey.getName())) {
            return Optional.empty();
        }
        char[] decoded = decode(secretValueService.decrypt(apiKey));
        try {
            int colon = indexOf(decoded, ':');
            if (colon <= 0) {
                return Optional.empty(); // not Base64(login:password): the provider reports missing credentials
            }
            char[] part = login ? copy(decoded, 0, colon) : copy(decoded, colon + 1, decoded.length);
            return part.length == 0 ? Optional.<SecretValue>empty() : Optional.of(SecretValue.of(part));
        } finally {
            SecretArrays.clear(decoded);
        }
    }

    /** Base64 → UTF-8 chars; wipes the input. Returns an empty array for anything that is not Base64. */
    static char[] decode(char[] base64) {
        byte[] ascii = null;
        byte[] raw = null;
        try {
            if (base64 == null) {
                return new char[0];
            }
            int start = 0;
            int end = base64.length;
            while (start < end && Character.isWhitespace(base64[start])) {
                start++;
            }
            while (end > start && Character.isWhitespace(base64[end - 1])) {
                end--;
            }
            ascii = new byte[end - start];
            for (int i = start; i < end; i++) {
                ascii[i - start] = (byte) base64[i];
            }
            try {
                raw = Base64.getDecoder().decode(ascii);
            } catch (IllegalArgumentException notBase64) {
                return new char[0];
            }
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(raw));
            char[] result = new char[chars.remaining()];
            chars.get(result);
            if (chars.hasArray()) {
                SecretArrays.clear(chars.array());
            }
            return result;
        } finally {
            SecretArrays.clear(base64);
            SecretArrays.clear(ascii);
            SecretArrays.clear(raw);
        }
    }

    private static int indexOf(char[] chars, char c) {
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static char[] copy(char[] source, int from, int to) {
        char[] out = new char[to - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    @Override
    public String toString() {
        return "DataForSeoApiKeyCredentialProvider";
    }
}
