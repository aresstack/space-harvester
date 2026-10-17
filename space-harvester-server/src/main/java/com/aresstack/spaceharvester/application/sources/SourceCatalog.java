package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.Optional;

/** Die konfigurierten Quellen. */
public interface SourceCatalog {

    /** Alle Quellen in Konfigurationsreihenfolge. */
    List<HarvestSource> all();

    Optional<HarvestSource> find(String sourceId);
}
