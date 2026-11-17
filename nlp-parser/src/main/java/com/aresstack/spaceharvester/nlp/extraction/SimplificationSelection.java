package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Auswahl der Sätze, die die Vorstufe „Sätze vereinfachen“ in der Betriebsart „nur wo nötig“ bekommt (ADR 0032, FL3).
 * Läuft nach dem Parsen des Originals, deshalb hier neben dem Parser und nicht im Core. Ein Satz wird gewählt, wenn er
 * ein Stichpunkt oder eine Kennungszeile ist, kein Verb hat oder keine Aussage der Stufe PLAUSIBLE. Die
 * PLAUSIBLE-Aussagen des Originals bleiben beim Zusammenführen immer erhalten; die Vorstufe ergänzt nur Fehlendes
 * ({@link #merge}). Zeilen ohne Verb (Überschriften, reine Kennungen, Stichworte) sind Zusammenhang, nie selbst zu
 * vereinfachen; ein Abschnitt geht nur ans Modell, wenn er einen zu vereinfachenden Satz enthält ({@link #sections}).
 * Prüfstand, App und Core-Auftrag nutzen dieselben Funktionen.
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

    /** Ob ein gewählter Satz selbst vereinfacht wird: nicht bei Zeilen ohne Verb, die sind nur Zusammenhang. */
    public static boolean simplify(Set<Reason> reasons) {
        return reasons != null && !reasons.contains(Reason.NO_VERB);
    }

    /**
     * Ein Abschnitt (Index ab 0, einschließlich) mit den Sätzen darin, die vereinfacht werden; die übrigen Sätze des
     * Abschnitts gehen als Zusammenhang mit.
     */
    public record Section(int first, int last, List<Integer> simplify) {

        public Section {
            simplify = List.copyOf(simplify);
        }
    }

    /**
     * Abschnitte mit Modellaufruf: Ein Abschnitt beginnt an einer Überschrift (Zeile ohne Verb, kein Stichpunkt, nicht
     * auf „:“ endend) oder am Dokumentanfang; er zählt nur, wenn er mindestens einen zu vereinfachenden Satz hat.
     */
    public static List<Section> sections(ParsedDocument document, Map<Integer, Set<Reason>> selection) {
        List<Section> result = new ArrayList<>();
        int first = 0;
        List<Integer> simplify = new ArrayList<>();
        List<Sentence> sentences = document.sentences();
        for (int i = 0; i < sentences.size(); i++) {
            if (i > first && heading(sentences.get(i))) {
                if (!simplify.isEmpty()) {
                    result.add(new Section(first, i - 1, simplify));
                }
                first = i;
                simplify = new ArrayList<>();
            }
            if (simplify(selection.get(i))) {
                simplify.add(i);
            }
        }
        if (!simplify.isEmpty()) {
            result.add(new Section(first, sentences.size() - 1, simplify));
        }
        return result;
    }

    private static boolean heading(Sentence sentence) {
        String text = sentence.text().strip();
        return !text.isEmpty() && !text.endsWith(":") && !BULLET.matcher(sentence.text()).find()
                && sentence.tokens().stream().noneMatch(t -> t.hasUpos("VERB", "AUX"));
    }

    /**
     * Führt je Originalsatz zusammen (Satz ab 0): PLAUSIBLE-Aussagen des Originals bleiben; in vereinfachten Sätzen
     * kommen Aussagen der Vorstufe hinzu, deren Verb dort fehlt; eine Aussage des Originals unter PLAUSIBLE weicht nur
     * einer Vorstufen-Aussage mit demselben Verb. Nicht vereinfachte Sätze bleiben, wie sie sind.
     *
     * @param original   Aussagen des Originals
     * @param simplified Aussagen der Vorstufe, {@code sentence} liefert ihren Originalsatz
     * @param simplify   vereinfachte Originalsätze
     */
    public static <S> List<S> merge(List<S> original, List<S> simplified, Set<Integer> simplify,
                                    ToIntFunction<S> sentence, Predicate<S> plausible, BiPredicate<S, S> sameVerb) {
        return merge(original, simplified, simplify, sentence, plausible, sameVerb, (vorstufe, own) -> vorstufe);
    }

    /**
     * Wie {@link #merge(List, List, Set, ToIntFunction, Predicate, BiPredicate)}, dazu die Merkmale (Verneinung,
     * Verbindlichkeit, Zeitbezug) aus dem Original: Weicht eine Aussage des Originals einer Vorstufen-Aussage mit
     * demselben Verb, bekommt diese die Merkmale der Original-Aussage ({@code adopt}). Nur eine Vorstufen-Aussage zu
     * einem Verb, zu dem das Original im selben Satz keine Aussage hat, behält ihre eigenen; sie ist an ihrer Herkunft
     * als abgeleitet erkennbar. Die eine Funktion für Prüfstand, App und Auftrag.
     *
     * @param adopt Vorstufen-Aussage mit den Merkmalen der Original-Aussage desselben Verbs
     */
    public static <S> List<S> merge(List<S> original, List<S> simplified, Set<Integer> simplify,
                                    ToIntFunction<S> sentence, Predicate<S> plausible, BiPredicate<S, S> sameVerb,
                                    BinaryOperator<S> adopt) {
        List<S> result = new ArrayList<>();
        int last = -1;
        for (S s : original) {
            last = Math.max(last, sentence.applyAsInt(s));
        }
        for (S s : simplified) {
            last = Math.max(last, sentence.applyAsInt(s));
        }
        for (int k = 0; k <= last; k++) {
            int index = k;
            List<S> own = original.stream().filter(s -> sentence.applyAsInt(s) == index).toList();
            if (!simplify.contains(k)) {
                result.addAll(own);
                continue;
            }
            List<S> kept = own.stream().filter(plausible).toList();
            List<S> added = simplified.stream().filter(s -> sentence.applyAsInt(s) == index
                    && kept.stream().noneMatch(o -> sameVerb.test(o, s))).toList();
            result.addAll(kept);
            for (S s : added) {
                S replaced = own.stream().filter(o -> !plausible.test(o) && sameVerb.test(s, o)).findFirst()
                        .orElse(null);
                result.add(replaced == null ? s : adopt.apply(s, replaced));
            }
            own.stream().filter(s -> !plausible.test(s) && added.stream().noneMatch(n -> sameVerb.test(n, s)))
                    .forEach(result::add);
        }
        return result;
    }
}
