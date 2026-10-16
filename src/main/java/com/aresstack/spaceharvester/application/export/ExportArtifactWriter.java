package com.aresstack.spaceharvester.application.export;

import java.util.Optional;

/**
 * Ablage für Exportartefakte (#11). Ein Artefakt entsteht unsichtbar und wird erst mit
 * {@link Session#publish(ExportTree, ExportSummary)} als Ganzes sichtbar; ein abgebrochener oder gescheiterter Export
 * hinterlässt nichts. Ein veröffentlichtes Artefakt wird nie überschrieben.
 */
public interface ExportArtifactWriter {

    /** Beginnt ein neues, noch unsichtbares Artefakt. */
    Session begin(String exportId, ExportScope scope);

    /** Das veröffentlichte Artefakt oder leer, wenn es (noch) keines gibt. */
    Optional<ExportArtifact> find(String exportId);

    /** Ein Artefakt im Entstehen. {@link #close()} ohne vorheriges {@link #publish} verwirft es. */
    interface Session extends AutoCloseable {

        /** Hängt eine Seite an das flache Seiten-Array an und legt ihre Payload unverändert ab. */
        void page(ExportPage page);

        /** Schreibt Baum und Manifest und macht das Artefakt sichtbar. */
        ExportArtifact publish(ExportTree tree, ExportSummary summary);

        @Override
        void close();
    }
}
