package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Gespeichertes Ergebnis eines Prozessors für eine Seite. Je Seite und Prozessor gibt es höchstens eines; ein neuer
 * Lauf ersetzt es.
 *
 * @param key              Seite
 * @param processorId      {@link EnrichmentProcessor#id()}
 * @param processorVersion Version, mit der das Ergebnis berechnet wurde
 * @param inputSha256      {@link PageText#inputSha256()} der Eingabe
 * @param revision         Revision der Payload, aus der die Eingabe stammt, oder {@code null}
 * @param status           {@link Status#OK} oder {@link Status#FAILED}
 * @param payloadJson      Ergebnis als JSON-Objekt, bei {@link Status#FAILED} {@code {}}
 * @param warnings         Hinweise des Prozessors
 * @param errorCode        nur bei {@link Status#FAILED}: fester Code, keine Inhalte
 * @param computedAt       Zeitpunkt der Berechnung
 */
public record Enrichment(PageKey key, String processorId, String processorVersion, String inputSha256,
                         String revision, Status status, String payloadJson, List<String> warnings, String errorCode,
                         Instant computedAt) {

    public enum Status {
        OK,
        FAILED
    }

    public Enrichment {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(processorId, "processorId");
        Objects.requireNonNull(processorVersion, "processorVersion");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(computedAt, "computedAt");
        warnings = List.copyOf(warnings);
        if ((status == Status.FAILED) != (errorCode != null)) {
            throw new IllegalArgumentException("errorCode genau dann, wenn status FAILED ist");
        }
    }

    /**
     * Ob das Ergebnis zur aktuellen Eingabe und Prozessorversion passt.
     *
     * @param currentInputSha256 Eingabe-Hash der aktuellen Payload
     * @param currentVersion     aktuelle Version des Prozessors oder {@code null}, wenn es ihn nicht mehr gibt
     */
    public Freshness freshness(String currentInputSha256, String currentVersion) {
        return inputSha256.equals(currentInputSha256) && processorVersion.equals(currentVersion)
                ? Freshness.CURRENT : Freshness.STALE;
    }

    /** Aktualität eines Ergebnisses. */
    public enum Freshness {
        /** Berechnet aus der aktuellen Payload mit der aktuellen Prozessorversion. */
        CURRENT,
        /** Eingabe oder Prozessorversion haben sich geändert; vor Gebrauch neu berechnen. */
        STALE
    }
}
