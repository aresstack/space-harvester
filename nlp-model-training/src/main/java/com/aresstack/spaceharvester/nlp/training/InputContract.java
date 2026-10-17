package com.aresstack.spaceharvester.nlp.training;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Der Eingabevertrag zwischen Modell und Verbraucher (#98): Der Parser sieht nur FORM und UPOS syntaktischer Wörter.
 * Die Vorverarbeitung des Verbrauchers (OpenNLP) liefert weder verlässliche XPOS noch FEATS; damit das Modell nichts
 * lernt, was im Betrieb fehlt, werden LEMMA, XPOS, FEATS, DEPS und MISC schon im Training geleert. Mehrwort-Zeilen
 * ({@code 6-7 im}) und leere Knoten ({@code 3.1}) fallen weg; der Verbraucher löst Verschmelzungen selbst auf.
 */
public final class InputContract {

    /** Spalten, die das Modell als Merkmale nutzen darf; Featuremodelle mit anderen Spalten werden abgelehnt. */
    public static final List<String> COLUMNS = List.of("FORM", "UPOS");
    public static final String FORMAT = "conllu";
    public static final String TOKENIZATION = "ud-syntactic-words";

    private static final String EMPTY = "_";

    private InputContract() {
    }

    /** Trainingsform: Wörter mit FORM, UPOS, HEAD, DEPREL; alle anderen Spalten {@code _}. */
    public static ConlluSentence forTraining(ConlluSentence sentence) {
        return project(sentence, true);
    }

    /** Parsereingabe: wie {@link #forTraining}, zusätzlich ohne HEAD und DEPREL, damit kein Goldwert durchsickert. */
    public static ConlluSentence forParsing(ConlluSentence sentence) {
        return project(sentence, false);
    }

    /** Ersetzt UPOS je Wort, etwa durch vorhergesagte Wortarten; die Länge muss zur Wortzahl passen. */
    public static ConlluSentence withUpos(ConlluSentence sentence, List<String> upos) {
        List<String[]> words = sentence.words();
        if (words.size() != upos.size()) {
            throw new IllegalArgumentException(words.size() + " Wörter, aber " + upos.size() + " Wortarten");
        }
        List<String[]> lines = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            String[] columns = words.get(i).clone();
            columns[ConlluSentence.UPOS] = upos.get(i);
            lines.add(columns);
        }
        return new ConlluSentence(sentence.comments(), lines);
    }

    private static ConlluSentence project(ConlluSentence sentence, boolean keepTree) {
        List<String[]> lines = new ArrayList<>();
        for (String[] word : sentence.words()) {
            String[] columns = new String[ConlluSentence.COLUMNS];
            Arrays.fill(columns, EMPTY);
            columns[ConlluSentence.ID] = word[ConlluSentence.ID];
            columns[ConlluSentence.FORM] = word[ConlluSentence.FORM];
            columns[ConlluSentence.UPOS] = word[ConlluSentence.UPOS];
            if (keepTree) {
                columns[ConlluSentence.HEAD] = word[ConlluSentence.HEAD];
                columns[ConlluSentence.DEPREL] = word[ConlluSentence.DEPREL];
            }
            lines.add(columns);
        }
        return new ConlluSentence(sentence.comments(), lines);
    }
}
