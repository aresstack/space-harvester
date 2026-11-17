package com.aresstack.spaceharvester.application.simplification;

import java.util.List;
import java.util.Objects;

/**
 * Ein Abschnitt für einen Modellaufruf der Vorstufe (ADR 0032, FL3): die gewählten Sätze und ihr Kontext. Der Kontext
 * sind Überschriften und Kennungszeilen des Abschnitts; sie gehen mit an das Modell, damit es Stichpunkte zu ganzen
 * Sätzen machen kann, ihre Ausgaben werden aber nicht übernommen.
 *
 * @param context   Überschriften und Kennungszeilen, in Textreihenfolge
 * @param sentences die gewählten Sätze, in Textreihenfolge
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
    }
}
