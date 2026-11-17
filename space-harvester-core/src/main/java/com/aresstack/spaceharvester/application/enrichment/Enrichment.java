package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Gespeichertes Ergebnis eines Prozessors für ein Dokument: eine Seite, einen Anhang oder eine enthaltene Datei (ADR
 * 0026, Punkt 10). Je Dokument, Abschnitt und Prozessor gibt es höchstens eines; ein neuer Lauf ersetzt es. Für Seiten
 * ändert sich nichts: {@code document} ist die Seite selbst, {@code section} 0.
 *
 * @param key              Seite, unter der das Dokument steht (und deren Rechte es hat)
 * @param processorId      {@link EnrichmentProcessor#id()}
 * @param processorVersion Version, mit der das Ergebnis berechnet wurde
 * @param inputSha256      {@link PageText#inputSha256()} der Eingabe
 * @param revision         Revision der Payload, aus der die Eingabe stammt, oder {@code null}
 * @param status           {@link Status#OK} oder {@link Status#FAILED}
 * @param payloadJson      Ergebnis als JSON-Objekt, bei {@link Status#FAILED} {@code {}}
 * @param warnings         Hinweise des Prozessors
 * @param errorCode        nur bei {@link Status#FAILED}: fester Code, keine Inhalte
 * @param computedAt       Zeitpunkt der Berechnung
 * @param document         das Dokument; {@link DocumentKey#page()} ist {@code key}
 * @param section          Abschnitt eines großen Dokuments ab 0, sonst 0
 * @param extraction       nur bei Dokumenten: undurchsichtige Kennung der Extraktion, für die {@code revision} (Hash
 *                         des Lesetextes des Dokuments) gebildet wurde; ändert sie sich, wird der Hash einmal neu
 *                         gebildet (ADR 0026, AP9a); sonst {@code null}
 */
public record Enrichment(PageKey key, String processorId, String processorVersion, String inputSha256,
                         String revision, Status status, String payloadJson, List<String> warnings, String errorCode,
                         Instant computedAt, DocumentKey document, int section, String extraction) {

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
        document = document == null ? DocumentKey.page(key) : document;
        if (!document.page().equals(key)) {
            throw new IllegalArgumentException("Das Dokument gehört zu einer anderen Seite");
        }
        if (section < 0) {
            throw new IllegalArgumentException("section darf nicht negativ sein");
        }
    }

    /** Ohne Kennung der Extraktion. */
    public Enrichment(PageKey key, String processorId, String processorVersion, String inputSha256, String revision,
                      Status status, String payloadJson, List<String> warnings, String errorCode, Instant computedAt,
                      DocumentKey document, int section) {
        this(key, processorId, processorVersion, inputSha256, revision, status, payloadJson, warnings, errorCode,
                computedAt, document, section, null);
    }

    /** Dasselbe Ergebnis mit anderem Hash des Lesetextes und anderer Kennung der Extraktion, ohne neu zu rechnen. */
    public Enrichment withBasis(String revision, String extraction) {
        return new Enrichment(key, processorId, processorVersion, inputSha256, revision, status, payloadJson, warnings,
                errorCode, computedAt, document, section, extraction);
    }

    /** Ergebnis für die Seite selbst (wie vor ADR 0026). */
    public Enrichment(PageKey key, String processorId, String processorVersion, String inputSha256, String revision,
                      Status status, String payloadJson, List<String> warnings, String errorCode, Instant computedAt) {
        this(key, processorId, processorVersion, inputSha256, revision, status, payloadJson, warnings, errorCode,
                computedAt, null, 0);
    }

    /** Ob das Ergebnis zu einem Anhang oder einer enthaltenen Datei gehört, nicht zur Seite selbst. */
    public boolean ofDocument() {
        return !document.isPage();
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
