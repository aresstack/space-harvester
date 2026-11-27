package com.aresstack.askai.research.search.http;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.asynchttpclient.BoundRequestBuilder;

public class EncryptedHeaderAuthentication
        implements RequestAuthentication {

    private final String headerName;
    private final String valuePrefix;
    private final EncryptedSecret encryptedSecret;
    private final SecretValueService secretValueService;

    public EncryptedHeaderAuthentication(
            String headerName,
            String valuePrefix,
            EncryptedSecret encryptedSecret,
            SecretValueService secretValueService) {

        this.headerName = requireText(
                headerName,
                "headerName");
        this.valuePrefix =
                valuePrefix == null ? "" : valuePrefix;
        this.encryptedSecret = requireSecret(encryptedSecret);
        this.secretValueService = requireNonNull(
                secretValueService,
                "secretValueService");
    }

    @Override
    public void apply(BoundRequestBuilder requestBuilder) {
        if (requestBuilder == null) {
            throw new IllegalArgumentException(
                    "requestBuilder must not be null");
        }

        char[] secret =
                secretValueService.decrypt(encryptedSecret);

        try {
            requestBuilder.setHeader(
                    headerName,
                    valuePrefix + new String(secret));
        } finally {
            SecretArrays.clear(secret);
        }
    }

    private static EncryptedSecret requireSecret(
            EncryptedSecret encryptedSecret) {

        if (encryptedSecret == null
                || !encryptedSecret.isPresent()) {

            throw new IllegalArgumentException(
                    "encryptedSecret must be present");
        }
        return encryptedSecret;
    }

    private static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
