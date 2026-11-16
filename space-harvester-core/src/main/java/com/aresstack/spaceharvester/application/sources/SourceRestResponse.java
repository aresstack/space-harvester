package com.aresstack.spaceharvester.application.sources;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Antwort einer REST-Abfrage an die Quelle ({@link HarvestSource#restGet}): Medientyp und Körper, wie geliefert.
 *
 * @param contentType {@code Content-Type} der Antwort, möglicherweise leer
 * @param body        Körper Byte für Byte
 */
public record SourceRestResponse(String contentType, byte[] body) {

    public SourceRestResponse {
        contentType = contentType == null ? "" : contentType;
        body = Objects.requireNonNull(body, "body").clone();
    }

    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean json() {
        return contentType.toLowerCase(java.util.Locale.ROOT).contains("json");
    }
}
