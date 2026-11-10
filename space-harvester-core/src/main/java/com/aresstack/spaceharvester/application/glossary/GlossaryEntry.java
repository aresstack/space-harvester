package com.aresstack.spaceharvester.application.glossary;

import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Ein Eintrag im Fachglossar (#122 Stufe 2, ADR 0019): ein Fachbegriff in einem Geltungsbereich. Nutzerdaten, kein
 * Index: Herkunft und Prüfstatus sind getrennt, ein bestätigter KI-Vorschlag bleibt als KI-Vorschlag erkennbar.
 *
 * @param id            stabile Kennung aus Suchwert und Geltungsbereich ({@link #idOf}); derselbe Begriff im selben
 *                      Geltungsbereich ist derselbe Eintrag, darum gibt es keine Dubletten
 * @param term          Begriff im Wortlaut
 * @param normalized    Suchwert ({@link TermText#normalize})
 * @param scope         Geltungsbereich: global, eine Quelle oder ein Space
 * @param origin        {@link RelationOrigin#MANUAL} oder {@link RelationOrigin#AI}; ändert sich nie
 * @param status        Prüfstatus
 * @param evidence      Belegstellen aus dem Begriffsindex; bei KI-Vorschlägen die Stellen, die das Modell sah
 * @param evidenceStale ob der letzte Lauf den Begriff nicht mehr belegt fand („Beleg veraltet“); gelöscht wird nichts
 * @param explanation   fachliche Erläuterung des Bearbeiters oder {@code null}; das Modell schreibt keine
 * @param generation    bei KI-Vorschlägen Modell, Promptfassung und Zeitpunkt des Laufs, der sie zuletzt nannte
 * @param createdBy     wer angelegt hat ({@code AccessContext#jobOwner()} oder Kennung des Laufs)
 * @param createdAt     wann
 * @param updatedAt     letzte Änderung
 * @param reviewedBy    wer zuletzt bestätigt, abgelehnt oder wiederhergestellt hat, oder {@code null}
 * @param reviewedAt    wann, oder {@code null}
 * @param revision      zählt jede Änderung, für optimistisches Sperren
 */
public record GlossaryEntry(String id, String term, String normalized, RelationScope scope, RelationOrigin origin,
                            ReviewStatus status, List<RelationEvidence> evidence, boolean evidenceStale,
                            String explanation, String generation, String createdBy, Instant createdAt,
                            Instant updatedAt, String reviewedBy, Instant reviewedAt, long revision) {

    public GlossaryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (origin == RelationOrigin.RULE) {
            throw new IllegalArgumentException("Glossareinträge sind manuell oder von der KI");
        }
        evidence = List.copyOf(evidence);
    }

    /** Kennung: {@code g} und 24 Hexadezimalziffern von SHA-256 über Suchwert und Geltungsbereich. */
    public static String idOf(String term, RelationScope scope) {
        String key = TermText.normalize(term) + '\u0000' + (scope.sourceId() == null ? "" : scope.sourceId())
                + '\u0000' + (scope.spaceKey() == null ? "" : scope.spaceKey());
        try {
            return "g" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    GlossaryEntry reviewed(ReviewStatus next, String actor, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, next, evidence, evidenceStale, explanation,
                generation, createdBy, createdAt, at, actor, at, revision + 1);
    }

    GlossaryEntry explained(String text, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, evidenceStale, text,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1);
    }

    GlossaryEntry proposedAgain(List<RelationEvidence> fresh, String run, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, fresh, false, explanation, run,
                createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1);
    }

    GlossaryEntry stale(Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, true, explanation,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1);
    }

    GlossaryEntry withoutEvidenceOf(String sourceId, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status,
                evidence.stream().filter(item -> !item.sourceId().equals(sourceId)).toList(), evidenceStale,
                explanation, generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1);
    }
}
