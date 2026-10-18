package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;

/**
 * Bildet HTTP-Status auf portneutrale {@link EmbeddingFailureKind}s ab. Die Fehlermeldung enthält nur den Pfad des Endpunkts (ohne Host),
 * Status und gegebenenfalls einen maschinenlesbaren Fehlercode des Servers. Nie enthalten: Request-Header
 * (Bearer-Token), Request-Body und freier Antworttext, weil Validierungsfehler Eingabetexte zurückspiegeln können.
 */
final class EmbeddingErrorMapper {

    private EmbeddingErrorMapper() {
    }

    static EmbeddingFailureKind kindOf(int status) {
        if (status == 401 || status == 403) {
            return EmbeddingFailureKind.AUTHENTICATION;
        }
        if (status == 429) {
            return EmbeddingFailureKind.RATE_LIMITED;
        }
        if (status == 408) {
            return EmbeddingFailureKind.UNAVAILABLE;
        }
        if (status >= 500) {
            return EmbeddingFailureKind.PROVIDER_ERROR;
        }
        if (status >= 400) {
            return EmbeddingFailureKind.REJECTED;
        }
        // 1xx/3xx (Redirects folgt der Adapter bewusst nicht) und unerwartete 2xx
        return EmbeddingFailureKind.INVALID_RESPONSE;
    }

    static EmbeddingException fromStatus(String endpoint, int status, String body) {
        StringBuilder message = new StringBuilder("embedding endpoint ").append(endpoint)
                .append(" returned HTTP ").append(status);
        String code = EmbeddingResponseParser.errorCode(body);
        if (code != null) {
            message.append(" (").append(code).append(')');
        }
        return new EmbeddingException(kindOf(status), message.toString());
    }
}
