package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.Objects;

/**
 * Maschinell erzeugte Beschreibung eines Bildes, als Enrichment an der Bildressource, nicht an der Seite (#19). Sie
 * gilt nur für genau diese Bildbytes ({@code imageSha256}) und dieses Modell; ändert sich eines davon, wird sie neu
 * erzeugt. Sie ist kein vom Autor bestätigter Teil der Seite und hat denselben Zugriffsschutz wie das Bild.
 */
public record ImageDescription(String imageSha256, String describer, String modelVersion, String text,
                               Instant createdAt) {

    public ImageDescription {
        Objects.requireNonNull(imageSha256, "imageSha256");
        Objects.requireNonNull(describer, "describer");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        return "ImageDescription[imageSha256=" + imageSha256 + ", describer=" + describer + ", modelVersion="
                + modelVersion + ", chars=" + text.length() + "]";
    }
}
