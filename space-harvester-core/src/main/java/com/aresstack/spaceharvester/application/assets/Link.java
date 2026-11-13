package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.Optional;

/**
 * Verweisziel an einem Block (ADR 0026, Punkt 3), getrennt vom Text, damit die Markdown-Ansicht es zeigt und Analysen
 * es nicht als Text lesen.
 *
 * @param target Ziel, wie es in der Datei steht
 * @param label  sichtbarer Text des Verweises, wenn er sich vom Ziel unterscheidet
 */
public record Link(String target, Optional<String> label) {

    public Link {
        Objects.requireNonNull(target, "target");
        if (target.isBlank()) {
            throw new IllegalArgumentException("Verweis ohne Ziel");
        }
        label = label == null ? Optional.empty() : label.filter(text -> !text.isBlank());
    }
}
