package com.aresstack.enterpriseai.embedding.openai;

/** Status und UTF-8-dekodierter Body einer Antwort von {@link EmbeddingHttpTransport}. */
final class HttpResult {

    final int status;
    final String body;
    /** {@code Retry-After} der Antwort in Millisekunden; {@code -1}, wenn der Dienst keine (Sekunden-)Angabe macht. */
    final long retryAfterMillis;

    HttpResult(int status, String body) {
        this(status, body, -1L);
    }

    HttpResult(int status, String body, long retryAfterMillis) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.retryAfterMillis = retryAfterMillis;
    }

    /**
     * Liest einen {@code Retry-After}-Header in Sekunden ({@code "2"}); ein HTTP-Datum oder Unlesbares gilt als
     * fehlend ({@code -1}).
     */
    static long retryAfterMillis(String header) {
        if (header == null) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? -1L : Math.min(seconds, 3600L) * 1000L;
        } catch (NumberFormatException notSeconds) {
            return -1L;
        }
    }
}
