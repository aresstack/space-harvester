package com.aresstack.spaceharvester.application.export;

import java.util.Map;
import java.util.Objects;

/**
 * Herkunft einer konvertierten Datei im Manifest: womit sie aus welchem Input entstand.
 *
 * @param format           angefragtes Format, etwa {@code json}
 * @param converter        Version des Converters
 * @param mediaType        Medientyp der Datei
 * @param fileName         Dateiname im Artefakt
 * @param inputSha256      Hash über die konvertierten Seiten in Baumreihenfolge ({@link #INPUT_HASH_VERSION})
 * @param parameters       Parameter der Konvertierung; in V1 leer
 */
public record Conversion(String format, String converter, String mediaType, String fileName, String inputSha256,
                         Map<String, String> parameters) {

    /**
     * Kennung des Input-Hashes: SHA-256 über je eine Zeile {@code depth TAB pageId TAB metadataHash TAB rawSha256 LF}
     * je Seite in Baumreihenfolge. Er deckt Baum, Kopfdaten und Payload ab; gleicher Hash und gleiche Converter-Version
     * ergeben dieselbe Datei.
     */
    public static final String INPUT_HASH_VERSION = "conversion-input/v1";

    public Conversion {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(inputSha256, "inputSha256");
        parameters = Map.copyOf(parameters);
    }
}
