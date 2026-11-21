package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;

/**
 * Sichtbare Namen und Texte der Aussagenanalysatoren (Fahrplan A2/A3), gemeinsam für Einstellungen › Modelle, den
 * Bestätigungsdialog „Analysieren“ und das Ablage-Auswahlfeld in der Reiterzeile. Der KI-Analysator heißt sichtbar
 * „Erprobung“; Vorgabe bleibt der MaltParser.
 */
public final class AnalyzerNames {

    /** Der regelbasierte Analysator (Vorgabe). */
    public static final String PARSER = "MaltParser";
    /** Der Analysator über den eingestellten KI-Dienst. */
    public static final String MODEL = "Sprachmodell (KI, Erprobung)";
    /** Kurzname für das kompakte Auswahlfeld in der Reiterzeile. */
    public static final String PARSER_SHORT = "MaltParser";
    public static final String MODEL_SHORT = "KI (Erprobung)";
    /** Anzeige im geschlossenen Auswahlfeld der Reiterzeile (UX/P1: bei 960 px einzeilig); die Liste zeigt die Kurznamen. */
    public static final String PARSER_COMPACT = "Parser";
    public static final String MODEL_COMPACT = "KI";
    /** Tooltip des Ablage-Auswahlfelds. */
    public static final String STORE_TOOLTIP = "Aussagen, Graph, Stand, Begriffe und Export lesen aus dieser Ablage";
    /** Pflichttext des ?-Knopfs (Auflage des Architekten): zwei Sätze zur Güte. */
    public static final String HELP = "Der KI-Analysator ist eine Erprobung: Auf den erfundenen Prüftexten des "
            + "Prüfstands ist er nicht besser als der MaltParser. Er macht dabei mehr Fehler, die plausibel aussehen, "
            + "deshalb steht an jeder Aussage, welcher Analysator sie geliefert hat.";

    private AnalyzerNames() {
    }

    /** Der lange Name eines Analysators. */
    public static String name(AnalyzerChoice choice) {
        return choice == AnalyzerChoice.MODEL ? MODEL : PARSER;
    }

    /** Der kurze Name für die Reiterzeile. */
    public static String shortName(AnalyzerChoice choice) {
        return choice == AnalyzerChoice.MODEL ? MODEL_SHORT : PARSER_SHORT;
    }

    /** Der Name im geschlossenen Auswahlfeld der Reiterzeile. */
    public static String compactName(AnalyzerChoice choice) {
        return choice == AnalyzerChoice.MODEL ? MODEL_COMPACT : PARSER_COMPACT;
    }

    /** Der Analysator zu einem sichtbaren Namen (lang oder kurz); Unbekanntes ist der MaltParser. */
    public static AnalyzerChoice of(Object name) {
        return MODEL.equals(name) || MODEL_SHORT.equals(name) ? AnalyzerChoice.MODEL : AnalyzerChoice.PARSER;
    }
}
