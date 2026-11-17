package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Objects;

/**
 * Eine Aussage der Status-quo-Sicht (ADR 0032, FL6). Verneinung und Verbindlichkeit gehen an jedem Eintrag mit, sonst
 * kehrte die Sicht den Sinn um.
 *
 * @param page          Seite bzw. Dokument der Aussage mit Stand der Analyse
 * @param statement     die gespeicherte Aussage (Beteiligte, Merkmale, Herkunft, Befunde)
 * @param kind          Handlung oder Zustand
 * @param subject       Wortlaut des Handelnden bzw. Trägers oder {@code null}
 * @param subjectKey    dessen Grundform ({@code Mention#key()}) oder {@code null}
 * @param situation     Sachlage für den Vergleich ({@link StatementConflicts}) oder {@code null}, wenn ein Beteiligter
 *                      fehlt, offen ist (Pronomen) oder ein Teilsatz ist
 * @param quality       wirksame Stufe ({@link StandingRules#effectiveQuality}); nur PLAUSIBLE zählt als sicher
 * @param standing      Stand mit Herkunft und Grund
 * @param decision      Entscheidung eines Menschen zu dieser Aussage oder {@code null}
 * @param analysisStale ob die Analyse der Seite veraltet ist ({@link KnowledgeGraph.PageRef#state()})
 */
public record StatusQuoEntry(KnowledgeGraph.PageRef page, ParsedStatements.Statement statement, StatementKind kind,
                             String subject, String subjectKey, Situation situation, ParsedStatements.Quality quality,
                             StatementStanding standing, StandingDecision decision, boolean analysisStale) {

    /** Befund eines Zustandswechsels („wird zuständig“); nicht gedeutet, nur mitgegeben. */
    public static final String STATE_CHANGE = "STATE_CHANGE";

    public StatusQuoEntry {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(standing, "standing");
    }

    /** Ob die Aussage unsicher ist; sie zählt dann nicht für den Stand der Seite. */
    public boolean uncertain() {
        return quality != ParsedStatements.Quality.PLAUSIBLE;
    }

    /** Verneinung (Merkmal, ändert den Stand nicht). */
    public ParsedStatements.Negation negation() {
        return statement.features().negation();
    }

    /** Verbindlichkeit (Merkmal, ändert den Stand nicht). */
    public ParsedStatements.Modality modality() {
        return statement.features().modality();
    }

    /** Herkunft: Original oder Vorstufe. */
    public ParsedStatements.Origin origin() {
        return statement.derivation().origin();
    }

    /** Kennzeichen der Vorstufe als eigenes Feld; {@code NONE} beim Original. */
    public ParsedStatements.StageFlag stageFlag() {
        return statement.derivation().stageFlag();
    }

    /** Ob der Parser einen Zustandswechsel erkannt hat ({@value #STATE_CHANGE}). */
    public boolean stateChange() {
        return statement.findings().contains(STATE_CHANGE);
    }
}
