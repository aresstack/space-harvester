package com.aresstack.spaceharvester.application.sources;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Ein Treffer der quelleneigenen Suche ({@link HarvestSource#searchPages}), so wie die Quelle ihn liefert: ohne
 * Inhalt und ohne Rechteprüfung für einen bestimmten Aufrufer.
 *
 * @param pageId     ID der Seite in der Quelle
 * @param title      Titel laut Suche, leer, wenn unbekannt
 * @param snippet    Textauszug als Klartext ohne Markup, leer, wenn die Quelle keinen liefert
 * @param url        Adresse der Seite oder {@code null}
 * @param version    Version laut Suche oder {@code null}
 * @param modifiedAt letzte Änderung laut Suche oder {@code null}
 */
public record SourceSearchMatch(String pageId, String title, String snippet, URI url, String version,
                                Instant modifiedAt) {

    public SourceSearchMatch {
        Objects.requireNonNull(pageId, "pageId");
        title = title == null ? "" : title;
        snippet = snippet == null ? "" : snippet;
    }
}
