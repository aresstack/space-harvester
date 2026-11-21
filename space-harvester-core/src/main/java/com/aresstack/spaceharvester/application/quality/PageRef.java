package com.aresstack.spaceharvester.application.quality;

import java.util.Objects;

/**
 * Eine Seite einer Quelle, geschrieben {@code quelle:pageId}.
 *
 * @param sourceId ID der Wissensquelle
 * @param pageId   ID der Seite in der Quelle
 */
public record PageRef(String sourceId, String pageId) {

    public PageRef {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        if (sourceId.isBlank() || pageId.isBlank()) {
            throw new IllegalArgumentException("Quelle und Seite dürfen nicht leer sein");
        }
    }

    @Override
    public String toString() {
        return sourceId + ":" + pageId;
    }
}
