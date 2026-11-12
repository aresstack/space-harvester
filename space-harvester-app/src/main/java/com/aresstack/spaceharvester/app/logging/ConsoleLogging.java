package com.aresstack.spaceharvester.app.logging;

import com.aresstack.spaceharvester.app.settings.ConsoleOutput;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Die Terminal-Ausgabe der App (Einstellungen › Allgemein › Terminal-Ausgabe, {@value ConsoleOutput#KEY}). Beim Start
 * leitet {@link #install()} {@code java.util.logging} (Quellen und KeePass aus dem Enterprise AI Client, Bibliotheken)
 * und damit auch {@code System.Logger} (Core) in SLF4J um. So laufen alle Meldungen durch Logback: ins Terminal nur
 * ab der gewählten Stufe ({@link ConsoleThreshold} in {@code logback.xml}), in die Protokolldatei immer. Vorher schrieb
 * {@code java.util.logging} zweizeilig und getrennt auf stderr, Logback auf stdout, und bei vielen parallelen
 * Verbindungen liefen beide Ausgaben durcheinander.
 *
 * <p>Ohne {@link #install()} (Tests, der Dienst, andere Einstiege) bleibt alles wie bisher.
 */
public final class ConsoleLogging {

    /** {@code null}, solange {@link #install()} nicht lief: dann filtert {@link ConsoleThreshold} nichts. */
    private static volatile ConsoleOutput output;

    private ConsoleLogging() {
    }

    /**
     * Einmal ganz am Anfang von {@code main}: leitet {@code java.util.logging} um (nur dessen Terminal-Ausgabe entfällt,
     * andere Handler bleiben) und setzt die Terminal-Ausgabe auf den Standard der App, bis die Einstellungen gelesen
     * sind.
     */
    public static synchronized void install() {
        if (output != null) {
            return;
        }
        Logger root = LogManager.getLogManager().getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                root.removeHandler(handler);
            }
        }
        root.addHandler(new JulBridge());
        output = ConsoleOutput.DEFAULT;
    }

    /** Stellt die Terminal-Ausgabe sofort um, ohne Neustart; ohne {@link #install()} ohne Wirkung. */
    public static void apply(ConsoleOutput wanted) {
        if (output != null && wanted != null) {
            output = wanted;
        }
    }

    /** Was das Terminal gerade zeigt; {@code null}, solange {@link #install()} nicht lief. */
    public static ConsoleOutput current() {
        return output;
    }

    /** Nur für Tests: wie vor {@link #install()}, ohne die Umleitung zurückzunehmen. */
    static void reset() {
        output = null;
    }
}
