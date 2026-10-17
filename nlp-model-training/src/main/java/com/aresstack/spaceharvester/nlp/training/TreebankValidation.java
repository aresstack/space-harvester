package com.aresstack.spaceharvester.nlp.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Prüft eine CoNLL-U-Datei so weit, wie MaltParser und die Evaluation sich darauf verlassen: zehn Spalten, Wort-IDs
 * lückenlos ab 1, HEAD im Satz, genau eine Wurzel, ein Baum ohne Zyklen, UPOS aus dem UD-Inventar. Zählt dabei
 * Mehrwort-Zeilen, leere Knoten, nicht projektive Sätze und die Labels.
 */
public final class TreebankValidation {

    static final Set<String> UPOS = Set.of("ADJ", "ADP", "ADV", "AUX", "CCONJ", "DET", "INTJ", "NOUN", "NUM", "PART",
            "PRON", "PROPN", "PUNCT", "SCONJ", "SYM", "VERB", "X");

    private TreebankValidation() {
    }

    public record Report(int sentences, int words, int multiwordTokens, int emptyNodes, int nonProjectiveSentences,
                         SortedMap<String, Integer> labels, List<String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Report validate(List<ConlluSentence> sentences) {
        int words = 0;
        int multiword = 0;
        int empty = 0;
        int nonProjective = 0;
        SortedMap<String, Integer> labels = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        int index = 0;
        for (ConlluSentence sentence : sentences) {
            index++;
            String where = sentence.sentenceId().isEmpty() ? "Satz " + index : sentence.sentenceId();
            for (String[] line : sentence.lines()) {
                if (ConlluSentence.isMultiwordToken(line)) {
                    multiword++;
                } else if (ConlluSentence.isEmptyNode(line)) {
                    empty++;
                } else if (!ConlluSentence.isWord(line)) {
                    errors.add(where + ": ungültige ID " + line[ConlluSentence.ID]);
                }
            }
            List<String[]> sentenceWords = sentence.words();
            words += sentenceWords.size();
            int[] heads = checkWords(where, sentenceWords, errors);
            if (heads != null) {
                sentenceWords.forEach(word -> labels.merge(word[ConlluSentence.DEPREL], 1, Integer::sum));
                if (!projective(heads)) {
                    nonProjective++;
                }
            }
        }
        return new Report(sentences.size(), words, multiword, empty, nonProjective, labels, List.copyOf(errors));
    }

    /** Köpfe je Wort (Index 1..n, 0 = Wurzel) oder {@code null}, wenn der Satz fehlerhaft ist. */
    private static int[] checkWords(String where, List<String[]> words, List<String> errors) {
        int count = errors.size();
        int[] heads = new int[words.size() + 1];
        int roots = 0;
        for (int i = 0; i < words.size(); i++) {
            String[] word = words.get(i);
            int id = i + 1;
            if (!word[ConlluSentence.ID].equals(Integer.toString(id))) {
                errors.add(where + ": Wort-ID " + word[ConlluSentence.ID] + " statt " + id);
                return null;
            }
            if (!UPOS.contains(word[ConlluSentence.UPOS])) {
                errors.add(where + "/" + id + ": unbekannte UPOS " + word[ConlluSentence.UPOS]);
            }
            String deprel = word[ConlluSentence.DEPREL];
            if (deprel.isEmpty() || deprel.equals("_")) {
                errors.add(where + "/" + id + ": DEPREL fehlt");
            }
            int head;
            try {
                head = Integer.parseInt(word[ConlluSentence.HEAD]);
            } catch (NumberFormatException e) {
                errors.add(where + "/" + id + ": HEAD " + word[ConlluSentence.HEAD] + " ist keine Zahl");
                continue;
            }
            if (head < 0 || head > words.size() || head == id) {
                errors.add(where + "/" + id + ": HEAD " + head + " außerhalb des Satzes");
                continue;
            }
            if ((head == 0) != deprel.equals("root")) {
                errors.add(where + "/" + id + ": root und HEAD 0 gehören zusammen");
            }
            if (head == 0) {
                roots++;
            }
            heads[id] = head;
        }
        if (roots != 1) {
            errors.add(where + ": " + roots + " Wurzeln statt einer");
        }
        if (errors.size() == count && hasCycle(heads)) {
            errors.add(where + ": Zyklus in den Kanten");
        }
        return errors.size() == count ? heads : null;
    }

    private static boolean hasCycle(int[] heads) {
        for (int start = 1; start < heads.length; start++) {
            int node = start;
            for (int steps = 0; node != 0; steps++) {
                if (steps >= heads.length) {
                    return true;
                }
                node = heads[node];
            }
        }
        return false;
    }

    /** Projektiv heißt: Jede Kante überdeckt nur Wörter, die von ihrem Kopf abstammen. */
    static boolean projective(int[] heads) {
        for (int dependent = 1; dependent < heads.length; dependent++) {
            int head = heads[dependent];
            int from = Math.min(head, dependent);
            int to = Math.max(head, dependent);
            for (int between = from + 1; between < to; between++) {
                int node = between;
                while (node != 0 && node != head) {
                    node = heads[node];
                }
                if (node != head) {
                    return false;
                }
            }
        }
        return true;
    }
}
