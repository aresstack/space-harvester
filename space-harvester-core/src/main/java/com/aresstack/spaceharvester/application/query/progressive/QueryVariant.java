package com.aresstack.spaceharvester.application.query.progressive;

import java.util.Objects;

/**
 * Eine Suchanfrage der progressiven Suche.
 *
 * @param text     Suchbegriffe, nie eine Abfragesprache wie CQL
 * @param priority 0 für die ursprüngliche Anfrage, danach 1 = genaueste Variante; kleinere zuerst
 */
public record QueryVariant(String text, int priority) {

    public QueryVariant {
        Objects.requireNonNull(text, "text");
        text = text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Variante ohne Text");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("Priorität ist nicht negativ");
        }
    }
}
