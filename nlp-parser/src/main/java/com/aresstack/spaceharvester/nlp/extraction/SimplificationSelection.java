package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Auswahl der Sätze, die die Vorstufe „Sätze vereinfachen“ in der Betriebsart „nur wo nötig“ bekommt (ADR 0032, FL3).
 * Läuft nach dem Parsen des Originals, deshalb hier neben dem Parser und nicht im Core. Ein Satz wird gewählt, wenn er
 * ein Stichpunkt oder eine Kennungszeile ist, kein Verb hat oder keine Aussage der Stufe PLAUSIBLE. Die
 * PLAUSIBLE-Aussagen des Originals bleiben beim Zusammenführen immer erhalten; die Vorstufe ergänzt nur Fehlendes.
 */
public final class SimplificationSelection {

    /** Warum ein Satz gewählt ist. */
    public enum Reason {
        /** Stichpunkt („- Ware prüfen“, „1. …“). */
        BULLET,
        /** Kennungszeile („Stand 2022: …“, „Verantwortlich: …“). */
        LABEL,
        /** Kein Verb (Überschrift, Stichwort). */
        NO_VERB,
        /** Keine Aussage der Stufe PLAUSIBLE. */
        NO_PLAUSIBLE_STATEMENT
    }

    /** Betriebsart der Vorstufe. */
    public enum Mode {
        /** Nur gewählte Sätze (Standard). */
        WHERE_NEEDED,
        /** Alle Sätze. */
        ALWAYS
    }

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-–•*▪·]|\\d{1,2}[.)])\\s");
    /** Bis zu so vielen Wörtern vor dem Doppelpunkt gilt der Satz als Kennungszeile. */
    private static final int LABEL_WORDS = 4;

    private SimplificationSelection() {
    }

    /**
     * Gewählte Sätze (Index ab 0) mit Gründen, in Satzreihenfolge; bei {@link Mode#ALWAYS} jeder Satz, auch ohne Grund.
     */
    public static Map<Integer, Set<Reason>> select(ParsedDocument document, ObjectCentricAnalysis analysis, Mode mode) {
        Map<String, Mention> mentions = StatementQuality.mentions(analysis);
        boolean[] plausible = new boolean[document.sentences().size()];
        for (Statement statement : analysis.statements()) {
            if (StatementQuality.assess(mentions, analysis, statement).level() == StatementQuality.Level.PLAUSIBLE) {
                plausible[statement.sentence()] = true;
            }
        }
        Map<Integer, Set<Reason>> result = new LinkedHashMap<>();
        for (Sentence sentence : document.sentences()) {
            Set<Reason> reasons = EnumSet.noneOf(Reason.class);
            if (BULLET.matcher(sentence.text()).find()) {
                reasons.add(Reason.BULLET);
            }
            if (label(sentence.tokens())) {
                reasons.add(Reason.LABEL);
            }
            if (sentence.tokens().stream().noneMatch(t -> t.hasUpos("VERB", "AUX"))) {
                reasons.add(Reason.NO_VERB);
            }
            if (!plausible[sentence.index()]) {
                reasons.add(Reason.NO_PLAUSIBLE_STATEMENT);
            }
            if (!reasons.isEmpty() || mode == Mode.ALWAYS) {
                result.put(sentence.index(), Set.copyOf(reasons));
            }
        }
        return result;
    }

    private static boolean label(List<Token> tokens) {
        int words = 0;
        for (Token token : tokens) {
            if (":".equals(token.form())) {
                return words > 0 && words <= LABEL_WORDS;
            }
            if (!token.hasUpos("PUNCT")) {
                words++;
            }
            if (words > LABEL_WORDS) {
                return false;
            }
        }
        return false;
    }
}
