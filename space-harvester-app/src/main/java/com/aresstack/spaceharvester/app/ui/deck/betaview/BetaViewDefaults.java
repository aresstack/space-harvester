package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns;
import java.util.HashMap;
import java.util.Map;

/**
 * Der Standard-Suchfilter von BetaView wie in den Einstellungen von MainframeMate ({@code BetaViewSettingsPanel},
 * Abschnitt „Standard-Suchfilter“): Favorite ID, Locale, Extension, Form, Tage zurück. Leere Angaben werden zu den
 * Vorgaben von MainframeMate ({@code BetaViewAppProperties}). Liegt im App-Zustand neben der zuletzt benutzten Suche.
 *
 * @param favoriteId Favorit
 * @param locale     Sprache
 * @param extension  EXTENSION
 * @param form       FORM
 * @param daysBack   Tage zurück (Zahl des Zeitraums)
 */
public record BetaViewDefaults(String favoriteId, String locale, String extension, String form, int daysBack) {

    static final String KEY = "deck.betaview.default.";

    public static final BetaViewDefaults MAINFRAMEMATE = new BetaViewDefaults("", "", "", "", 0);

    public BetaViewDefaults {
        favoriteId = orDefault(favoriteId, FilterPatterns.DEFAULT_FAVORITE);
        locale = orDefault(locale, FilterPatterns.DEFAULT_LOCALE);
        extension = orDefault(extension, FilterPatterns.DEFAULT_EXTENSION);
        form = orDefault(form, FilterPatterns.DEFAULT_FORM);
        daysBack = daysBack > 0 ? daysBack : FilterPatterns.DEFAULT_DAYS_BACK;
    }

    /** Die gespeicherten Vorgaben. */
    public static BetaViewDefaults load() {
        AppState state = AppState.shared();
        return new BetaViewDefaults(state.get(KEY + "favorite"), state.get(KEY + "locale"),
                state.get(KEY + "extension"), state.get(KEY + "form"), state.getInt(KEY + "days-back", 0));
    }

    /**
     * Speichert die Vorgaben. Wie in MainframeMate gelten sie für die nächste Suchmaske: die zuletzt benutzten Werte
     * genau dieser Felder werden dafür vergessen.
     */
    public void save() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY + "favorite", favoriteId);
        values.put(KEY + "locale", locale);
        values.put(KEY + "extension", extension);
        values.put(KEY + "form", form);
        values.put(KEY + "days-back", String.valueOf(daysBack));
        // Zahl, Einheit und Art des Zeitraums gehören zusammen: sonst würden aus 14 Tagen nach einer Suche in Stunden
        // 14 Stunden. Wie MainframeMate beginnt die nächste Suchmaske mit „Letzte 7 Tage“ und der Zahl in Tagen.
        for (String field : new String[] {"favorite", "locale", "extension", "form", "amount", "unit", "period"}) {
            values.put(SearchPane.KEY + field, null);
        }
        AppState.shared().putAll(values);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
