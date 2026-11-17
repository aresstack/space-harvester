package com.aresstack.spaceharvester.application.knowledge;

import java.util.Optional;

/**
 * Die eine Stelle, die für Auswertungen über Seiten hinweg beantwortet, ob eine gespeicherte Aussage zählt und mit
 * welcher Stufe (ADR 0032). Auswertungen wie die Ableitung der Begriffe (FL5) fragen nur hier; der Prüfschritt (FL4)
 * hängt hier die wirksame Stufe ein, der Stand je Aussage (FL6) später, was nicht mehr gilt.
 *
 * <p>Heute: Eine vom Menschen abgelehnte Aussage ({@link ParsedStatements.Review#REJECTED}) zählt nie; jede andere
 * zählt mit ihrer Extraktionsqualität.
 */
public final class CountedStatements {

    private CountedStatements() {
    }

    /** Stufe, mit der die Aussage zählt, oder leer, wenn sie nicht zählt. */
    public static Optional<ParsedStatements.Quality> level(ParsedStatements.Statement statement) {
        if (statement.review() == ParsedStatements.Review.REJECTED) {
            return Optional.empty();
        }
        return Optional.of(statement.quality());
    }

    /** Ob die Aussage mit der Stufe {@link ParsedStatements.Quality#PLAUSIBLE} zählt. */
    public static boolean plausible(ParsedStatements.Statement statement) {
        return level(statement).filter(level -> level == ParsedStatements.Quality.PLAUSIBLE).isPresent();
    }
}
