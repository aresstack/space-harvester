package com.aresstack.askai.research.search.http;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretValueService;

public final class EncryptedBearerAuthentication
        extends EncryptedHeaderAuthentication {

    public EncryptedBearerAuthentication(
            EncryptedSecret encryptedSecret,
            SecretValueService secretValueService) {

        super(
                "Authorization",
                "Bearer ",
                encryptedSecret,
                secretValueService);
    }
}
