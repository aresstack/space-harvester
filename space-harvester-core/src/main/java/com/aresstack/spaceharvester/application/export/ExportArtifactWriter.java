package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ablage für Exportartefakte (#11). Ein Artefakt entsteht unsichtbar und wird erst mit
 * {@link Session#publish(PageTree, ExportSummary)} als Ganzes sichtbar; ein abgebrochener oder gescheiterter Export
 * hinterlässt nichts. Ein veröffentlichtes Artefakt wird nie überschrieben.
 */
public interface ExportArtifactWriter {

    /**
     * Beginnt ein neues, noch unsichtbares Artefakt. Jede Schreibmethode der Sitzung kann
     * {@link ExportStorageFullException} werfen, wenn die Ablage ihre Gesamtgrenze erreicht.
     *
     * @throws ExportStorageFullException wenn die Ablage schon voll ist
     */
    Session begin(String exportId, ExportScope scope);

    /**
     * Prüft vor der Annahme eines Exports, ob die Ablage noch Platz hat.
     *
     * @throws ExportStorageFullException wenn nicht
     */
    default void requireCapacity() {
    }

    /** Das veröffentlichte Artefakt oder leer, wenn es (noch) keines gibt. */
    Optional<ExportArtifact> find(String exportId);

    /**
     * Die Seiten-IDs eines veröffentlichten Artefakts, damit vor jeder Auslieferung geprüft werden kann, ob der
     * Auftraggeber sie noch lesen darf.
     *
     * @return leer, wenn es das Artefakt nicht gibt
     */
    Optional<List<String>> pageIds(String exportId);

    /**
     * Eine Datei eines veröffentlichten Artefakts zum Lesen.
     *
     * @param name relativer Name wie in {@link ExportArtifact#files()} oder {@code raw/<Datei>} aus dem Seiten-Array
     * @return leer, wenn es das Artefakt oder die Datei nicht gibt oder der Name aus dem Artefakt hinausführt
     */
    Optional<InputStream> open(String exportId, String name);

    /**
     * Der gespeicherte Datenbestand eines veröffentlichten Artefakts (Seiten, Payloads, Baum) als Eingabe für
     * Converter; liest weder Cache noch Quelle.
     *
     * @return leer, wenn es das Artefakt nicht gibt oder die Ablage keinen Datenbestand liefern kann
     * @throws ExportDatasetDamagedException wenn der Datenbestand nicht zu sich selbst passt
     */
    default Optional<com.aresstack.spaceharvester.application.convert.ConversionInput> dataset(String exportId) {
        return Optional.empty();
    }

    /**
     * Legt das Bildmanifest ({@code assets.json}) zu einem veröffentlichten Artefakt ab oder ersetzt es; die übrigen
     * Dateien bleiben unverändert.
     *
     * @throws IllegalStateException wenn es das Artefakt nicht gibt
     */
    default void putAssets(String exportId, com.aresstack.spaceharvester.application.convert.ExportAssetManifest
            manifest) {
        throw new UnsupportedOperationException("Ablage kennt keine Bildmanifeste");
    }

    /** Das Bildmanifest eines veröffentlichten Artefakts oder leer, solange keines abgelegt ist. */
    default Optional<com.aresstack.spaceharvester.application.convert.ExportAssetManifest> assets(String exportId) {
        return Optional.empty();
    }

    /**
     * Legt die Lese-Ansicht einer Seite im veröffentlichten Datenbestand ab (#218); eine vorhandene derselben Seite wird
     * ersetzt.
     */
    default void putReadingView(String exportId, com.aresstack.spaceharvester.application.convert.ExportReadingView
            view) {
        throw new UnsupportedOperationException("Ablage kennt keine Lese-Ansichten");
    }

    /** IDs aller veröffentlichten Artefakte. */
    Set<String> published();

    /** IDs aller Artefakte im Entstehen, auch von Läufen, die nie fertig wurden (Abbruch des Dienstes). */
    Set<String> unpublished();

    /** Entfernt das veröffentlichte Artefakt dieses Exports; ohne Wirkung, wenn es keines gibt. */
    void delete(String exportId);

    /** Verwirft das Artefakt dieses Exports im Entstehen; ein veröffentlichtes bleibt unberührt. */
    void discard(String exportId);

    /** Ein Artefakt im Entstehen. {@link #close()} ohne vorheriges {@link #publish} verwirft es. */
    interface Session extends AutoCloseable {

        /**
         * Hängt eine Seite an das flache Seiten-Array an und legt ihre Payload unverändert ab.
         *
         * @param header   aktuelle Kopfdaten der Seite
         * @param snapshot nativer Snapshot mit der Payload; seine Kopfdaten gehören zur Payload
         */
        void page(PageHeader header, PageSnapshot snapshot);

        /**
         * Legt eine aus den Seiten abgeleitete Datei ab (#30) und nimmt ihre Herkunft ins Manifest auf.
         *
         * @param content schreibt den Inhalt; scheitert er, wird das ganze Artefakt verworfen
         * @throws IOException aus {@code content}
         */
        ExportArtifact.File converted(Conversion conversion, Content content) throws IOException;

        /** Schreibt Baum und Manifest und macht das Artefakt sichtbar. */
        ExportArtifact publish(PageTree tree, ExportSummary summary);

        @Override
        void close();
    }

    /** Schreibt den Inhalt einer Datei in einen Strom, den der Writer öffnet und schließt. */
    @FunctionalInterface
    interface Content {

        void write(OutputStream out) throws IOException;
    }
}
