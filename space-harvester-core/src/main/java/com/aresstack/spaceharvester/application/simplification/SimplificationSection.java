package com.aresstack.spaceharvester.application.simplification;

import java.util.List;
import java.util.Objects;

/**
 * Ein Abschnitt für einen Modellaufruf der Vorstufe (ADR 0032, FL3): die gewählten Sätze und ihr Kontext. Der Kontext
 * sind alle übrigen Zeilen des Abschnitts (Überschriften, Kennungszeilen, nicht gewählte Sätze); sie gehen wie in der
 * Messung mit an das Modell, damit es Pronomen auflösen und Stichpunkte zu ganzen Sätzen machen kann, ihre Ausgaben
 * werden aber nicht übernommen.
 *
 * @param context   die übrigen Zeilen des Abschnitts
 * @param sentences die gewählten Sätze
 */
public record SimplificationSection(List<Sentence> context, List<Sentence> sentences) {

    /**
     * Ein Satz der Seite.
     *
     * @param index Index des Satzes in der Seite (Satznummer des Analysators, ab 0)
     * @param text  der Satztext, eine Zeile
     */
    public record Sentence(int index, String text) {

        public Sentence {
            if (index < 0) {
                throw new IllegalArgumentException("index darf nicht negativ sein");
            }
            Objects.requireNonNull(text, "text");
            text = text.replaceAll("\\s+", " ").strip();
        }
    }

    public SimplificationSection {
        context = List.copyOf(context);
        sentences = List.copyOf(sentences);
        if (sentences.isEmpty()) {
            throw new IllegalArgumentException("Ein Abschnitt braucht mindestens einen gewählten Satz");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Sentence line : java.util.stream.Stream.concat(context.stream(), sentences.stream()).toList()) {
            if (!seen.add(line.index())) {
                throw new IllegalArgumentException("Satz " + line.index() + " doppelt im Abschnitt");
            }
        }
    }

    /** Alle Zeilen, Kontext und gewählte Sätze, in Textreihenfolge (nach Satzindex). */
    public List<Sentence> lines() {
        java.util.List<Sentence> lines = new java.util.ArrayList<>(context);
        lines.addAll(sentences);
        lines.sort(java.util.Comparator.comparingInt(Sentence::index));
        return List.copyOf(lines);
    }
}
