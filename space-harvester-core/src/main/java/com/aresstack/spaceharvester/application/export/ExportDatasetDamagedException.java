package com.aresstack.spaceharvester.application.export;

/**
 * Der gespeicherte Datenbestand eines Exports passt nicht mehr zu sich selbst (fehlende Datei, abweichende Prüfsumme,
 * Baum ohne Seite). Eine Konvertierung daraus entsteht dann nicht; ein neuer Export beschafft die Seiten erneut.
 * Die Meldung nennt keine Seiten-IDs oder Titel.
 */
public class ExportDatasetDamagedException extends RuntimeException {

    private final String exportId;

    public ExportDatasetDamagedException(String exportId, String reason) {
        super("Datenbestand des Exports " + exportId + " ist beschädigt: " + reason);
        this.exportId = exportId;
    }

    public String exportId() {
        return exportId;
    }
}
