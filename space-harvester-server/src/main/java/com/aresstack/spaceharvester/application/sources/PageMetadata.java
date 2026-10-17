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
        attributes = natural(attributes);
    }

    @Override
    public SortedMap<String, String> attributes() {
        return natural(attributes);
    }

    /**
     * {@code false}, wenn die Quelle ein Attribut nur unvollständig geliefert hat. Adapter melden das mit einem
     * Attribut {@code <name>.complete=false} (bei Confluence {@code confluence.labels.complete}, wenn
     * {@code metadata.labels} gekürzt war). Vollständige Attribute tragen keinen solchen Eintrag, deshalb bleibt
     * {@code attributesHash} ({@code page-attributes/v1}) für sie unverändert. Bei unvollständigen Attributen ist der
     * Hash kein verlässlicher Änderungsnachweis.
     */
    public boolean attributesComplete() {
        return attributes.entrySet().stream()
                .noneMatch(entry -> entry.getKey().endsWith(".complete") && "false".equals(entry.getValue()));
    }

    /** Kopie mit natürlicher Schlüsselordnung, unabhängig vom Comparator der übergebenen Map. */
    private static SortedMap<String, String> natural(Map<String, String> attributes) {
        SortedMap<String, String> copy = new TreeMap<>();
        if (attributes != null) {
            copy.putAll(attributes);
        }
        return copy;
    }

    /** Für Tests und Adapter: Attribute aus einer beliebigen Map. */
    public static SortedMap<String, String> sorted(Map<String, String> attributes) {
        return new TreeMap<>(attributes);
    }
}
