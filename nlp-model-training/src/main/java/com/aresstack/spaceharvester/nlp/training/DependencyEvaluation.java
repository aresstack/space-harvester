package com.aresstack.spaceharvester.nlp.training;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * UAS und LAS über alle syntaktischen Wörter wie im CoNLL-2018-Shared-Task (Interpunktion zählt mit, Mehrwort-Zeilen
 * und leere Knoten nicht), zusätzlich ohne Interpunktion (UPOS {@code PUNCT}) und je Relation. LAS verlangt das volle
 * Label einschließlich Subtyp ({@code obl:arg} ≠ {@code obl}).
 */
public final class DependencyEvaluation {

    /** Relationen, die die objektzentrierte Auswertung aus #98 braucht; erscheinen immer im Bericht. */
    public static final List<String> CORE_RELATIONS = List.of("nsubj", "nsubj:pass", "obj", "iobj", "obl", "obl:arg",
            "obl:agent", "nmod", "nmod:poss", "aux:pass", "compound:prt", "conj");

    private DependencyEvaluation() {
    }

    public record Metrics(int words, double uas, double las, int wordsWithoutPunct, double uasWithoutPunct,
                          double lasWithoutPunct, SortedMap<String, Relation> relations,
                          Map<String, Integer> topLabelConfusions) {
    }

    /**
     * Je Gold-Relation: Vorkommen in Gold und Vorhersage, richtig mit Kopf und Label; Precision, Recall und F1 in
     * Prozent (Precision 0, wenn die Relation nie vorhergesagt wurde).
     */
    public record Relation(int gold, int predicted, int correct, double precision, double recall, double f1) {
    }

    public static Metrics evaluate(List<ConlluSentence> gold, List<ConlluSentence> predicted) {
        if (gold.size() != predicted.size()) {
            throw new IllegalArgumentException(gold.size() + " Gold-Sätze, aber " + predicted.size() + " vorhergesagte");
        }
        int words = 0, attached = 0, labelled = 0;
        int wordsNoPunct = 0, attachedNoPunct = 0, labelledNoPunct = 0;
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, Integer> confusions = new TreeMap<>();
        CORE_RELATIONS.forEach(relation -> counts.put(relation, new int[3]));
        for (int s = 0; s < gold.size(); s++) {
            List<String[]> g = gold.get(s).words();
            List<String[]> p = predicted.get(s).words();
            if (g.size() != p.size()) {
                throw new IllegalArgumentException("Satz " + gold.get(s).sentenceId() + ": " + g.size()
                        + " Gold-Wörter, aber " + p.size() + " vorhergesagte");
            }
            for (int i = 0; i < g.size(); i++) {
                String[] gw = g.get(i);
                String[] pw = p.get(i);
                String goldLabel = gw[ConlluSentence.DEPREL];
                String predictedLabel = pw[ConlluSentence.DEPREL];
                boolean head = gw[ConlluSentence.HEAD].equals(pw[ConlluSentence.HEAD]);
                boolean label = head && goldLabel.equals(predictedLabel);
                words++;
                attached += head ? 1 : 0;
                labelled += label ? 1 : 0;
                if (!"PUNCT".equals(gw[ConlluSentence.UPOS])) {
                    wordsNoPunct++;
                    attachedNoPunct += head ? 1 : 0;
                    labelledNoPunct += label ? 1 : 0;
                }
                counts.computeIfAbsent(goldLabel, k -> new int[3])[0]++;
                counts.computeIfAbsent(predictedLabel, k -> new int[3])[1]++;
                if (label) {
                    counts.get(goldLabel)[2]++;
                }
                if (!goldLabel.equals(predictedLabel)) {
                    confusions.merge(goldLabel + " → " + predictedLabel, 1, Integer::sum);
                }
            }
        }
        SortedMap<String, Relation> relations = new TreeMap<>();
        counts.forEach((name, c) -> relations.put(name, relation(c[0], c[1], c[2])));
        Map<String, Integer> top = new LinkedHashMap<>();
        confusions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .forEach(entry -> top.put(entry.getKey(), entry.getValue()));
        return new Metrics(words, percent(attached, words), percent(labelled, words), wordsNoPunct,
                percent(attachedNoPunct, wordsNoPunct), percent(labelledNoPunct, wordsNoPunct), relations, top);
    }

    private static Relation relation(int gold, int predicted, int correct) {
        double precision = percent(correct, predicted);
        double recall = percent(correct, gold);
        double f1 = precision + recall == 0 ? 0 : round(2 * precision * recall / (precision + recall));
        return new Relation(gold, predicted, correct, precision, recall, f1);
    }

    static double percent(int part, int whole) {
        return whole == 0 ? 0 : round(100.0 * part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
