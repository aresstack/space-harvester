package com.aresstack.spaceharvester.app.group;

import java.util.Collection;
import java.util.Locale;

/**
 * Die feste Auswahl an Farben farbiger Gruppen, wie die Farben der Tabgruppen in Firefox: Chat-Gruppen (ADR 0043,
 * Nachtrag F2) und farbige Favoritenordner nutzen dieselbe Auswahl. Farbe bedeutet überall die Gruppe; ein Projekt hat
 * keine Farbe. Die Farbwerte stehen in {@code ui.controls.GroupColors}.
 * Gespeichert wird der Schlüssel, nie ein Farbwert; wie eine Farbe aussieht, legt die Oberfläche fest. Auswahl und
 * Reihenfolge nach dem UX-Strang (27.11.2026): kein Rot, denn Rot steht in der App nur für Fehler; die Reihenfolge ist
 * die, in der neue Gruppen ihre Farbe bekommen.
 */
public enum GroupColor {
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

    GroupColor(String key, String label) {
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
    public static GroupColor of(String key) {
        if (key != null) {
            String wanted = key.trim().toLowerCase(Locale.ROOT);
            for (GroupColor color : values()) {
                if (color.key.equals(wanted)) {
                    return color;
                }
            }
        }
        return GRAU;
    }

    /** Die erste Farbe, die noch keine Gruppe trägt; sind alle vergeben, geht es reihum weiter. */
    public static GroupColor next(Collection<GroupColor> used) {
        for (GroupColor color : values()) {
            if (!used.contains(color)) {
                return color;
            }
        }
        return values()[used.size() % values().length];
    }
}
