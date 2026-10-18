package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
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
 * @param media    Anzahl der Dateien je Stand laut {@code assets.json} der letzten Beschaffung
 */
public record ExportDatasetInfo(String exportId, String sourceId, String spaceKey, int pages, boolean complete,
                                Images images, List<String> formats, Media media) {

    public ExportDatasetInfo {
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(images, "images");
        formats = List.copyOf(formats);
        media = media == null ? Media.NONE : media;
    }

    /**
     * Dateien der letzten Bildbeschaffung je Stand (ADR 0018); vor der ersten Beschaffung alles null.
     *
     * @param stored         abgelegt
     * @param missing        nicht beschafft (fehlt, verboten, zu groß, nicht erreichbar)
     * @param signInRequired wegen fehlender Anmeldung (Einmalcode) nicht beschafft; Nachholen über
     *                       {@link ExportConversions#collectImages}
     * @param redundant      bewusst nicht geladen, etwa die Vorschau eines gezeichneten draw.io-Diagramms
     */
    public record Media(int stored, int missing, int signInRequired, int redundant) {

        public static final Media NONE = new Media(0, 0, 0, 0);

        /** Alle Dateien der Beschaffung. */
        public int total() {
            return stored + missing + signInRequired + redundant;
        }

        public static Media of(ExportAssetManifest manifest) {
            int stored = 0;
            int missing = 0;
            int signIn = 0;
            int redundant = 0;
            for (ExportAssetManifest.Entry entry : manifest.assets()) {
                switch (entry.status()) {
                    case STORED -> stored++;
                    case SIGN_IN_REQUIRED -> signIn++;
                    case REDUNDANT -> redundant++;
                    default -> missing++;
                }
            }
            return new Media(stored, missing, signIn, redundant);
        }
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
