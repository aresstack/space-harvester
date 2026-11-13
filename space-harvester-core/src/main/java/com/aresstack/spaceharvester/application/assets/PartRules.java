package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Was der Adapter beim Lesen eines Behälters von außen erfährt (ADR 0026, Punkte 5 und 13): den Hash des äußeren
 * Behälters für Kennungen nach Position und welche am Inhalt erkannten Typen eigene Dokumente werden
 * ({@code export.attachments.media-types}). Nicht zugelassene Teile stehen mit {@code media-type-excluded} im
 * Verzeichnis; Archive und Mails werden trotzdem geöffnet.
 *
 * @param containerSha256 SHA-256 des äußeren Behälters (64 Hexziffern); fehlt er, bildet der Adapter ihn selbst
 * @param documentTypes   ob ein Medientyp (ohne Parameter, klein) ein eigenes Dokument wird
 */
public record PartRules(Optional<String> containerSha256, Predicate<String> documentTypes) {

    /** Ohne bekannten Hash, alle Typen zugelassen. */
    public static final PartRules ALL = new PartRules(Optional.empty(), type -> true);

    public PartRules {
        containerSha256 = containerSha256 == null ? Optional.empty()
                : containerSha256.map(sha -> sha.startsWith("sha256:") ? sha.substring("sha256:".length()) : sha);
        Objects.requireNonNull(documentTypes, "documentTypes");
    }

    /** Dieselben Typen für einen Behälter mit diesem Hash ({@code sha256:<hex>} oder Hexziffern). */
    public PartRules forContainer(String sha256) {
        return new PartRules(Optional.ofNullable(sha256), documentTypes);
    }
}
