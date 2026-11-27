package com.aresstack.askai.research.search.http;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.asynchttpclient.BoundRequestBuilder;

/**
 * HTTP Basic authentication with a ready-made API key: the stored secret already is the Base64 token
 * (DataForSEO's "API key" = Base64(login:password)) and is sent unchanged as {@code Basic <key>}.
 */
public final class EncryptedBasicApiKeyAuthentication
        implements RequestAuthentication {

    private final EncryptedSecret encryptedApiKey;
    private final SecretValueService secretValueService;

    public EncryptedBasicApiKeyAuthentication(
            EncryptedSecret encryptedApiKey,
            SecretValueService secretValueService) {

        if (encryptedApiKey == null || !encryptedApiKey.isPresent()) {
            throw new IllegalArgumentException("encryptedApiKey must be present");
        }
        if (secretValueService == null) {
            throw new IllegalArgumentException("secretValueService must not be null");
        }
        this.encryptedApiKey = encryptedApiKey;
        this.secretValueService = secretValueService;
    }

    @Override
    public void apply(BoundRequestBuilder requestBuilder) {
        if (requestBuilder == null) {
            throw new IllegalArgumentException("requestBuilder must not be null");
        }
        char[] key = secretValueService.decrypt(encryptedApiKey);
        try {
            requestBuilder.setHeader("Authorization", "Basic " + new String(key).trim());
        } finally {
            SecretArrays.clear(key);
        }
    }
}
