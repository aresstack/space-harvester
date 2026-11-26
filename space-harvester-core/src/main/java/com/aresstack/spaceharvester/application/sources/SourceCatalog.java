package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.Optional;

/** Die konfigurierten Quellen. */
public interface SourceCatalog {

    /** Alle Quellen in Konfigurationsreihenfolge. */
    List<HarvestSource> all();

    Optional<HarvestSource> find(String sourceId);

    /** Show an active source in the explorer unless configured otherwise. */
    default boolean showInExplorer(String sourceId) {
        return true;
    }

    /** Include an active source in implicit global searches unless configured otherwise. */
    default boolean includeInGlobalSearch(String sourceId) {
        return true;
    }

    /** List active sources that participate in implicit global searches. */
    default List<HarvestSource> globalSearchSources() {
        return all().stream().filter(source -> includeInGlobalSearch(source.id())).toList();
    }

    /** Die Quellentypen, die der Katalog kennt, in Registrierungsreihenfolge (#187). */
    default List<SourceType> types() {
        return List.of();
    }
}
