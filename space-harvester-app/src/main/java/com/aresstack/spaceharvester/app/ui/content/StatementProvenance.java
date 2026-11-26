package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Herkunft und Prüfstatus einer Aussage in Worten (Fahrplan Analyse-Reiter, Paket A1): eine Stelle für den Reiter
 * „Aussagen“ und den Reiter „Stand“. Unsicheres wird gekennzeichnet, nie ausgeblendet und nie als geprüfte Tatsache
 * dargestellt. Knapp in der Zeile ({@link #line}), Einzelheiten im Overlay ({@link #details}).
 */
public final class StatementProvenance {

    /** Kennzeichen einer unsicheren Aussage: ein Vorschlag des Analysators, keine geprüfte Tatsache. */
    public static final String PROPOSAL = "Vorschlag";
    /** Kennzeichen einer ungültigen Aussage: erkannte Fehlkonstruktion, bleibt sichtbar. */
    public static final String INVALID = "ungültig";
    public static final String CONFIRMED_BY_HAND = "von Hand bestätigt";
    public static final String REJECTED_BY_HAND = "von Hand verworfen";
    public static final String UNCHECKED = "nicht geprüft";
    /** Überschrift des Overlays und Beschriftung des Knopfs am Eintrag. */
    public static final String HEADING = "Herkunft und Prüfstatus";
    public static final String CHIP = "Herkunft";
    /** Name des Analysators {@code maltparser-de}. */
    public static final String MALTPARSER = "MaltParser";
    /** Name des KI-Analysators {@code language-model} (Fahrplan Paket A2). */
    public static final String LANGUAGE_MODEL = AnalyzerNames.MODEL;

    /** Erklärung hinter dem ?-Knopf des Reiters „Aussagen“. */
    public static final String HELP = "Jede Aussage ist eine grammatische Zuordnung des Analysators, keine geprüfte "
            + "Tatsache.\n\n"
            + "„" + PROPOSAL + "“: Der Analysator ist sich bei den Kernbeteiligten nicht sicher (etwa aus dem Kasus "
            + "geschlossen, Pronomen ohne Bezug, kein Handelnder) oder der Prüfschritt hat die Aussage verworfen. Sie "
            + "bleibt sichtbar, zählt aber nicht für Graph, Export und Stand.\n"
            + "„" + INVALID + "“: erkannte Fehlkonstruktion ohne verwendbare Beziehung; ebenfalls sichtbar.\n"
            + "Ohne Kennzeichen: aus sicheren Parserkanten abgeleitet, fachlich trotzdem nicht geprüft.\n\n"
            + "Herkunft: Originalsatz, Vorstufe „Sätze vereinfachen“ (KI) oder Korrektur des Prüfschritts (KI), dazu "
            + "der Analysator (MaltParser oder Sprachmodell).\n"
            + "Prüfstatus: „" + UNCHECKED + "“, oder das Urteil des Schritts „" + CheckTexts.JOB + "“; ein Urteil "
            + "von Hand geht vor.\n\n"
            + "„" + CHIP + "“ am Eintrag zeigt Gründe, Urteile und worauf jede Rolle beruht. Ein Klick auf den Text "
            + "springt zum Beleg.";

    private StatementProvenance() {
    }

    /** Lesbarer Name des Analysators zur Kennung der Analyse; {@code null} bleibt {@code null}. */
    public static String analyzer(String analyzerId) {
        if (analyzerId == null || analyzerId.isBlank()) {
            return null;
        }
        if (analyzerId.startsWith("maltparser")) {
            return MALTPARSER;
        }
        return com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer.ID.equals(analyzerId)
                ? LANGUAGE_MODEL : analyzerId;
    }

    /**
     * Das Kennzeichen in der Zeile: ein Urteil von Hand geht vor, sonst „Vorschlag“ bei wirksamer Stufe unsicher,
     * „ungültig“ bei ungültig; {@code null} bei plausibler, nicht von Hand geprüfter Aussage.
     */
    public static String marker(ParsedStatements.Statement statement) {
        return switch (statement.review()) {
            case CONFIRMED -> CONFIRMED_BY_HAND;
            case REJECTED -> REJECTED_BY_HAND;
            case UNREVIEWED -> switch (statement.effectiveQuality()) {
                case PLAUSIBLE -> null;
                case UNCERTAIN -> PROPOSAL;
                case INVALID -> INVALID;
            };
        };
    }

    /** Ob die Aussage als unsicher gekennzeichnet ist (Vorschlag oder ungültig, nicht von Hand bestätigt). */
    public static boolean flagged(ParsedStatements.Statement statement) {
        String marker = marker(statement);
        return PROPOSAL.equals(marker) || INVALID.equals(marker);
    }

    /** Die Herkunft kurz: Originalsatz, Vorstufe, Korrektur oder Programmanalyse, mit Analysator, wenn bekannt. */
    public static String origin(ParsedStatements.Statement statement, String analyzer) {
        String origin = switch (statement.derivation().origin()) {
            case ORIGINAL -> "Originalsatz";
            case VORSTUFE -> "Vorstufe „" + StatementsTab.SIMPLIFY + "“ (KI)";
            case MODELL -> CheckTexts.ORIGIN_MODEL + " (KI)";
            case PROGRAMM -> "Programmanalyse (KI-Hypothese, Ist-Zustand)";
        };
        return analyzer == null ? origin : origin + ", " + analyzer;
    }

    /** Der Prüfstatus kurz: „nicht geprüft“ oder das Urteil des Prüfschritts. */
    public static String check(ParsedStatements.Statement statement) {
        ParsedStatements.Check check = statement.check();
        if (check == null) {
            return UNCHECKED;
        }
        return switch (check.verdict()) {
            case STIMMT -> "vom Prüfschritt bestätigt";
            case FALSCH -> "vom Prüfschritt verworfen";
            case UNVOLLSTAENDIG -> check.corrected() ? "vom Prüfschritt als unvollständig eingestuft, Korrektur ergänzt"
                    : "vom Prüfschritt als unvollständig eingestuft";
        };
    }

    /** Die knappe Zeile am Eintrag, etwa „Vorschlag · Originalsatz, MaltParser · nicht geprüft“. */
    public static String line(ParsedStatements.Statement statement, String analyzer) {
        String marker = marker(statement);
        return (marker == null ? "" : marker + " · ") + origin(statement, analyzer) + " · " + check(statement);
    }

    /**
     * Die Einzelheiten für das Overlay: Prüfstatus mit Stufe und Gründen, Urteile, Herkunft samt Satz, Grundlage jeder
     * Rolle.
     *
     * @param mentions Erwähnungen nach ID für die Grundlage der Rollen; {@code null} lässt sie aus
     */
    public static String details(ParsedStatements.Statement statement, String analyzer,
                                 Map<String, ParsedStatements.Mention> mentions) {
        List<String> lines = new ArrayList<>();
        String marker = marker(statement);
        lines.add("Kennzeichen: " + (marker == null ? "keins (aus sicheren Parserkanten, fachlich nicht geprüft)"
                : marker));
        ParsedStatements.Quality effective = statement.effectiveQuality();
        String lowered = CheckTexts.lowered(statement);
        lines.add(CheckTexts.QUALITY_LABEL + ": " + (lowered != null ? lowered : CheckTexts.quality(effective)));
        List<String> reasons = statement.effectiveQualityReasons().stream().map(StatementProvenance::reason).toList();
        if (!reasons.isEmpty()) {
            lines.add("Gründe: " + String.join("; ", reasons));
        }
        lines.add("Prüfschritt: " + check(statement));
        if (statement.check() != null) {
            lines.add(CheckTexts.VERDICT_LABEL + ": " + CheckTexts.verdict(statement.check()));
        }
        lines.add("Urteil von Hand: " + switch (statement.review()) {
            case UNREVIEWED -> "keins";
            case CONFIRMED -> "bestätigt";
            case REJECTED -> "verworfen";
        });
        ParsedStatements.Derivation derivation = statement.derivation();
        lines.add(CheckTexts.ORIGIN_LABEL + ": " + origin(statement, analyzer)
                + (derivation.origin() == ParsedStatements.Origin.VORSTUFE
                ? " · Kennzeichen der Vorstufe: " + StatementsTab.stageFlag(derivation.stageFlag()) : ""));
        String sentence = derivation.simplifiedSentence();
        if (sentence != null && !sentence.isBlank()) {
            lines.add((derivation.origin() == ParsedStatements.Origin.MODELL ? CheckTexts.MODEL_SENTENCE_LABEL
                    : "Vereinfachter Satz") + ": „" + sentence.strip() + "“");
        }
        if (mentions != null) {
            for (ParsedStatements.Argument argument : statement.arguments()) {
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention != null) {
                    lines.add(StatementsTab.roleLabel(argument.role()) + " „" + mention.text() + "“: "
                            + basis(argument));
                }
            }
        }
        return String.join("\n", lines);
    }

    /** Worauf die Zuordnung eines Beteiligten beruht. */
    public static String basis(ParsedStatements.Argument argument) {
        String basis = switch (argument.basis()) {
            case PARSER -> "Parserkante (" + argument.relation() + ")";
            case COORDINATION -> "aus der Reihung übernommen";
            case CASE_HEURISTIC -> "aus dem Kasus geschlossen";
            case LEXICON -> "Wortliste";
            case HEURISTIC -> "Heuristik gegen eine fehlerhafte Parserkante";
            case MODEL -> "vom Sprachmodell zugeordnet (KI), Wortlaut im Satz geprüft";
        };
        return basis + (argument.certain() ? ", sicher" : ", unsicher");
    }

    /** Ein Grund der Stufe in Worten; unbekannte Gründe als Kennung. */
    public static String reason(String code) {
        return switch (code) {
            case "NO_PARTICIPANT" -> "kein Beteiligter erkannt";
            case "SELF_RELATION" -> "Handelnder und Betroffener sind dieselbe Stelle";
            case "CASE_CONFLICT" -> "Kasus widerspricht der Satzstruktur";
            case "HEURISTIC_PARTICIPANT" -> "Beteiligter aus dem Kasus geschlossen";
            case "SUBJECT_FROM_LABEL" -> "Subjekt nur aus der Kennung vor dem Doppelpunkt";
            case "UNCERTAIN_PARTICIPANT" -> "Kernbeteiligter nicht durch eine sichere Parserkante gestützt";
            case "UNRESOLVED_PRONOUN" -> "Pronomen mit ungeklärtem Bezug";
            case "DETERMINER_AS_PARTICIPANT" -> "Artikel oder Satzzeichen als Beteiligter";
            case "ROLE_CONFLICT" -> "mehrere Handelnde oder Betroffene";
            case "NO_ACTOR" -> "aktiv, aber kein Handelnder erkannt";
            case ParsedStatements.Statement.NOT_ASSESSED -> "nicht bewertet (ältere Analyse oder anderer Analysator)";
            case ParsedStatements.Check.MODEL_FALSE -> "der Prüfschritt hält die Aussage für falsch";
            case ParsedStatements.Check.MODEL_CORRECTED -> "der Prüfschritt hat die Aussage vervollständigt";
            default -> code;
        };
    }
}
