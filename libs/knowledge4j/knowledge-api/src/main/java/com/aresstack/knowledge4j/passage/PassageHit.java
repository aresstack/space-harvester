package com.aresstack.knowledge4j.passage;

import java.util.Objects;

/** One passage found by a search, with the search's score (higher is better; comparable only within one search). */
public record PassageHit(PassageView passage, double score) {

    public PassageHit {
        Objects.requireNonNull(passage, "passage");
    }
}
