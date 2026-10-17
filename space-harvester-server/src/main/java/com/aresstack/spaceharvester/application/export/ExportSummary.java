package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.query.CacheState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Inhalt des Manifests, soweit ihn der Export bestimmt; Dateinamen, Größen und Hashes ergänzt der Writer.
 *
 * @param exportId     zugleich die Job-ID
 * @param scope        gelesener Space und Generation
 * @param startedAt    Beginn des Exports
 * @param finishedAt   Ende des Lesens
 * @param pagesWritten Seiten im flachen Seiten-Array
 * @param failures     Seiten, die der Leser nicht liefern konnte
 * @param warnings     Auffälligkeiten, etwa Seiten, die im Baum nur durch fehlende Elternseite oder Zyklus Wurzel
 *                     wurden
 * @param complete     {@code true} nur, wenn die Generation bestätigt ist und keine Seite fehlt
 */
public record ExportSummary(String exportId, ExportScope scope, Instant startedAt, Instant finishedAt,
                            long pagesWritten, List<PageFailure> failures, List<String> warnings, boolean complete) {

    /** Kennung des Manifest- und Artefaktformats. */
    public static final String SCHEMA_VERSION = "space-harvester-export/v1";

    public ExportSummary {
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        failures = List.copyOf(failures);
        warnings = List.copyOf(warnings);
        if (complete && (scope.generation().state() != CacheState.CONFIRMED || !failures.isEmpty())) {
            throw new IllegalArgumentException("vollständig nur aus bestätigter Generation ohne fehlende Seiten");
        }
    }
}
