package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.Objects;

/**
 * Text eines Dokument-Anhangs (#183), als Anreicherung an der Datei, nicht an der Seite: Er gilt nur für genau diese
 * Bytes ({@code sha256}) und diese Extraktion; ändert sich eines davon, wird er neu gelesen. Er hat denselben
 * Zugriffsschutz wie der Anhang.
 *
 * @param truncated ob der Text an der Zeichengrenze der Extraktion abgeschnitten wurde
 */
public record AssetText(String sha256, String extractor, String version, String text, boolean truncated,
                        Instant createdAt) {

    public AssetText {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        return "AssetText[sha256=" + sha256 + ", extractor=" + extractor + ", version=" + version + ", chars="
                + text.length() + ", truncated=" + truncated + "]";
    }
}
