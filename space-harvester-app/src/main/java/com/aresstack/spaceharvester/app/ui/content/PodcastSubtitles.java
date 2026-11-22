package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.podcast.Podcast;

import java.util.ArrayList;
import java.util.List;

/**
 * Welcher Satz eines Abschnitts gerade gesprochen wird (Angelo 22.11.2026). Zeitmarken je Satz gibt es nicht: Die
 * Stelle im Abschnitt wird nach der Textlänge geschätzt, denn die Stimmen sprechen etwa gleich schnell. Jeder Beitrag
 * bekommt dazu ein kleines Gewicht für die Pause beim Sprecherwechsel.
 */
final class PodcastSubtitles {

    /** Gewicht der Pause vor einem Beitrag, in Zeichen. */
    static final int TURN_PAUSE = 12;

    /**
     * Der Satz an einer Stelle.
     *
     * @param text  der ganze Satz
     * @param share wie weit er gesprochen ist, 0 bis 1
     */
    record Cue(String text, double share) {
        static final Cue NONE = new Cue("", 0);
    }

    private PodcastSubtitles() {
    }

    /** Die Pause, mit der die Beiträge beim Vertonen aneinandergefügt werden; sie gehört zu keinem Satz. */
    static final long JOIN_PAUSE_MILLIS = 350;

    /**
     * Der Satz an {@code millis} im Abschnitt der Länge {@code length}. Sind die Startzeiten aller Beiträge beim
     * Vertonen gemessen, bestimmen sie den Beitrag genau; nur die Stelle innerhalb des Beitrags wird nach der
     * Textlänge geschätzt. Sonst (älteres Audio) gilt die Schätzung über den ganzen Abschnitt.
     */
    static Cue at(List<Podcast.Line> lines, long millis, long length) {
        List<Podcast.Line> spoken = lines.stream().filter(line -> !line.text().isBlank()).toList();
        boolean measured = !spoken.isEmpty() && spoken.stream().allMatch(line -> line.startMillis() >= 0);
        for (int i = 1; measured && i < spoken.size(); i++) {
            measured = spoken.get(i).startMillis() > spoken.get(i - 1).startMillis();
        }
        if (!measured) {
            return at(lines, length <= 0 ? 0 : millis / (double) length);
        }
        int index = 0;
        while (index + 1 < spoken.size() && millis >= spoken.get(index + 1).startMillis()) {
            index++;
        }
        long start = spoken.get(index).startMillis();
        boolean last = index == spoken.size() - 1;
        long end = last ? Math.max(length, start + 1) : spoken.get(index + 1).startMillis();
        // Vor dem nächsten Beitrag liegt die Pause; in ihr bleibt der letzte Satz stehen.
        long span = Math.max(1, end - start - (last ? 0 : Math.min(JOIN_PAUSE_MILLIS, (end - start) / 2)));
        return at(List.of(new Podcast.Line(spoken.get(index).host(), spoken.get(index).text())),
                (millis - start) / (double) span);
    }

    /** Der Satz an der Stelle {@code share} (0 bis 1) des Abschnitts mit den Beiträgen {@code lines}. */
    static Cue at(List<Podcast.Line> lines, double share) {
        List<String> sentences = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        long total = 0;
        for (Podcast.Line line : lines) {
            boolean first = true;
            for (String sentence : sentences(line.text())) {
                int weight = sentence.length() + (first ? TURN_PAUSE : 0);
                sentences.add(sentence);
                weights.add(weight);
                total += weight;
                first = false;
            }
        }
        if (sentences.isEmpty()) {
            return Cue.NONE;
        }
        double at = Math.max(0, Math.min(1, share)) * total;
        long before = 0;
        for (int i = 0; i < sentences.size(); i++) {
            int weight = weights.get(i);
            if (at < before + weight || i == sentences.size() - 1) {
                return new Cue(sentences.get(i), Math.max(0, Math.min(1, (at - before) / weight)));
            }
            before += weight;
        }
        return Cue.NONE;
    }

    /** Ob {@code sentence} mit einem kurzen Kürzel samt Punkt endet („z.“, „B.“, „Nr.“, „3.“). */
    private static boolean endsWithAbbreviation(String sentence) {
        String last = sentence.substring(sentence.lastIndexOf(' ') + 1);
        return last.endsWith(".") && last.length() <= 3 && last.length() < sentence.length();
    }

    /** Teilt einen Beitrag nach Satzzeichen; Abkürzungen mit Punkt mitten im Satz („z. B.“) bleiben zusammen. */
    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        // Ein Satz endet an . ! ? … (auch mit schließendem Anführungszeichen), wenn danach ein Großbuchstabe,
        // eine Ziffer oder ein öffnendes Anführungszeichen beginnt.
        for (String part : text.strip().split("(?<=[.!?…][\"“”»«')\\]]?)\\s+(?=[\\p{Lu}\\d\"„“»«(])")) {
            String sentence = part.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            // Nach einer Abkürzung („z. B.“, „Nr. 3“, „3. Quartal“) geht der Satz weiter; ein sehr kurzes Stück
            // hängt am vorigen Satz, statt allein aufzublitzen.
            if (!sentences.isEmpty() && (sentence.length() < 4 || endsWithAbbreviation(sentences.getLast()))) {
                sentences.set(sentences.size() - 1, sentences.getLast() + " " + sentence);
            } else {
                sentences.add(sentence);
            }
        }
        return sentences;
    }
}
