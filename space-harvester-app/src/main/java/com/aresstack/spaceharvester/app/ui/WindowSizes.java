package com.aresstack.spaceharvester.app.ui;

import java.awt.Dimension;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Gemerkte Fenstergrößen der Dialoge (Bedienzustand, keine Konfiguration): in den Benutzer-Einstellungen von Java,
 * unter Windows also im Benutzerzweig der Registry. Fehlt der Speicher oder ist er gesperrt, gilt einfach die
 * Standardgröße.
 */
public final class WindowSizes {

    private static final int MIN = 200;

    private WindowSizes() {
    }

    static Optional<Dimension> recall(String key) {
        try {
            Preferences node = node();
            int width = node.getInt(key + ".width", -1);
            int height = node.getInt(key + ".height", -1);
            return width >= MIN && height >= MIN ? Optional.of(new Dimension(width, height)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static void remember(String key, Dimension size) {
        if (size.width < MIN || size.height < MIN) {
            return;
        }
        try {
            Preferences node = node();
            node.putInt(key + ".width", size.width);
            node.putInt(key + ".height", size.height);
        } catch (RuntimeException e) {
            // Ohne Speicher bleibt es bei der Standardgröße.
        }
    }

    /** Ein gemerkter Zahlenwert des Bedienzustands, etwa die Breite einer Seitenleiste. */
    public static int recallInt(String key, int fallback) {
        try {
            return node().getInt(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static void rememberInt(String key, int value) {
        try {
            node().putInt(key, value);
        } catch (RuntimeException e) {
            // Ohne Speicher gilt beim nächsten Start der Standard.
        }
    }

    private static Preferences node() {
        return Preferences.userRoot().node("com/aresstack/spaceharvester/app/windows");
    }
}
