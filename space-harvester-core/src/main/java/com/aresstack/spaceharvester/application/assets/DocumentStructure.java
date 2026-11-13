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
}
