package com.aresstack.spaceharvester.app.project;

import java.util.Collection;
import java.util.Locale;

/**
 * Die feste Auswahl an Farben eines Projekts (ADR 0043, Nachtrag F1), wie die Farben der Tabgruppen in Firefox.
 * Gespeichert wird der Schlüssel, nie ein Farbwert; wie eine Farbe aussieht, legt die Oberfläche fest. Auswahl und
 * Reihenfolge nach dem UX-Strang (27.11.2026): kein Rot, denn Rot steht in der App nur für Fehler; die Reihenfolge ist
 * die, in der neue Projekte ihre Farbe bekommen.
 */
public enum ProjectColor {
    BLAU("blau", "Blau"),
    TUERKIS("tuerkis", "Türkis"),
    GRUEN("gruen", "Grün"),
    ORANGE("orange", "Orange"),
    LILA("lila", "Lila"),
    ROSA("rosa", "Rosa"),
    GELB("gelb", "Gelb"),
    GRAU("grau", "Grau");

    private final String key;
    private final String label;

    ProjectColor(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Der Schlüssel in der Datei. */
    public String key() {
        return key;
    }

    /** Der Name in der Oberfläche. */
    public String label() {
        return label;
    }

    /** Die Farbe zum Schlüssel; ein unbekannter oder fehlender Schlüssel wird {@link #GRAU}, nie ein Fehler. */
    public static ProjectColor of(String key) {
        if (key != null) {
            String wanted = key.trim().toLowerCase(Locale.ROOT);
            for (ProjectColor color : values()) {
                if (color.key.equals(wanted)) {
                    return color;
                }
            }
        }
        return GRAU;
    }

    /** Die erste Farbe, die noch kein Projekt trägt; sind alle vergeben, geht es reihum weiter. */
    public static ProjectColor next(Collection<ProjectColor> used) {
        for (ProjectColor color : values()) {
            if (!used.contains(color)) {
                return color;
            }
        }
        return values()[used.size() % values().length];
    }
}
