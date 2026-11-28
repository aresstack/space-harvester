package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.WindowSizes;
import java.awt.Window;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.function.Supplier;

/**
 * Die saisonale Gestaltung der App (Angelo 28.11.2026): In der Weihnachtszeit, vom 1. Advent bis einschließlich
 * 6. Januar, trägt das „H“ von „Harvester“ im Titel eine rote Mütze, und in der Titelleiste liegen ein paar Schneeflocken und Schneehäufchen.
 * Der Zeitraum wird für jedes Jahr berechnet; gefragt wird beim Zeichnen, es läuft nichts im Hintergrund. Abschalten
 * lässt sich das unter Einstellungen, Darstellung.
 */
public final class Season {

    /** Schlüssel des Schalters „Saisonale Gestaltung“ in den Benutzer-Einstellungen (1 = an, die Vorgabe). */
    public static final String KEY = "ui.season.enabled";
    /** Der letzte Tag der Weihnachtszeit: Heilige Drei Könige. */
    static final int EPIPHANY_DAY = 6;
    /** Der 1. Advent liegt so viele Wochen vor dem 4. Advent, dem letzten Sonntag vor dem 25. Dezember. */
    static final int WEEKS_BEFORE_FOURTH_ADVENT = 3;
    static final int CHRISTMAS_EVE = 24;

    private static Supplier<LocalDate> today = LocalDate::now;

    private Season() {
    }

    /** Der 1. Advent des Jahres. */
    public static LocalDate firstAdvent(int year) {
        return LocalDate.of(year, Month.DECEMBER, CHRISTMAS_EVE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(WEEKS_BEFORE_FOURTH_ADVENT);
    }

    /** Ob der Tag in der Weihnachtszeit liegt: 1. Advent bis einschließlich 6. Januar. */
    public static boolean christmas(LocalDate day) {
        if (day.getMonth() == Month.JANUARY) {
            return day.getDayOfMonth() <= EPIPHANY_DAY;
        }
        return !day.isBefore(firstAdvent(day.getYear()));
    }

    /** Ob der Schalter „Saisonale Gestaltung“ an ist. */
    public static boolean enabled() {
        return WindowSizes.recallInt(KEY, 1) == 1;
    }

    /** Stellt den Schalter und zeichnet alle Fenster neu. */
    public static void enabled(boolean enabled) {
        WindowSizes.rememberInt(KEY, enabled ? 1 : 0);
        for (Window window : Window.getWindows()) {
            window.repaint();
        }
    }

    /** Ob heute weihnachtlich gezeichnet wird: Schalter an und Weihnachtszeit. */
    public static boolean christmasNow() {
        return enabled() && christmas(today.get());
    }

    /** Ersetzt die Quelle des heutigen Datums (für Tests und Bilder); {@code null} stellt die Uhr wieder her. */
    public static void today(Supplier<LocalDate> source) {
        today = source == null ? LocalDate::now : source;
    }
}
