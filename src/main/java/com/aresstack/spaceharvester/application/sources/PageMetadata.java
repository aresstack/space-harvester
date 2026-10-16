package com.aresstack.spaceharvester.application.sources;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Metadaten einer Seite ohne Inhalt, wie sie die Quelle beim Inventarisieren liefert.
 *
 * @param pageId       ID der Seite in der Quelle (bei Confluence die Content-ID), eindeutig nur zusammen mit der
 *                     Quellen-ID
 * @param resourceId   quellübergreifend eindeutige Ressourcen-ID, z. B. {@code confluence:wiki.example.org/page/123}
 * @param title        Titel
 * @param partitionKey Schlüssel der Partition (bei Confluence der Space-Key)
 * @param parentPageId ID der Elternseite oder {@code null} für eine Wurzelseite
 * @param version      Versionskennung der Quelle oder {@code null}, wenn unbekannt
 * @param modifiedAt   Zeitpunkt der letzten Änderung oder {@code null}, wenn unbekannt
 * @param url          Adresse der Seite in der Quelle oder {@code null}, wenn unbekannt
 * @param attributes   weitere Metadaten der Quelle (bei Confluence etwa Labels), nach Schlüssel sortiert
 */
public record PageMetadata(String pageId, String resourceId, String title, String partitionKey, String parentPageId,
                           String version, Instant modifiedAt, URI url, SortedMap<String, String> attributes) {

    public PageMetadata {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(resourceId, "resourceId");
        title = title == null ? "" : title;
        Objects.requireNonNull(partitionKey, "partitionKey");
        attributes = attributes == null ? new TreeMap<>() : new TreeMap<>(attributes);
    }

    @Override
    public SortedMap<String, String> attributes() {
        return new TreeMap<>(attributes);
    }

    /** Für Tests und Adapter: Attribute aus einer beliebigen Map. */
    public static SortedMap<String, String> sorted(Map<String, String> attributes) {
        return new TreeMap<>(attributes);
    }
}
