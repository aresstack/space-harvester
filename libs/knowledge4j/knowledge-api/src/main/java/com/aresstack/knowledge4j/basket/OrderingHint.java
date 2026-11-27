package com.aresstack.knowledge4j.basket;

import java.util.Objects;

/**
 * A neutral ordering hint from the reader: {@code term} belongs under {@code parent}, as the reader sorted found terms
 * after the search. It travels in the basket to the writing as a non-binding hint for ordering and merging near
 * questions; never a structure the book must follow and never a measure of the book. Where the hint came from (a mind
 * map, cards, a list) is not part of it. Immutable.
 *
 * @param term   the term the reader placed
 * @param parent the term it belongs under
 */
public record OrderingHint(String term, String parent) {

    public OrderingHint {
        term = Objects.requireNonNull(term, "term").trim();
        parent = Objects.requireNonNull(parent, "parent").trim();
        if (term.isEmpty() || parent.isEmpty()) {
            throw new IllegalArgumentException("an ordering hint needs a term and a parent");
        }
        if (term.equalsIgnoreCase(parent)) {
            throw new IllegalArgumentException("a term does not belong under itself");
        }
    }
}
