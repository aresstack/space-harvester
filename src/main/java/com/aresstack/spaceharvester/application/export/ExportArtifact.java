package com.aresstack.spaceharvester.application.export;

import java.util.List;
import java.util.Objects;

/**
 * Ein veröffentlichtes Exportartefakt.
 *
 * @param exportId zugleich die Job-ID
 * @param complete wie im Manifest
 * @param files    Dateien des Artefakts ohne die einzelnen Payloads (deren Hashes stehen im Seiten-Array)
 */
public record ExportArtifact(String exportId, boolean complete, List<File> files) {

    public ExportArtifact {
        Objects.requireNonNull(exportId, "exportId");
        files = List.copyOf(files);
    }

    /**
     * @param name   relativer Name, z. B. {@code pages.ndjson}
     * @param bytes  Größe
     * @param sha256 {@code sha256:<hex>} über den Dateiinhalt
     */
    public record File(String name, long bytes, String sha256) {
    }
}
