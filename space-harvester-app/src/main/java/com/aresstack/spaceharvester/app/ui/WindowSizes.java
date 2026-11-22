package com.aresstack.spaceharvester.app.ui;

import java.awt.Dimension;
import java.util.Map;
import java.util.Optional;

/**
 * Gemerkte Fenstergrößen der Dialoge und Zahlen des Bedienzustands (keine Konfiguration): im Application State
 * ({@link AppState}, {@code app-state.json}). Fehlt der Speicher oder ist er gesperrt, gilt einfach die Standardgröße.
 */
public final class WindowSizes {

    private static final int MIN = 200;

    private WindowSizes() {
    }

    static Optional<Dimension> recall(String key) {
        int width = recallInt(key + ".width", -1);
        int height = recallInt(key + ".height", -1);
        return width >= MIN && height >= MIN ? Optional.of(new Dimension(width, height)) : Optional.empty();
    }

    static void remember(String key, Dimension size) {
        if (size.width < MIN || size.height < MIN) {
            return;
        }
        AppState.shared().putAll(Map.of(key + ".width", String.valueOf(size.width),
                key + ".height", String.valueOf(size.height)));
    }

    /** Ein gemerkter Zahlenwert des Bedienzustands, etwa die Breite einer Seitenleiste. */
    public static int recallInt(String key, int fallback) {
        return AppState.shared().getInt(key, fallback);
    }

    public static void rememberInt(String key, int value) {
        AppState.shared().put(key, value);
    }
}
