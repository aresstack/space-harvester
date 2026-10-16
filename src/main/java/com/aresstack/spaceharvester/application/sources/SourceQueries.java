package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.Objects;

/** Anwendungsfälle zum Entdecken der konfigurierten Quellen und ihrer Partitionen (Spaces). */
public class SourceQueries {

    private final SourceCatalog catalog;

    public SourceQueries(SourceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }
}
