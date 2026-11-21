package com.aresstack.spaceharvester.application.changelog;

import java.util.Objects;

/**
 * Eine Seite, auf die sich eine Aussage der Zusammenfassung stützt.
 *
 * @param title    jüngster bekannter Titel, {@code null} wenn unbekannt
 * @param spaceKey Space der Seite, {@code null} wenn unbekannt
 */
public record SummaryPage(String pageId, String title, String spaceKey) {

    public SummaryPage {
        Objects.requireNonNull(pageId, "pageId");
    }

    /** Ohne bekannten Space. */
    public SummaryPage(String pageId, String title) {
        this(pageId, title, null);
    }
}
