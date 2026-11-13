package com.aresstack.spaceharvester.application.convert;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Die Bilder eines Exportdatenbestands für Converter: Einträge je Seite aus {@code assets.json} und ihre Bytes aus dem
 * Asset-Speicher. Ein Converter fordert nie bei der Quelle an; was nicht {@link ExportAssetManifest.Entry#stored()}
 * ist, meldet er als fehlend.
 */
public interface ExportAssets {

    /** Ohne beschaffte Bilder: keine Einträge. */
    ExportAssets NONE = new ExportAssets() {
        @Override
        public Optional<ExportAssetManifest> manifest() {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
            return Optional.empty();
        }
    };

    /** Das Manifest oder leer, solange keine Bilder beschafft wurden. */
    Optional<ExportAssetManifest> manifest();

    /** Die Einträge einer Seite; leer ohne Manifest. */
    default List<ExportAssetManifest.Entry> forPage(String pageId) {
        return manifest().map(manifest -> manifest.forPage(pageId)).orElse(List.of());
    }

    /**
     * Die abgelegten Bytes eines Eintrags, gegen seine {@code sha256} geprüft; bei {@link ExportAssetManifest.Kind#MERMAID}
     * der Diagrammtext in UTF-8.
     *
     * @return leer, wenn er nicht abgelegt ist oder die Ablage ihn nicht (mehr) hat
     */
    Optional<byte[]> content(ExportAssetManifest.Entry entry);

    /**
     * Wie {@link #content}, aber als Strom, ohne den Eintrag ganz in den Speicher zu lesen: für Originale beliebiger
     * Größe (Angelo 11.11.2026). Eine Abweichung von der {@code sha256} meldet der Strom spätestens am Ende als
     * {@link java.io.IOException}. Der Aufrufer schließt ihn.
     *
     * @return leer, wenn er nicht abgelegt ist oder die Ablage ihn nicht (mehr) hat
     */
    default Optional<InputStream> open(ExportAssetManifest.Entry entry) {
        return content(entry).map(ByteArrayInputStream::new);
    }

    /**
     * Die beim Beschaffen gezeichnete Grafik eines Diagramms ({@link ExportAssetManifest.Entry#rendition()}), gegen
     * ihre {@code sha256} geprüft; ein Converter zeichnet dann nicht selbst.
     *
     * @return leer ohne gezeichnete Grafik oder wenn die Ablage sie nicht (mehr) hat
     */
    default Optional<byte[]> rendition(ExportAssetManifest.Entry entry) {
        return Optional.empty();
    }

    /**
     * Der beim Beschaffen gelesene Text eines Dokument-Anhangs ({@link ExportAssetManifest.Kind#DOCUMENT}, ADR 0023)
     * aus der Ablage; ein Converter liest nie selbst.
     *
     * @return leer, wenn es keinen Text gibt (nicht lesbar, keine Extraktion, kein Dokument)
     */
    default Optional<String> text(ExportAssetManifest.Entry entry) {
        return Optional.empty();
    }

    /**
     * Wie {@link #text}, mit der Angabe, ob der Text beim Einlesen eines Pakets gekürzt wurde, und dem gelesenen
     * Umfang in Bytes (ADR 0026, Auflage zu AP4c: nie stilles Kürzen).
     */
    default Optional<DocumentText> documentText(ExportAssetManifest.Entry entry) {
        return text(entry).map(text -> new DocumentText(text, false,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
    }

    /**
     * Text eines Dokument-Anhangs aus einem Paket.
     *
     * @param truncated ob beim Einlesen gekürzt wurde
     * @param bytesRead gelesene Bytes (UTF-8)
     * @param extractor Extraktion laut Paket ({@code attachments/v2}: {@code extraction.extractor} des Behälters) oder
     *                  {@code null}
     * @param version   Version der Extraktion laut Paket oder {@code null}
     */
    record DocumentText(String text, boolean truncated, long bytesRead, String extractor, String version) {

        public DocumentText {
            java.util.Objects.requireNonNull(text, "text");
        }

        /** Ohne Angabe der Extraktion ({@code attachments/v1}). */
        public DocumentText(String text, boolean truncated, long bytesRead) {
            this(text, truncated, bytesRead, null, null);
        }
    }

    /**
     * Ein abgelegter Dokument-Anhang mit seinen enthaltenen Dateien als Struktur (ADR 0026, AP4c,
     * {@code attachments/v2}); leer ohne strukturierte Extraktion, dann gilt {@link #text}.
     */
    default Optional<com.aresstack.spaceharvester.application.assets.DocumentExport> document(
            ExportAssetManifest.Entry entry) {
        return Optional.empty();
    }
}
