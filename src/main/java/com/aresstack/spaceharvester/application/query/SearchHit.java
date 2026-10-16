package com.aresstack.spaceharvester.application.query;

import java.util.Objects;

/**
 * Ein Treffer der Suche: aktuelle Kopfdaten der Seite und der Cache-Stand ihres Space.
 *
 * @param page       aktuelle Kopfdaten und {@code rawSha256} wie in der Seitenliste
 * @param generation Cache-Generation des Space, aus dem der Treffer stammt
 * @param position   undurchsichtige Position des Treffers in Sortierung und Indexgeneration, für
 *                   {@link PageSearchIndex#search}; darf nur aus den Werten dieses Treffers bestehen
 */
public record SearchHit(ListedPage page, CacheGeneration generation, String position) {

    public SearchHit {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(generation, "generation");
        if (position == null || position.isEmpty()) {
            throw new IllegalArgumentException("position fehlt");
        }
    }
}
