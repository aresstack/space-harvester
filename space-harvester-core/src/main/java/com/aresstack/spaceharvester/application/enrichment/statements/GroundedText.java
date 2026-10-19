package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.Objects;

/**
 * Wörter des Textes mit ihrer Stelle: der Bereich in der Projektion und der Text, der dort steht.
 *
 * @param span Bereich
 * @param text Wortlaut
 */
public record GroundedText(TextSpan span, String text) {

    public GroundedText {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        // Ohne Wortlaut: toString landet leicht im Log.
        return "GroundedText@" + span;
    }
}
