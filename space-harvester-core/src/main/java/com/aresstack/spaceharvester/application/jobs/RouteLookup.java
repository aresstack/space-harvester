package com.aresstack.spaceharvester.application.jobs;

import java.util.Optional;

/**
 * Die Proxy-Route zu einer Quelle in einer Zeile, etwa {@code DIRECT} oder {@code PROXY proxy:8080 (pac:resolved)},
 * für Diagnoseereignisse. {@link Jobs} fragt nur bei gescheiterten Aufträgen; Umsetzungen nutzen die beim Abruf
 * schon aufgelöste, gemerkte Route und werfen nicht.
 */
@FunctionalInterface
public interface RouteLookup {

    RouteLookup NONE = sourceId -> Optional.empty();

    /** Leer für unbekannte Quellen und Quellen ohne Netzwerkzugriff. */
    Optional<String> routeOf(String sourceId);
}
