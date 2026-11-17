package com.aresstack.spaceharvester.app.settings;

import java.util.Locale;

/**
 * Der Hintergrund des Bildbetrachters „Bilder der Seite“ (Angelo 17.11.2026): in der Datei {@value #KEY}, nur die
 * App. Der aus dem Enterprise AI Client übernommene Betrachter hatte eine dunkle Fläche fest im Code; die App ist hell,
 * deshalb ist ohne Angabe {@link #THEME} der Standard: die Oberfläche der App. Die Farben zu den Werten vergibt die
 * Oberfläche aus ihrer Palette ({@code Comic.viewerBackground}), hier stehen nur Wert und Bezeichnung.
 */
public enum ViewerBackground {

    /** Die Oberfläche der App (hell), wie der Rahmen des Fensters. */
    THEME("Wie die App"),
    WHITE("Weiß"),
    /** Neutrales Grau: zeigt die Ränder weißer Bilder und Transparenz. */
    GREY("Grau"),
    /** Die dunkle Fläche des Enterprise AI Client. */
    DARK("Dunkel"),
    BLACK("Schwarz");

    /** Ohne Angabe in der Datei. */
    public static final ViewerBackground DEFAULT = THEME;
    /** Der Schlüssel in der Datei. */
    public static final String KEY = "space-harvester-app.viewer.background";

    private final String label;

    ViewerBackground(String label) {
        this.label = label;
    }

    /** Die Bezeichnung in der Oberfläche. */
    public String label() {
        return label;
    }

    /** Der Wert in der Datei: {@code theme}, {@code white}, {@code grey}, {@code dark} oder {@code black}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Alle Werte der Datei, für Meldungen. */
    public static String keys() {
        StringBuilder all = new StringBuilder();
        for (ViewerBackground value : values()) {
            all.append(all.length() == 0 ? "" : ", ").append(value.key());
        }
        return all.toString();
    }

    /**
     * Der Hintergrund zu einem Wert aus der Datei, ohne Rücksicht auf Groß- und Kleinschreibung; {@code gray} wie
     * {@link #GREY}.
     *
     * @return {@code null}, wenn der Wert keiner ist
     */
    public static ViewerBackground parse(String text) {
        if (text == null) {
            return null;
        }
        String key = text.strip().toLowerCase(Locale.ROOT);
        if ("gray".equals(key)) {
            return GREY;
        }
        for (ViewerBackground value : values()) {
            if (value.key().equals(key)) {
                return value;
            }
        }
        return null;
    }
}
