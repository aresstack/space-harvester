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

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }
}
