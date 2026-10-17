package com.aresstack.spaceharvester.application.convert;

import java.io.IOException;

/** Eine konvertierte Datei würde größer als erlaubt; sie wird nie abgeschnitten, der Export scheitert. */
public class ConversionLimitExceededException extends IOException {

    public ConversionLimitExceededException(String fileName, long maxBytes) {
        super("Konvertierte Datei " + fileName + " überschreitet " + maxBytes + " Bytes");
    }

    private ConversionLimitExceededException(String message) {
        super(message);
    }

    /** Die Datei hätte mehr Seiten als erlaubt (PDF). */
    public static ConversionLimitExceededException pages(String fileName, int maxPages) {
        return new ConversionLimitExceededException("Konvertierte Datei " + fileName + " überschreitet " + maxPages
                + " Seiten");
    }
}
