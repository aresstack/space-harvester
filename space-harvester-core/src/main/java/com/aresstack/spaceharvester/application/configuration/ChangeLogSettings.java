package com.aresstack.spaceharvester.application.configuration;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aufbewahrung des Änderungsprotokolls (ADR 0030, Abschnitt 2) unter {@code space-harvester.cache.change-log.retention};
 * Server und App lesen denselben Schlüssel über {@link #parse}. Aufgeräumt wird beim Start und nach einem Abgleich,
 * nie nach Zeitgeber. Betrifft nur Protokollzeilen und Marken, nicht die Revisionen im Cache.
 *
 * <p>Dazu unter {@code space-harvester.cache.change-log.show-after-update} die Einstellung „Nach dem Update Änderungen
 * anzeigen“ (Abschnitt 6, Standard aus): Sie öffnet nach einem Update die Updates der Quelle und erstellt nie von selbst
 * eine Zusammenfassung.
 *
 * <p>Und unter {@code space-harvester.cache.change-log.area-pages} das Seitenbudget der Bereichszusammenfassung (ADR
 * 0034 §7, „Bestand einer Auswahl“; Angelo 24.11.2026: Umschalter „Änderungen | Bereich“): höchstens so viele Seiten
 * gehen mit Auszug an das Modell, weitere nur als Zahl.
 *
 * @param retention       wie lange Zeilen bleiben; {@code null} heißt unbegrenzt
 * @param showAfterUpdate ob die App nach einem Update die Updates der Quelle öffnet
 * @param areaPages       Seitenbudget der Bereichszusammenfassung, mindestens 1
 */
public record ChangeLogSettings(Duration retention, boolean showAfterUpdate, int areaPages) {

    public static final String RETENTION = "cache.change-log.retention";
    public static final String SHOW_AFTER_UPDATE = "cache.change-log.show-after-update";
    public static final String AREA_PAGES = "cache.change-log.area-pages";
    /** Vorgabe des Seitenbudgets der Bereichszusammenfassung, wenn die YAML nichts sagt. */
    public static final int DEFAULT_AREA_PAGES = 300;
    /** Obergrenze, die die Einstellungen anbieten; die YAML darf mehr. */
    public static final int MAX_AREA_PAGES_OFFERED = 5_000;
    /** Ein Jahr (Angelo 16.11.2026), als 365 Tage. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(365);
    public static final ChangeLogSettings DEFAULT = new ChangeLogSettings(DEFAULT_RETENTION);
    public static final ChangeLogSettings UNLIMITED = new ChangeLogSettings(null);

    private static final Pattern SIMPLE = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d|w|y)?");

    /** Nur die Aufbewahrung; nach dem Update wird nichts angezeigt. */
    public ChangeLogSettings(Duration retention) {
        this(retention, false);
    }

    /** Mit der Vorgabe des Seitenbudgets. */
    public ChangeLogSettings(Duration retention, boolean showAfterUpdate) {
        this(retention, showAfterUpdate, DEFAULT_AREA_PAGES);
    }

    public ChangeLogSettings {
        if (retention != null && retention.isNegative()) {
            throw new IllegalArgumentException("Die Aufbewahrung darf nicht negativ sein (0 = unbegrenzt)");
        }
        if (areaPages < 1) {
            throw new IllegalArgumentException("Das Seitenbudget der Bereichszusammenfassung muss mindestens 1 sein");
        }
        retention = retention == null || retention.isZero() ? null : retention;
    }

    /** Dieselbe Aufbewahrung mit {@code value} für „Nach dem Update Änderungen anzeigen“. */
    public ChangeLogSettings withShowAfterUpdate(boolean value) {
        return new ChangeLogSettings(retention, value, areaPages);
    }

    /** Dieselben Werte mit dem Seitenbudget {@code value} der Bereichszusammenfassung. */
    public ChangeLogSettings withAreaPages(int value) {
        return new ChangeLogSettings(retention, showAfterUpdate, value);
    }

    /** Ob nichts aufgeräumt wird. */
    public boolean unlimited() {
        return retention == null;
    }

    /** Zeilen vor diesem Zeitpunkt sind abgelaufen; leer, wenn unbegrenzt. */
    public Optional<Instant> cutoff(Instant now) {
        return retention == null ? Optional.empty() : Optional.of(now.minus(retention));
    }

    /**
     * Liest den Wert der Konfiguration: fehlend heißt Vorgabe ({@link #DEFAULT_RETENTION}), leer oder {@code 0}
     * unbegrenzt. Erlaubt ISO-8601 mit Jahren und Monaten ({@code P1Y}, {@code P6M}, {@code P30D}, {@code PT12H}) und
     * die Kurzform der übrigen Zeitdauern ({@code 365d}, {@code 2w}, {@code 1y}). Ein Jahr zählt 365 Tage, ein Monat 30.
     *
     * @throws IllegalArgumentException bei einem unlesbaren oder negativen Wert
     */
    public static ChangeLogSettings parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return UNLIMITED;
        }
        return new ChangeLogSettings(duration(text));
    }

    private static Duration duration(String text) {
        Matcher simple = SIMPLE.matcher(text.toLowerCase(Locale.ROOT));
        if (simple.matches()) {
            long amount = Long.parseLong(simple.group(1));
            String unit = simple.group(2) == null ? "ms" : simple.group(2);
            return switch (unit) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(7 * amount);
                case "y" -> Duration.ofDays(365 * amount);
                default -> Duration.ofMillis(amount);
            };
        }
        String upper = text.toUpperCase(Locale.ROOT);
        int time = upper.indexOf('T');
        try {
            if (time < 0) {
                Period period = Period.parse(upper);
                return Duration.ofDays(365L * period.getYears() + 30L * period.getMonths() + period.getDays());
            }
            if (time == 1) {
                return Duration.parse(upper);
            }
            Period period = Period.parse(upper.substring(0, time));
            return Duration.ofDays(365L * period.getYears() + 30L * period.getMonths() + period.getDays())
                    .plus(Duration.parse("P" + upper.substring(time)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unlesbare Aufbewahrung „" + text + "“, etwa P1Y, 365d oder 0", e);
        }
    }
}
