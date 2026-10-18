package com.aresstack.spaceharvester.application.export;

import java.util.List;
import java.util.Objects;

/**
 * Der gespeicherte Datenbestand eines abgeschlossenen Exports: Grundlage für „Exportieren als …“
 * ({@link ExportConversions#convert}).
 *
 * @param pages    Anzahl der Seiten im Datenbestand
 * @param complete ob der Export vollständig war (siehe {@link ExportSummary#complete()})
 * @param images   Stand der Bilder zum Datenbestand
 * @param formats  Zielformate, in die er sich konvertieren lässt
 */
public record ExportDatasetInfo(String exportId, String sourceId, String spaceKey, int pages, boolean complete,
                                Images images, List<String> formats) {

    public ExportDatasetInfo {
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(images, "images");
        formats = List.copyOf(formats);
    }

    /** Stand der Bilder: bis sie beschafft sind, enthalten Konvertierungen nur Verweise und Hinweise. */
    public enum Images {
        /** Für diesen Datenbestand wurden (noch) keine Bilder beschafft. */
        NOT_COLLECTED,
        /** Ein Auftrag beschafft sie gerade. */
        COLLECTING,
        /** Alle Bildverweise sind abgelegt. */
        COMPLETE,
        /** Einige ließen sich nicht beschaffen; sie gelten in Konvertierungen als fehlend. */
        INCOMPLETE
    }
}
