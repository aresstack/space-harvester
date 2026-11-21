package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Entscheidung eines Menschen zu einer Seite oder einer Aussage (ADR 0032, FL6 und Umsetzung W3). Je Ziel gibt es genau
 * einen Eintrag, keine zweite Ablage daneben. Er trägt den Stand und, nur bei Aussagen, die fachliche Prüfung
 * (bestätigt, abgelehnt); mindestens eins von beiden. Beide werden unabhängig gesetzt und zurückgenommen; ein Eintrag
 * ohne Stand und ohne Prüfung wird gelöscht. Stand und Prüfung teilen sich die Revision des Eintrags: Wer mit einer
 * älteren Revision schreibt, bekommt einen Konflikt, auch wenn inzwischen nur das andere Feld geändert wurde.
 *
 * <p>Eine Entscheidung zur Seite hängt am {@link PageKey} und bleibt über neue Fassungen gültig. Eine Entscheidung zur
 * Aussage hängt an deren vollständiger ID ({@code quelle/seite@<Eingabe-Hash>#s<Satz>.v<Verb>}), also an genau dieser
 * Fassung: Ändert sich der Text oder verschwindet die Aussage (etwa Aussagen der Vorstufe nach Entzug der
 * KI-Freigabe), greift sie nicht mehr und erscheint als veraltet, statt still an eine andere Aussage zu wandern.
 *
 * <p>MaltParser und KI-Analysator bilden IDs gleicher Form aus demselben Präfix; dieselbe ID kann in beiden Ablagen
 * verschiedene Aussagen meinen. Deshalb gehört der Analysator ({@link AnalyzerChoice}) bei Aussagen zum Schlüssel
 * ({@link Target}); eine Entscheidung greift nur auf Aussagen aus der Ablage dieses Analysators.
 *
 * @param page        Seite
 * @param statementId ID der Aussage oder {@code null} für die Seite selbst
 * @param analyzer    Ablage der Aussage; {@code null} für die Seite, bei Aussagen ohne Angabe {@link AnalyzerChoice#PARSER}
 * @param standing    gesetzter Stand oder {@code null}, wenn nur die Prüfung gesetzt ist; bei der Seite Pflicht
 * @param review      Prüfung durch einen Menschen ({@code CONFIRMED}, {@code REJECTED}) oder {@code null}; nur bei
 *                    Aussagen, nie {@code UNREVIEWED}
 * @param revision    Änderungszähler ab 1 für optimistisches Sperren, gemeinsam für Stand und Prüfung
 * @param createdBy   wer zuerst entschieden hat ({@code AccessContext#jobOwner()})
 * @param createdAt   wann zuerst
 * @param decidedBy   wer zuletzt entschieden hat
 * @param decidedAt   wann zuletzt
 */
public record StandingDecision(PageKey page, String statementId, AnalyzerChoice analyzer, Standing standing,
                               ParsedStatements.Review review, long revision, String createdBy, Instant createdAt,
                               String decidedBy, Instant decidedAt) {

    public StandingDecision {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision beginnt bei 1");
        }
        if (statementId != null && statementId.isBlank()) {
            throw new IllegalArgumentException("statementId darf nicht leer sein");
        }
        analyzer = Target.analyzer(statementId, analyzer);
        if (review == ParsedStatements.Review.UNREVIEWED) {
            throw new IllegalArgumentException("Keine Prüfung heißt review = null");
        }
        if (statementId == null) {
            Objects.requireNonNull(standing, "standing");
            if (review != null) {
                throw new IllegalArgumentException("Eine Prüfung gibt es nur zu Aussagen");
            }
        } else if (standing == null && review == null) {
            throw new IllegalArgumentException("Stand oder Prüfung muss gesetzt sein");
        }
    }

    /** Nur mit Stand (wie vor W3); eine Aussage gehört dann zur Ablage des MaltParsers. */
    public StandingDecision(PageKey page, String statementId, Standing standing, long revision, String createdBy,
                            Instant createdAt, String decidedBy, Instant decidedAt) {
        this(page, statementId, null, standing, null, revision, createdBy, createdAt, decidedBy, decidedAt);
    }

    /** Ob die Entscheidung die Seite selbst betrifft. */
    public boolean ofPage() {
        return statementId == null;
    }

    /** Ob ein Stand von Hand gesetzt ist (sonst trägt der Eintrag nur die Prüfung). */
    public boolean hasStanding() {
        return standing != null;
    }

    /** Schlüssel des Eintrags. */
    public Target target() {
        return new Target(page, statementId, analyzer);
    }

    /**
     * Ziel einer Entscheidung.
     *
     * @param page        Seite
     * @param statementId ID der Aussage oder {@code null} für die Seite
     * @param analyzer    Ablage der Aussage; {@code null} für die Seite, bei Aussagen ohne Angabe
     *                    {@link AnalyzerChoice#PARSER}
     */
    public record Target(PageKey page, String statementId, AnalyzerChoice analyzer) {

        public Target {
            Objects.requireNonNull(page, "page");
            if (statementId != null && statementId.isBlank()) {
                throw new IllegalArgumentException("statementId darf nicht leer sein");
            }
            analyzer = analyzer(statementId, analyzer);
        }

        /** Ohne Analysator: eine Aussage gehört zur Ablage des MaltParsers (Einträge vor W3). */
        public Target(PageKey page, String statementId) {
            this(page, statementId, null);
        }

        static AnalyzerChoice analyzer(String statementId, AnalyzerChoice analyzer) {
            if (statementId == null) {
                if (analyzer != null) {
                    throw new IllegalArgumentException("Eine Entscheidung zur Seite hat keinen Analysator");
                }
                return null;
            }
            return analyzer == null ? AnalyzerChoice.PARSER : analyzer;
        }
    }
}
