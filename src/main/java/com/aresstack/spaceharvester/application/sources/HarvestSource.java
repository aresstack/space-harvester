package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Eine konfigurierte Wissensquelle aus Sicht der Anwendungsfälle, unabhängig von ihrem Typ (Confluence, später
 * weitere). Umgesetzt im Paket {@code source}.
 */
public interface HarvestSource {

    /** Quellen-ID aus der Konfiguration, z. B. {@code confluence}. */
    String id();

    /** Quellentyp, z. B. {@code confluence}. */
    String type();

    /** Anzeigename. */
    String title();

    /**
     * Alle Partitionen der Quelle (bei Confluence: Spaces), die mit den konfigurierten Zugangsdaten sichtbar sind;
     * ungekürzt.
     *
     * @throws SourceAccessException wenn die Quelle nicht erreichbar ist oder den Zugriff verweigert
     */
    List<Partition> partitions();

    /**
     * Ein Stapel Metadaten aller Seiten einer Partition, ohne Inhalte, vollständig über den Cursor (auch Seiten
     * außerhalb des Baums der Startseite, ohne Gesamtgrenze).
     *
     * @param partitionKey Schlüssel der Partition (Space-Key)
     * @param cursor       {@code null} für den ersten Stapel, sonst {@link PageMetadataBatch#nextCursor()}
     * @param limit        größter gewünschter Stapel, mindestens 1; die Quelle darf weniger liefern
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte Partition, {@code UNSUPPORTED} für einen
     *                               fremden Cursor oder eine Quelle ohne diese Fähigkeit, sonst wie
     *                               {@link #partitions()}
     */
    PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit);

    /**
     * Metadaten einer einzelnen Seite ohne Inhalt über ihre ID in der Quelle, unabhängig vom Space (eine verschobene
     * Seite behält ihre ID).
     *
     * @param pageId ID der Seite in der Quelle (bei Confluence die Content-ID)
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte oder nicht sichtbare Seite,
     *                               {@code UNSUPPORTED} für eine ID, die die Quelle nicht kennt, oder eine Quelle ohne
     *                               diese Fähigkeit, sonst wie {@link #partitions()}
     */
    PageMetadata pageMetadata(String pageId);
}
