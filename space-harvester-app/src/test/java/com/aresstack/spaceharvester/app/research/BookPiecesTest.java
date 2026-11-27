package com.aresstack.spaceharvester.app.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ohne laufenden Auftrag laufen die Teile wie bisher im Auftrag selbst und behalten ihre Reihenfolge. */
class BookPiecesTest {

    @Test
    void withoutABoundJobThePiecesRunInPlace() {
        BookPieces pieces = new BookPieces(2, parent -> {
            throw new AssertionError("ohne Auftrag keine Unteraufträge");
        });
        assertThat(pieces.run("quellensteckbriefe", List.of("a", "b", "c"), item -> item, String::toUpperCase))
                .containsExactly("A", "B", "C");
    }
}
