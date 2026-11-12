package com.aresstack.spaceharvester.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;

/**
 * Filter am Terminal-Appender in {@code logback.xml}: lässt nur Meldungen ab der gewählten Terminal-Ausgabe durch
 * ({@link ConsoleLogging}); der Datei-Appender hat keinen Filter und bekommt weiter alles. Die Stufe kann sich jederzeit
 * ändern, der Filter liest sie bei jeder Meldung.
 */
public final class ConsoleThreshold extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return shows(ConsoleLogging.current(), event.getLevel()) ? FilterReply.NEUTRAL : FilterReply.DENY;
    }

    /** Ob eine Meldung der Stufe {@code level} im Terminal erscheint; ohne Einstellung ({@code null}) jede. */
    static boolean shows(ConsoleOutput output, Level level) {
        if (output == null) {
            return true;
        }
        return switch (output) {
            case OFF -> false;
            case ERROR -> level.isGreaterOrEqual(Level.ERROR);
            case WARN -> level.isGreaterOrEqual(Level.WARN);
            case INFO -> level.isGreaterOrEqual(Level.INFO);
            case ALL -> true;
        };
    }
}
