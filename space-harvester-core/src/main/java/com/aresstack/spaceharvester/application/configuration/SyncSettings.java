package com.aresstack.spaceharvester.application.configuration;

import java.time.Duration;
import java.util.List;

/**
 * Änderungsquellen der Synchronisierung (ADR 0014), unter {@code space-harvester.sync.*}: alle optional und
 * standardmäßig aus. Ohne sie bleibt der Cache über die {@link RefreshStrategy} je Seite und die Beschaffung der
 * Suchtreffer aktuell; Webhooks sind eine zusätzliche Quelle, nie Voraussetzung. Ein Zeitplan wird beim Aktivieren neu
 * gesetzt; ein laufender Abgleich endet mit seinen bisherigen Einstellungen.
 *
 * @param events            Änderungsereignisse der Quelle (Confluence-Webhooks) annehmen; Standard aus, weil ein
 *                          Administrator den Webhook registrieren muss
 * @param interval          Abstand des geplanten Nachholabgleichs (CQL {@code lastmodified}) oder {@code null} ohne
 *                          Zeitplan; z. B. {@code P1D} für einmal am Tag
 * @param spaces            Spaces des Zeitplans, je Eintrag {@code <sourceId>/<spaceKey>}
 * @param beforeSearch      vor jeder Suchanfrage nachholen; Standard aus
 * @param withheldRetention wie lange gesperrte Rohdaten bleiben (ADR 0014)
 * @param catchUpOnStart    beim Start verpasste Änderungen nachholen; Standard aus
 * @param loadUnknown       der Abgleich lädt auch Seiten mit Inhalt, die der Cache noch nicht kennt; Standard aus,
 *                          ein Zeitplan aktualisiert sonst nur bekannte Seiten
 */
public record SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                           Duration withheldRetention, boolean catchUpOnStart, boolean loadUnknown) {

    public static final String EVENTS = "sync.events";
    public static final String INTERVAL = "sync.interval";
    public static final String SPACES = "sync.spaces";
    public static final String BEFORE_SEARCH = "sync.before-search";
    public static final String WITHHELD_RETENTION = "sync.withheld-retention";
    public static final String CATCH_UP_ON_START = "sync.catch-up-on-start";
    public static final String LOAD_UNKNOWN = "sync.load-unknown";

    /** Kleinster Abstand des Zeitplans, wie {@code Harvester.reconcile}. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    /** Wie SpaceSync.DEFAULT_WITHHELD_RETENTION. */
    public static final Duration DEFAULT_WITHHELD_RETENTION = Duration.ofDays(30);

    /** Keine Änderungsquelle: keine Ereignisse, kein Nachholen beim Start, kein Zeitplan, nicht vor jeder Suche. */
    public static final SyncSettings DEFAULT = new SyncSettings(false, null, List.of(), false,
            DEFAULT_WITHHELD_RETENTION, false, false);

    public SyncSettings {
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        withheldRetention = withheldRetention == null ? DEFAULT_WITHHELD_RETENTION : withheldRetention;
    }

    /** Ohne Nachholen beim Start und ohne Laden unbekannter Seiten. */
    public SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                        Duration withheldRetention) {
        this(events, interval, spaces, beforeSearch, withheldRetention, false, false);
    }

    public SyncSettings withInterval(Duration value) {
        return new SyncSettings(events, value, spaces, beforeSearch, withheldRetention, catchUpOnStart, loadUnknown);
    }
}
