package com.aresstack.spaceharvester.application.images;

import java.util.Locale;

/** Analysestatus eines Bildes der Seite (ADR 0021). */
public enum ImageStatus {
    /** Beschrieben durch das eingestellte Modell. */
    DESCRIBED,
    /** Wartet: das Modell war beschäftigt; beim nächsten Abruf wird es erneut versucht. */
    PENDING,
    /** Fehlgeschlagen: Bild nicht ladbar oder keine verwertbare Antwort des Modells; {@code reason} nennt die Art. */
    FAILED,
    /** Übersprungen: kein Rasterbild, Diagramm mit textueller Quelle oder zu groß; {@code reason} nennt den Grund. */
    SKIPPED;

    /** Der Wert im Ergebnis, z. B. {@code described}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException für einen unbekannten Wert */
    public static ImageStatus of(String key) {
        return valueOf(key.toUpperCase(Locale.ROOT));
    }
}
