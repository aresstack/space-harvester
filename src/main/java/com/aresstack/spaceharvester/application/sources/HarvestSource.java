package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    /**
     * Die native Antwort der Quelle für eine Seite Byte für Byte, mit den Kopfdaten aus derselben Antwort (Revision und
     * Inhalt gehören zusammen). Bei Confluence die REST-Antwort mit {@code body.storage} (ADR 0002).
     *
     * @param pageId ID der Seite in der Quelle (bei Confluence die Content-ID)
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte oder nicht sichtbare Seite,
     *                               {@code INVALID_RESPONSE} auch für eine Antwort über der Größengrenze der Quelle (sie
     *                               wird nie gekürzt), {@code UNSUPPORTED} für eine fremde ID oder eine Quelle ohne
     *                               diese Fähigkeit, sonst wie {@link #partitions()}
     */
    default StoredPage storedPage(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann native Seitenantworten nicht liefern", null);
    }

    /**
     * {@code true}, wenn {@link #storedPageAsync} nicht blockierend arbeitet, also die Quelle einen asynchronen
     * HTTP-Client hat ({@code space-harvester.network.async-transport}, #47).
     */
    default boolean asyncStoredPages() {
        return false;
    }

    /**
     * Wie {@link #storedPage}, aber ohne den Aufrufer-Thread bis zur Antwort zu belegen. Die Stufe endet mit derselben
     * Seite oder ausnahmsweise mit derselben {@link SourceAccessException}; ein Abbruch der Stufe bricht die Anfrage
     * ab. Ohne {@link #asyncStoredPages()} endet sie sofort mit {@code UNSUPPORTED}.
     */
    default CompletionStage<StoredPage> storedPageAsync(String pageId) {
        return CompletableFuture.failedFuture(new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann native Seitenantworten nicht asynchron liefern", null));
    }
}
