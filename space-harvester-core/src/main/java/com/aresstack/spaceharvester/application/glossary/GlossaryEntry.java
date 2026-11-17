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
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ein Eintrag im Fachglossar (#122 Stufe 2, ADR 0019): ein Fachbegriff in einem Geltungsbereich. Nutzerdaten, kein
 * Index: Herkunft und Prüfstatus sind getrennt, ein bestätigter KI-Vorschlag bleibt als KI-Vorschlag erkennbar.
 * Vorschläge aus den Regeln der Begriffsableitung (ADR 0032, FL5) haben die Herkunft {@link RelationOrigin#RULE} und
 * verhalten sich wie KI-Vorschläge.
 *
 * @param id            stabile Kennung aus Suchwert und Geltungsbereich ({@link #idOf}); derselbe Begriff im selben
 *                      Geltungsbereich ist derselbe Eintrag, darum gibt es keine Dubletten
 * @param term          Begriff im Wortlaut
 * @param normalized    Suchwert ({@link TermText#normalize})
 * @param scope         Geltungsbereich: global, eine Quelle oder ein Space
 * @param origin        {@link RelationOrigin#MANUAL}, {@link RelationOrigin#AI} oder {@link RelationOrigin#RULE};
 *                      ändert sich nie
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
 * @param classification Einordnung fachlich / technisch / Rolle / unklar mit Urheber und Kennung des Grunds
 *                       (ADR 0032, FL5) oder {@code null} bei Einträgen ohne Einordnung
 * @param definitionProposal Beleg der Begriffsbestimmung („X ist ein Y“) als Position, nie als Text, oder
 *                       {@code null}; die Erläuterung schreibt allein der Mensch
 * @param supportedBy   welche Läufe den Begriff zuletzt genannt haben ({@link RelationOrigin#RULE},
 *                      {@link RelationOrigin#AI} oder beide); jede Phase trägt nur sich ein oder aus, ein ungeprüfter
 *                      Vorschlag fällt erst, wenn ihn keiner mehr stützt. Leer bei manuellen Einträgen.
 */
public record GlossaryEntry(String id, String term, String normalized, RelationScope scope, RelationOrigin origin,
                            ReviewStatus status, List<RelationEvidence> evidence, boolean evidenceStale,
                            String explanation, String generation, String createdBy, Instant createdAt,
                            Instant updatedAt, String reviewedBy, Instant reviewedAt, long revision,
                            Classification classification, RelationEvidence definitionProposal,
                            Set<RelationOrigin> supportedBy) {

    /**
     * Form einer Kennung für den Grund einer Einordnung: Großbuchstaben, Ziffern, Unterstrich, höchstens 64 Zeichen.
     * Nie freier Text eines Modells, denn der könnte Seitentext zitieren, den ein Leser nicht sehen darf.
     */
    public static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** Wer eine Einordnung gesetzt hat; der Mensch geht vor, die KI vor der Regel. */
    public enum ClassifiedBy {
        RULE,
        AI,
        MANUAL
    }

    /**
     * Einordnung eines Eintrags (ADR 0032, FL5).
     *
     * @param category Einordnung
     * @param by       Urheber
     * @param reason   Kennung des Grunds ({@link #REASON}, etwa {@code TECHNICAL_HEAD}) oder {@code null}
     */
    public record Classification(GlossaryDerivation.Category category, ClassifiedBy by, String reason) {

        public Classification {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(by, "by");
            if (reason != null && !REASON.matcher(reason).matches()) {
                throw new IllegalArgumentException("Grund der Einordnung ist keine Kennung");
            }
        }
    }

    /** Ein Eintrag ohne Einordnung und ohne Definitionsvorschlag (wie vor ADR 0032, FL5). */
    public GlossaryEntry(String id, String term, String normalized, RelationScope scope, RelationOrigin origin,
                         ReviewStatus status, List<RelationEvidence> evidence, boolean evidenceStale,
                         String explanation, String generation, String createdBy, Instant createdAt,
                         Instant updatedAt, String reviewedBy, Instant reviewedAt, long revision) {
        this(id, term, normalized, scope, origin, status, evidence, evidenceStale, explanation, generation, createdBy,
                createdAt, updatedAt, reviewedBy, reviewedAt, revision, null, null, supportOf(origin));
    }

    /** Stützende Läufe eines Eintrags, der noch keine Angabe hat: der Lauf, der ihn anlegte. */
    public static Set<RelationOrigin> supportOf(RelationOrigin origin) {
        return origin == RelationOrigin.MANUAL ? Set.of() : Set.of(origin);
    }

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
        evidence = List.copyOf(evidence);
        supportedBy = supportedBy == null ? supportOf(origin) : Set.copyOf(supportedBy);
        if (supportedBy.contains(RelationOrigin.MANUAL)) {
            throw new IllegalArgumentException("Ein Mensch stützt nicht, er legt an");
        }
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
                generation, createdBy, createdAt, at, actor, at, revision + 1, classification,
                definitionProposal, supportedBy);
    }

    GlossaryEntry explained(String text, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, evidenceStale, text,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, classification,
                definitionProposal, supportedBy);
    }

    GlossaryEntry proposedAgain(List<RelationEvidence> fresh, String run, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, fresh, false, explanation, run,
                createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, classification,
                definitionProposal, supportedBy);
    }

    GlossaryEntry stale(Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, true, explanation,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, classification,
                definitionProposal, supportedBy);
    }

    GlossaryEntry classified(Classification next, RelationEvidence definition, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, evidenceStale, explanation,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, next, definition,
                supportedBy);
    }

    /** Ergebnis eines Laufs an einem vorhandenen Eintrag, eine neue Fassung. */
    GlossaryEntry merged(List<RelationEvidence> nextEvidence, String run, Set<RelationOrigin> support,
                         Classification next, RelationEvidence definition, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, nextEvidence, false, explanation, run,
                createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, next, definition, support);
    }

    /** Derselbe Eintrag mit anderen stützenden Läufen, eine neue Fassung. */
    GlossaryEntry supported(Set<RelationOrigin> support, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, evidence, evidenceStale, explanation,
                generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1, classification,
                definitionProposal, support);
    }

    /** Ob noch ein Lauf den Eintrag stützt, ohne {@code without}. */
    Set<RelationOrigin> supportWithout(RelationOrigin without) {
        EnumSet<RelationOrigin> rest = supportedBy.isEmpty() ? EnumSet.noneOf(RelationOrigin.class)
                : EnumSet.copyOf(supportedBy);
        rest.remove(without);
        return rest;
    }

    /** Derselbe Eintrag mit anderen Belegen, ohne neue Fassung (Sichtbarkeit je Aufrufer). */
    GlossaryEntry showing(List<RelationEvidence> visible, RelationEvidence definition) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status, visible, evidenceStale, explanation,
                generation, createdBy, createdAt, updatedAt, reviewedBy, reviewedAt, revision, classification,
                definition, supportedBy);
    }

    GlossaryEntry withoutEvidenceOf(String sourceId, Instant at) {
        return new GlossaryEntry(id, term, normalized, scope, origin, status,
                evidence.stream().filter(item -> !item.sourceId().equals(sourceId)).toList(), evidenceStale,
                explanation, generation, createdBy, createdAt, at, reviewedBy, reviewedAt, revision + 1,
                classification, definitionProposal != null && definitionProposal.sourceId().equals(sourceId) ? null
                : definitionProposal, supportedBy);
    }
}
