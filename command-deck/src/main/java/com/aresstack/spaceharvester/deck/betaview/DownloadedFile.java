package com.aresstack.spaceharvester.deck.betaview;

import java.util.Locale;

/**
 * Ein heruntergeladenes Dokument: Inhalt und Inhaltstyp, wie der Server sie schickt.
 *
 * @param data        Inhalt
 * @param contentType Inhaltstyp, leer ohne
 */
public record DownloadedFile(byte[] data, String contentType) {

    public DownloadedFile {
        data = data == null ? new byte[0] : data;
        contentType = contentType == null ? "" : contentType;
    }

    public boolean pdf() {
        return contentType.toLowerCase(Locale.ROOT).contains("pdf");
    }

    /** Dateiendung zum Speichern: {@code .pdf}, {@code .csv} oder {@code .txt}. */
    public String extension() {
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("pdf")) {
            return ".pdf";
        }
        if (type.contains("csv")) {
            return ".csv";
        }
        return ".txt";
    }
}
