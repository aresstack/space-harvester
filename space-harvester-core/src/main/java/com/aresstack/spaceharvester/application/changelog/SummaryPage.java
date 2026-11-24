package com.aresstack.spaceharvester.application.changelog;

import java.util.Objects;

/**
 * Eine Seite, auf die sich eine Aussage der Zusammenfassung stützt.
 *
 * @param title    jüngster bekannter Titel, {@code null} wenn unbekannt
 * @param spaceKey Space der Seite, {@code null} wenn unbekannt
 * @param sourceId Quelle der Seite; {@code null} heißt die Quelle der Zusammenfassung (bei Updates immer). Eine
 *                 Bereichszusammenfassung über mehrere Quellen (etwa ein Favoriten-Ordner, ADR 0034) nennt sie je Seite.
 */
public record SummaryPage(String pageId, String title, String spaceKey, String sourceId) {

    public SummaryPage {
        Objects.requireNonNull(pageId, "pageId");
    }

    /** Eine Seite aus der Quelle der Zusammenfassung. */
    public SummaryPage(String pageId, String title, String spaceKey) {
        this(pageId, title, spaceKey, null);
    }

    /** Ohne bekannten Space. */
    public SummaryPage(String pageId, String title) {
        this(pageId, title, null);
    }

    /** Die Quelle der Seite, ohne eigene Angabe die der Zusammenfassung. */
    public String sourceIdOr(String summarySourceId) {
        return sourceId != null ? sourceId : summarySourceId;
    }
}
