package com.aresstack.spaceharvester.application.sources;

/**
 * Optionale Fähigkeiten einer {@link HarvestSource} (#18). Fehlt eine, antwortet die entsprechende Abfrage mit
 * {@code UNSUPPORTED}; {@code GET /api/v1/sources} zeigt sie je Quelle an, damit Aufrufer das vorher sehen.
 */
public enum SourceCapability {
    /** Partitionen (bei Confluence Spaces) auflisten. */
    PARTITIONS,
    /** Metadaten aller Seiten einer Partition stapelweise ({@link HarvestSource#pageMetadata(String, String, int)}). */
    PAGE_LISTING,
    /** Metadaten einer einzelnen Seite über ihre ID ({@link HarvestSource#pageMetadata(String)}). */
    PAGE_METADATA,
    /** Native Antwort einer Seite Byte für Byte ({@link HarvestSource#storedPage}). */
    STORED_PAGE,
    /** Dieselbe Antwort nicht blockierend ({@link HarvestSource#storedPageAsync}). */
    ASYNC_STORED_PAGE,
    /** Anhangsliste einer Seite als Metadaten ({@link HarvestSource#attachments}, #9). */
    PAGE_ATTACHMENTS,
    /** Volltextsuche in der Quelle selbst über Titel und Inhalt ({@link HarvestSource#searchPages}). */
    LIVE_SEARCH
}
