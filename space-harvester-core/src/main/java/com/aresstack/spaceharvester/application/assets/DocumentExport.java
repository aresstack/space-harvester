package com.aresstack.spaceharvester.application.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein Dokument-Anhang mit seinen enthaltenen Dateien für den Export (ADR 0026, AP4c, {@code attachments/v2}): je
 * Dokument Status der Extraktion, Text und Markdown aus den abgelegten Abschnitten, abschnittsweise geschrieben, ohne
 * Zeichengrenze und ohne erzeugten Text; die Originale der Teile mit Inhaltskennung als Strom.
 */
public interface DocumentExport {

    /** Status der Extraktion des Dokuments selbst. */
    Extraction extraction();

    /** Ob das Dokument selbst Text hat (ein Behälter ohne eigenen Text hat keinen). */
    boolean hasText();

    /** Schreibt den reinen Text des Dokuments selbst (UTF-8), Absätze mit Leerzeile getrennt. */
    void writeText(OutputStream out) throws IOException;

    /** Schreibt die Markdown-Ansicht des Dokuments selbst (UTF-8). */
    void writeMarkdown(OutputStream out) throws IOException;

    /** Die enthaltenen Dateien in Verzeichnisreihenfolge. */
    List<Part> parts();

    /** Schreibt den reinen Text eines Teils (UTF-8). */
    void writeText(Part part, OutputStream out) throws IOException;

    /** Schreibt die Markdown-Ansicht eines Teils (UTF-8). */
    void writeMarkdown(Part part, OutputStream out) throws IOException;

    /** Das Original eines Teils mit Inhaltskennung; leer bei Kennung nach Position. Der Strom muss geschlossen werden. */
    Optional<InputStream> openOriginal(Part part);

    /**
     * Gibt die Originale aller Teile mit Inhaltskennung in einem Durchlauf durch den Behälter nacheinander an
     * {@code originals} (Auflage zu AP4c): der Behälter wird dafür genau einmal gelesen, nichts im Speicher gesammelt.
     * Teile, die sich nicht abrufen lassen, kommen nicht vor.
     *
     * @throws IOException wenn {@code originals} scheitert
     */
    void copyOriginals(Originals originals) throws IOException;

    /** Abnehmer der Originale; der Strom gilt nur während des Aufrufs. */
    @FunctionalInterface
    interface Originals {
        void accept(Part part, InputStream content) throws IOException;
    }

    /**
     * Status einer Extraktion samt den Versionen, die sie erzeugt haben (ADR 0026, Punkte 7 und 11).
     *
     * @param status    {@link ExtractionStatus#name()}
     * @param reason    fester Code oder {@code null}
     * @param bytesRead gelesene Bytes oder {@code null}
     * @param charsRead gelesene Zeichen oder {@code null}
     * @param extractor Kennung der Extraktion
     * @param version   Version der Extraktion
     * @param model     Version des Strukturmodells
     */
    record Extraction(String status, String reason, boolean truncated, Long bytesRead, Long charsRead,
                      String extractor, String version, String model) {

        public Extraction {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(extractor, "extractor");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(model, "model");
        }

        static Extraction of(Outcome outcome, ExtractionKey key) {
            return new Extraction(outcome.status().name(), outcome.reason().map(StatusReason::code).orElse(null),
                    outcome.truncated(), outcome.extent().map(ReadExtent::bytes).orElse(null),
                    outcome.extent().map(ReadExtent::chars).orElse(null), key.extractor(), key.version(), key.model());
        }
    }

    /**
     * Eine enthaltene Datei.
     *
     * @param partId       Teilkennung ({@link PartId#value()})
     * @param parentPartId Teilkennung des Elterneintrags, {@code null} direkt im Behälter
     * @param position     Zählerpfad wie {@code /1/3}
     * @param title        Anzeigename; nie Pfad oder Kennung, kann fehlen ({@code null})
     * @param mediaType    am Inhalt erkannter Medientyp oder {@code null}
     * @param size         gelesene Bytes oder {@code null}
     * @param sha256       Inhalts-Hash, nur bei vollständig gelesenen Bytes
     * @param fileName     pfadsicherer Name nach der Kennung ({@link PartId#fileName()})
     * @param hasText      ob der Teil Text hat
     * @param original     ob das Original abrufbar ist (Inhaltskennung)
     */
    record Part(String partId, String parentPartId, String position, int depth, String title, String mediaType,
                Long size, String sha256, String fileName, Extraction extraction, boolean hasText, boolean original) {

        public Part {
            Objects.requireNonNull(partId, "partId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(extraction, "extraction");
        }

        @Override
        public String toString() {
            return "Part[" + partId + ", " + extraction.status() + "]";
        }
    }
}
