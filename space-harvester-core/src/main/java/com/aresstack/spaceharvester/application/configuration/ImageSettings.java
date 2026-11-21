package com.aresstack.spaceharvester.application.configuration;

/**
 * Automatische Bildbeschreibung (Fahrplan B1, ADR 0021, ADR 0028) unter {@code space-harvester.images}: Server und App
 * lesen dieselben Schlüssel. Mit {@code automatic} beschreibt ein eigener Auftrag nach jedem Abgleich einer Quelle und
 * nach neuer KI-Freigabe die Bilder ihrer freigegebenen Seiten, die für das eingestellte Vision-Modell noch keine
 * Beschreibung haben. Standard aus, weil ein großer Space beim ersten Lauf tausende Bilder an das Modell schickt; der
 * Knopf im Reiter „Bilder“ bleibt davon unberührt.
 *
 * @param automatic ob nach jedem Abgleich automatisch beschrieben wird (Standard {@code false})
 * @param maxPerRun so viele Bilder höchstens je Lauf und Quelle an das Modell (Standard {@value #DEFAULT_MAX_PER_RUN});
 *                  {@code 0} heißt unbegrenzt
 */
public record ImageSettings(boolean automatic, int maxPerRun) {

    public static final String AUTOMATIC = "images.automatic";
    public static final String MAX_PER_RUN = "images.max-per-run";
    public static final int DEFAULT_MAX_PER_RUN = 500;
    public static final ImageSettings DEFAULT = new ImageSettings(false, DEFAULT_MAX_PER_RUN);

    public ImageSettings {
        if (maxPerRun < 0) {
            throw new IllegalArgumentException("Die Obergrenze darf nicht negativ sein (0 = unbegrenzt)");
        }
    }

    /** Ob die Zahl der Bilder je Lauf unbegrenzt ist. */
    public boolean unlimited() {
        return maxPerRun == 0;
    }

    public ImageSettings withAutomatic(boolean value) {
        return new ImageSettings(value, maxPerRun);
    }

    public ImageSettings withMaxPerRun(int value) {
        return new ImageSettings(automatic, value);
    }
}
