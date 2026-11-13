package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Text eines Dokument-Anhangs (#183), als Anreicherung an der Datei, nicht an der Seite: Er gilt nur für genau diese
 * Bytes ({@code sha256}) und diese Extraktion; ändert sich eines davon, wird er neu gelesen. Er hat denselben
 * Zugriffsschutz wie der Anhang.
 *
 * @param truncated ob der Text gekürzt ist: an der Zeichengrenze oder weil die Extraktion gekürzt ist
 * @param outcome   Status der Extraktion (ADR 0026, AP4a): Status, Grund, gelesener Umfang; leer bei der bisherigen
 *                  Textextraktion ohne Abschnitte. Wird nicht mit dem Text abgelegt, sondern aus der Extraktion gelesen.
 */
public record AssetText(String sha256, String extractor, String version, String text, boolean truncated,
                        Instant createdAt, Optional<Outcome> outcome) {

    public AssetText {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
        outcome = outcome == null ? Optional.empty() : outcome;
    }

    /** Ohne Status der Extraktion (bisherige Textextraktion, abgelegter Text). */
    public AssetText(String sha256, String extractor, String version, String text, boolean truncated,
                     Instant createdAt) {
        this(sha256, extractor, version, text, truncated, createdAt, Optional.empty());
    }

    /** Derselbe Text mit diesem Status der Extraktion. */
    public AssetText withOutcome(Outcome outcome) {
        return new AssetText(sha256, extractor, version, text, truncated, createdAt, Optional.ofNullable(outcome));
    }

    @Override
    public String toString() {
        return "AssetText[sha256=" + sha256 + ", extractor=" + extractor + ", version=" + version + ", chars="
                + text.length() + ", truncated=" + truncated
                + outcome.map(o -> ", status=" + o.status()).orElse("") + "]";
    }
}
