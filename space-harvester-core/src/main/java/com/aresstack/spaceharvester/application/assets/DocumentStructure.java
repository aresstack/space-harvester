package com.aresstack.spaceharvester.application.assets;

import java.nio.file.Path;

/**
 * Port der strukturierten Extraktion (ADR 0026, Punkt 3), neben dem schmalen {@link DocumentText}: liest ein Original
 * aus einer Datei, nie als Byte-Array, und liefert Blöcke in Lesereihenfolge als Strom (je enthaltener Datei ein
 * eigener), Kopfdaten, das Verzeichnis der
 * enthaltenen Dateien und den Status. {@link DocumentText} wird aus derselben Extraktion bedient. Eigene Typen des
 * Core, keine Typen der Kopie aus dem Enterprise AI Client.
 *
 * <p>Erreicht das Lesen eine Schutzgrenze, endet nur der betroffene Teil; was bis dahin gelesen ist, bleibt, und die
 * Grenze steht als {@link Outcome} am Dokument oder Eintrag. Meldungen nennen weder Dateinamen noch Inhalte.
 */
public interface DocumentStructure {

    /** Version des Strukturmodells; ein anderer Wert macht abgelegte Extraktionen alt (ADR 0026, Punkt 11). */
    String MODEL_VERSION = "structured-document/v1";

    /** Kennung der Extraktion, z. B. {@code tika}. */
    String id();

    /** Version der Extraktion; ein anderer Wert macht abgelegte Extraktionen alt. */
    String version();

    /**
     * Liest ein Dokument.
     *
     * @param original  das abgelegte Original
     * @param title     Dateiname, nur als Hinweis auf das Format
     * @param mediaType Medientyp, möglichst schon am Inhalt geprüft
     * @param limits    Schutzgrenzen beim Lesen
     * @param sink      Empfänger der Blockströme: äußeres Dokument und je enthaltener Datei ein eigener
     * @return Kopfdaten, Verzeichnis und Status; ein Fehlschlag ist ein Status, keine Ausnahme
     */
    StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                               StructureSink sink);

    /**
     * Wie {@link #extract(Path, String, String, ExtractionLimits, StructureSink)}, mit den Regeln für enthaltene
     * Dateien (AP7): Hash des Behälters und zugelassene Typen. Ein Adapter ohne enthaltene Dateien übergeht sie.
     */
    default StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                       StructureSink sink, PartRules rules) {
        return extract(original, title, mediaType, limits, sink);
    }

    /**
     * Öffnet das Original einer enthaltenen Datei (ADR 0026, Punkt 6, Abnahme 9): liest den Behälter erneut unter
     * denselben Schutzgrenzen und gibt den Teil mit dieser Kennung als Strom heraus, ohne zweite Kopie in der Ablage.
     * Der Strom liest aus einer temporären Datei, die beim Schließen gelöscht wird.
     *
     * @return leer, wenn der Teil in diesem Behälter nicht (mehr) vollständig zu lesen ist; ohne enthaltene Dateien
     *         immer leer
     */
    default java.util.Optional<java.io.InputStream> openPart(Path original, String title, String mediaType,
                                                              ExtractionLimits limits, PartRules rules,
                                                              PartId.Content id) {
        return java.util.Optional.empty();
    }

    /**
     * Gibt die Originale der verlangten enthaltenen Dateien in einem Durchlauf durch den Behälter nacheinander an
     * {@code originals} (ADR 0026, Auflage zu AP4c): unter denselben Schutzgrenzen wie beim Lesen, nichts wird im
     * Speicher gesammelt, jede temporäre Datei fällt nach der Übergabe weg. Teile, die sich nicht (mehr) vollständig
     * lesen lassen, werden übergangen. Ohne eigene Umsetzung je Teil über {@link #openPart}.
     *
     * @throws IOException wenn {@code originals} scheitert; der Durchlauf endet dann
     */
    default void copyParts(Path original, String title, String mediaType, ExtractionLimits limits, PartRules rules,
                           java.util.Set<PartId.Content> wanted, PartOriginals originals) throws java.io.IOException {
        for (PartId.Content id : wanted) {
            java.util.Optional<java.io.InputStream> part = openPart(original, title, mediaType, limits, rules, id);
            if (part.isPresent()) {
                try (java.io.InputStream in = part.get()) {
                    originals.accept(id, in);
                }
            }
        }
    }

    /** Abnehmer der Originale enthaltener Dateien; der Strom gilt nur während des Aufrufs. */
    @FunctionalInterface
    interface PartOriginals {
        void accept(PartId.Content id, java.io.InputStream content) throws java.io.IOException;
    }
}
