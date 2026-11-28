package com.aresstack.spaceharvester.app.chat;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Was ein laufendes Werkzeug des Chats gerade tut („Sucht NDV-Objekte in LIB · 120 gefunden“), für die Statuszeile der
 * gelben Blase (Angelo 28.11.2026: ohne Lebenszeichen sah eine lange Suche aus wie ein Hänger). Wie {@link ChatRoom}
 * gilt der Empfänger für den Thread, auf dem das Werkzeug läuft ({@link #within}); außerhalb eines Turns geht die
 * Meldung ins Leere. So braucht keine der verschachtelten Werkzeugsammlungen eine eigene Weiterleitung.
 */
public final class ToolProgress {

    private static final ThreadLocal<Consumer<String>> REPORT = new ThreadLocal<>();

    private ToolProgress() {
    }

    /** Meldet den Stand des Werkzeugs auf diesem Thread; ohne laufenden Turn nichts. */
    public static void report(String what) {
        Consumer<String> report = REPORT.get();
        if (report != null && what != null && !what.isBlank()) {
            report.accept(what.strip());
        }
    }

    /** Führt {@code work} aus; Meldungen auf diesem Thread gehen währenddessen an {@code report}. */
    public static <T> T within(Consumer<String> report, Supplier<T> work) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(work, "work");
        Consumer<String> before = REPORT.get();
        REPORT.set(report);
        try {
            return work.get();
        } finally {
            if (before == null) {
                REPORT.remove();
            } else {
                REPORT.set(before);
            }
        }
    }
}
