package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;

/**
 * Die Texte des Prüfschritts „Aussagen prüfen“ (ADR 0032, FL4) an einer Stelle: Urteil, wirksame Stufe und Herkunft.
 * Der Reiter „Aussagen“ und der Reiter „Stand“ zeigen dieselben Worte.
 */
public final class CheckTexts {

    /** Name des Auftrags und des Knopfs. */
    public static final String JOB = "Aussagen prüfen";
    public static final String VERDICT_LABEL = "Urteil des Modells";
    public static final String QUALITY_LABEL = "Stufe";
    public static final String ORIGIN_LABEL = "Herkunft";
    /** Herkunft einer Aussage aus der Korrektur des Prüfschritts ({@link ParsedStatements.Origin#MODELL}). */
    public static final String ORIGIN_MODEL = "Korrektur des Modells";
    /** Der Satz, den das Modell als Korrektur schrieb. */
    public static final String MODEL_SENTENCE_LABEL = "Satz des Modells";

    public static final String NO_MODEL = "„" + JOB + "“ lief nicht: Es ist kein Modell dafür eingestellt "
            + "(Einstellungen › Modelle).";
    public static final String NOT_RELEASED = "„" + JOB + "“ lief nicht: Die Seite ist nicht für die KI freigegeben.";
    public static final String NOT_CURRENT = "„" + JOB + "“ lief nicht: Die Seite hat keine aktuelle Analyse; bitte "
            + "zuerst analysieren.";

    private CheckTexts() {
    }

    /** Das Urteil in Worten; bei „unvollständig“ mit dem Zusatz, ob eine Korrektur als eigene Aussage hinzukam. */
    public static String verdict(ParsedStatements.Check check) {
        return switch (check.verdict()) {
            case STIMMT -> "stimmt";
            case UNVOLLSTAENDIG -> check.corrected() ? "unvollständig (Korrektur ergänzt)" : "unvollständig";
            case FALSCH -> "falsch";
        };
    }

    /** Warum der Prüfschritt nicht lief, als Satz für die Statuszeile. */
    public static String unavailable(CheckUnavailableException.Reason reason) {
        return switch (reason) {
            case NO_MODEL -> NO_MODEL;
            case NOT_RELEASED -> NOT_RELEASED;
            case NOT_CURRENT -> NOT_CURRENT;
        };
    }

    /**
     * Was der Prüfschritt an der Seite hinterlassen hat, etwa „geprüft: 5 Urteile, 1 Korrektur“; {@code null}, solange
     * keine Aussage ein Urteil trägt.
     */
    public static String checked(ParsedStatements result) {
        long verdicts = result.statements().stream().filter(statement -> statement.check() != null).count();
        if (verdicts == 0) {
            return null;
        }
        long corrections = result.statements().stream()
                .filter(statement -> statement.derivation().origin() == ParsedStatements.Origin.MODELL).count();
        return "geprüft: " + verdicts + (verdicts == 1 ? " Urteil" : " Urteile")
                + (corrections == 0 ? "" : ", " + corrections + (corrections == 1 ? " Korrektur" : " Korrekturen"));
    }

    /** Die Stufe in Worten. */
    public static String quality(ParsedStatements.Quality quality) {
        return switch (quality) {
            case PLAUSIBLE -> "plausibel";
            case UNCERTAIN -> "unsicher";
            case INVALID -> "ungültig";
        };
    }

    /**
     * Die wirksame Stufe neben der des Parsers, wenn das Urteil sie gesenkt hat, etwa „unsicher (vom Parser:
     * plausibel)“; sonst {@code null}, denn ein Urteil hebt nie und die Stufe steht dann unverändert.
     */
    public static String lowered(ParsedStatements.Statement statement) {
        ParsedStatements.Quality effective = statement.effectiveQuality();
        return effective == statement.quality() ? null
                : quality(effective) + " (vom Parser: " + quality(statement.quality()) + ")";
    }
}
