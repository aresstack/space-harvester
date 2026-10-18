package com.aresstack.spaceharvester.application.sources;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Anwendungsfälle zum Entdecken der konfigurierten Quellen und ihrer Partitionen (Spaces). */
public class SourceQueries {

    /** Größte Stapelgröße für Seitenmetadaten je Anfrage; zugleich der Standardwert. */
    public static final int MAX_PAGE_METADATA_LIMIT = 100;

    private final SourceCatalog catalog;
    private final Clock clock;

    public SourceQueries(SourceCatalog catalog) {
        this(catalog, Clock.systemUTC());
    }

    public SourceQueries(SourceCatalog catalog, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<HarvestSource> sources() {
        return catalog.all();
    }

    /**
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wenn die Quelle nicht antworten kann
     */
    public List<Partition> partitions(String sourceId) {
        return source(sourceId).partitions();
    }

    /**
     * Ein Stapel Seitenmetadaten einer Partition mit {@code metadataHash} je Seite, live aus der Quelle.
     *
     * @param cursor {@code null} oder leer für den ersten Stapel
     * @param limit  1 bis {@value #MAX_PAGE_METADATA_LIMIT}
     * @throws InvalidQueryException  bei einem Limit außerhalb dieses Bereichs
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wenn die Quelle nicht antworten kann oder Partition/Cursor nicht kennt
     */
    public PageMetadataListing pageMetadata(String sourceId, String partitionKey, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_METADATA_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_PAGE_METADATA_LIMIT + " liegen");
        }
        HarvestSource source = source(sourceId);
        PageMetadataBatch batch = source.pageMetadata(partitionKey,
                cursor == null || cursor.isEmpty() ? null : cursor, limit);
        List<PageMetadataListing.Entry> entries = batch.pages().stream()
                .map(page -> new PageMetadataListing.Entry(page, MetadataHash.of(source.id(), page),
                        AttributesHash.of(source.id(), page)))
                .toList();
        return new PageMetadataListing(source.id(), partitionKey, entries, batch.nextCursor(), clock.instant());
    }

    /**
     * Wie {@link #pageMetadata(String, String, String, int)}, aber nur die Seiten, die innerhalb von {@code within}
     * angelegt oder geändert wurden ({@link HarvestSource#changedPageMetadata}).
     *
     * @throws SourceAccessException {@code UNSUPPORTED}, wenn die Quelle keine Änderungen meldet
     */
    public PageMetadataListing changedPageMetadata(String sourceId, String partitionKey, java.time.Duration within,
                                                   String cursor, int limit) {
        requireLimit(limit);
        HarvestSource source = source(sourceId);
        return listing(source, partitionKey, source.changedPageMetadata(partitionKey, within,
                cursor == null || cursor.isEmpty() ? null : cursor, limit));
    }

    /**
     * Wie {@link #pageMetadata(String, String, String, int)}, aber nur die Seiten, die die Quelle als gelöscht führt
     * ({@link HarvestSource#removedPageMetadata}).
     *
     * @throws SourceAccessException {@code UNSUPPORTED}, wenn die Quelle keine Löschungen meldet
     */
    public PageMetadataListing removedPageMetadata(String sourceId, String partitionKey, String cursor, int limit) {
        requireLimit(limit);
        HarvestSource source = source(sourceId);
        return listing(source, partitionKey, source.removedPageMetadata(partitionKey,
                cursor == null || cursor.isEmpty() ? null : cursor, limit));
    }

    /** Ob die Quelle Änderungen gezielt meldet ({@link HarvestSource#listsChanges()}). */
    public boolean listsChanges(String sourceId) {
        return source(sourceId).listsChanges();
    }

    /**
     * Ob die Quelle Änderungsereignisse lesen kann ({@link HarvestSource#readsChangeEvents()}).
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public boolean readsChangeEvents(String sourceId) {
        return source(sourceId).readsChangeEvents();
    }

    /**
     * Liest einen Webhook-Aufruf der Quelle ({@link HarvestSource#changeEvents}).
     *
     * @throws UnknownSourceException         wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnreadableChangeEventException wenn der Rumpf keine Meldung der Quelle ist
     */
    public List<ChangeEvent> changeEvents(String sourceId, byte[] body, String eventKey) {
        return source(sourceId).changeEvents(body, eventKey);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_METADATA_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_PAGE_METADATA_LIMIT + " liegen");
        }
    }

    private PageMetadataListing listing(HarvestSource source, String partitionKey, PageMetadataBatch batch) {
        List<PageMetadataListing.Entry> entries = batch.pages().stream()
                .map(page -> new PageMetadataListing.Entry(page, MetadataHash.of(source.id(), page),
                        AttributesHash.of(source.id(), page)))
                .toList();
        return new PageMetadataListing(source.id(), partitionKey, entries, batch.nextCursor(), clock.instant());
    }

    /**
     * Metadaten einer einzelnen Seite mit {@code metadataHash} und {@code attributesHash}, live aus der Quelle; dieselben
     * Werte wie in {@link #pageMetadata(String, String, String, int)}.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wenn die Quelle nicht antworten kann oder die Seite nicht kennt
     */
    public SinglePageMetadata pageMetadata(String sourceId, String pageId) {
        HarvestSource source = source(sourceId);
        PageMetadata page = source.pageMetadata(pageId);
        return new SinglePageMetadata(source.id(), new PageMetadataListing.Entry(page,
                MetadataHash.of(source.id(), page), AttributesHash.of(source.id(), page)), clock.instant());
    }

    /**
     * Ob „nicht gefunden“ bei dieser Quelle eine Löschung bestätigt ({@link HarvestSource#confirmsDeletion()}).
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public boolean confirmsDeletion(String sourceId) {
        return source(sourceId).confirmsDeletion();
    }

    /**
     * Die vollständige Anhangsliste einer Seite, live aus der Quelle (#9), mit dem aktuellen Space der Seite für die
     * Rechteprüfung. Lädt keine Dateien.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wenn die Quelle nicht antworten kann oder die Seite nicht kennt
     */
    public PageAttachmentList attachments(String sourceId, String pageId) {
        HarvestSource source = source(sourceId);
        PageMetadata page = source.pageMetadata(pageId);
        PageAttachments attachments = source.attachments(pageId);
        if (!attachments.pageId().equals(page.pageId())) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "Anhangsliste gehört zu einer anderen Seite", null);
        }
        return new PageAttachmentList(source.id(), page.partitionKey(), attachments, clock.instant());
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }
}
