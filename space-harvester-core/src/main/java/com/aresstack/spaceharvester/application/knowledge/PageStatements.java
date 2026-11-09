package com.aresstack.spaceharvester.application.knowledge;

import java.util.Objects;

/**
 * Stand der Aussagenanalyse einer Seite, wie der Reiter „Aussagen“ ihn zeigt (#499). Lesen rechnet nie.
 *
 * @param state    Stand
 * @param reason   bei {@link State#MISSING} oder {@link State#STALE} ein fester Code, sonst {@code null}
 * @param analysis gespeicherte Analyse ohne CoNLL-U oder {@code null} bei {@link State#MISSING}
 */
public record PageStatements(State state, String reason, PageStatementAnalysis analysis) {

    /** Noch nicht manuell analysiert. */
    public static final String NOT_ANALYSED = "not-analysed";
    /** Die Seite hat keine analysierbare Textprojektion. */
    public static final String NO_TEXT = "no-text";
    /** Der Text der Seite hat sich seit der Analyse geändert. */
    public static final String TEXT_CHANGED = "text-changed";
    /** Parser, Modell oder Regeln haben sich seit der Analyse geändert. */
    public static final String ANALYZER_CHANGED = "analyzer-changed";
    /** Die Seite liegt nicht mehr im Bestand. */
    public static final String PAGE_MISSING = "page-missing";

    public enum State {
        /** Keine Analyse gespeichert. */
        MISSING,
        /** Passt zum aktuellen Text und zum eingerichteten Analysator. */
        CURRENT,
        /** Veraltet: nicht als aktuelle Aussage zeigen; erst ein neuer Klick ersetzt sie. */
        STALE
    }

    public PageStatements {
        Objects.requireNonNull(state, "state");
        if ((state == State.MISSING) != (analysis == null)) {
            throw new IllegalArgumentException("analysis genau dann, wenn nicht MISSING");
        }
    }

    public static PageStatements missing(String reason) {
        return new PageStatements(State.MISSING, reason, null);
    }
}
