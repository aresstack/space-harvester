package com.aresstack.spaceharvester.application.snapshot;

import java.util.Objects;

/**
 * Identität einer Seite im Harvester: Quellen-ID und Seiten-ID der Quelle (bei Confluence die Content-ID). Die
 * Seiten-ID bleibt beim Verschieben in einen anderen Space gleich; der Space ist daher kein Teil des Schlüssels.
 */
public record PageKey(String sourceId, String pageId) {

    public PageKey {
        requireText(sourceId, "sourceId");
        requireText(pageId, "pageId");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
    }
}
