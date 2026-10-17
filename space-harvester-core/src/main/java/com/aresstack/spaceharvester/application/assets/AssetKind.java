package com.aresstack.spaceharvester.application.assets;

import java.util.Locale;

/**
 * Art eines Anhangs, in der Reihenfolge, in der Verbraucher ihn bevorzugen sollen: textuelle Diagrammdaten vor
 * Vektorgrafik vor Rasterbild. Ein Bild muss erst von einem Modell beschrieben werden, was verlustbehaftet ist; die
 * Quelldaten eines Diagramms sind bereits Text.
 */
public enum AssetKind {
    /** Quelldaten eines draw.io-Diagramms (mxfile-XML). */
    DIAGRAM,
    /** SVG-Grafik, als XML lesbar. */
    VECTOR,
    /** Rasterbild (PNG, JPEG, GIF …). */
    IMAGE,
    /** Alles andere, etwa PDF oder Office-Dokumente. */
    OTHER;

    static final String DRAWIO_MEDIA_TYPE = "application/vnd.jgraph.mxfile";
    static final String DRAWIO_SUFFIX = ".drawio";

    /**
     * Ordnet einen Anhang nach Medientyp und Dateiname ein. Confluence liefert draw.io-Diagramme teils ohne Endung mit
     * {@value #DRAWIO_MEDIA_TYPE}, teils als {@code *.drawio} mit {@code application/octet-stream}.
     */
    public static AssetKind of(String mediaType, String title) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        String name = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (type.equals(DRAWIO_MEDIA_TYPE) || name.endsWith(DRAWIO_SUFFIX)) {
            return DIAGRAM;
        }
        if (type.equals("image/svg+xml") || name.endsWith(".svg")) {
            return VECTOR;
        }
        if (type.startsWith("image/")) {
            return IMAGE;
        }
        return OTHER;
    }
}
