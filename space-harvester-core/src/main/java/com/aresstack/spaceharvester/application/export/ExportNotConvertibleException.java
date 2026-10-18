package com.aresstack.spaceharvester.application.export;

/** Der Export hat (noch) keinen Datenbestand, aus dem sich eine Datei erzeugen ließe. */
public class ExportNotConvertibleException extends RuntimeException {

    private final String exportId;

    public ExportNotConvertibleException(String exportId, String reason) {
        super("Export " + exportId + " lässt sich nicht konvertieren: " + reason);
        this.exportId = exportId;
    }

    public String exportId() {
        return exportId;
    }
}
