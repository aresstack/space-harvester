package com.aresstack.enterpriseai.astu.acropolis.image;

/** Das Ergebnis für ein Bild: erkannter Text (OCR) und Beschreibung getrennt; leer, wo kein Modell gewählt ist. */
public final class ImageAnalysis {

    private final String imageId;
    private final String title;
    private final String version;
    private final String ocrText;
    private final String description;

    public ImageAnalysis(String imageId, String title, String version, String ocrText, String description) {
        this.imageId = imageId;
        this.title = title == null ? "" : title;
        this.version = version == null ? "" : version;
        this.ocrText = ocrText == null ? "" : ocrText;
        this.description = description == null ? "" : description;
    }

    public String imageId() {
        return imageId;
    }

    public String title() {
        return title;
    }

    public String version() {
        return version;
    }

    public String ocrText() {
        return ocrText;
    }

    public String description() {
        return description;
    }

    /** Schlüssel für das Zwischenspeichern: Bild-ID und Version. */
    public static String cacheKey(String imageId, String version) {
        return imageId + "@" + (version == null ? "" : version);
    }
}
