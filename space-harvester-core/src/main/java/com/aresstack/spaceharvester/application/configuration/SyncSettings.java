package com.aresstack.spaceharvester.application.configuration;

import java.time.Duration;
import java.util.List;

/**
 * Synchronisierung (ADR 0014), unter {@code space-harvester.sync.*}. Ein Zeitplan wird beim Aktivieren neu gesetzt; ein
 * laufender Abgleich endet mit seinen bisherigen Einstellungen.
 *
 * @param events            Änderungsereignisse der Quelle (Confluence-Webhooks) annehmen; Standard aus, weil ein
 *                          Administrator den Webhook registrieren muss
 * @param interval          Abstand des geplanten Nachholabgleichs (CQL {@code lastmodified}) oder {@code null} ohne
 *                          Zeitplan; z. B. {@code P1D} für einmal am Tag
 * @param spaces            Spaces des Zeitplans, je Eintrag {@code <sourceId>/<spaceKey>}
 * @param beforeSearch      vor jeder Suchanfrage nachholen; Standard aus
 * @param withheldRetention wie lange gesperrte Rohdaten bleiben (ADR 0014)
 */
public record SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                           Duration withheldRetention) {

    public static final String EVENTS = "sync.events";
    public static final String INTERVAL = "sync.interval";
    public static final String SPACES = "sync.spaces";
    public static final String BEFORE_SEARCH = "sync.before-search";
    public static final String WITHHELD_RETENTION = "sync.withheld-retention";

    /** Wie SpaceSync.DEFAULT_WITHHELD_RETENTION. */
    public static final Duration DEFAULT_WITHHELD_RETENTION = Duration.ofDays(30);

    /** Bedarfsgesteuert: keine Ereignisse, kein Zeitplan, nicht vor jeder Suche. */
    public static final SyncSettings DEFAULT = new SyncSettings(false, null, List.of(), false,
            DEFAULT_WITHHELD_RETENTION);

    public SyncSettings {
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        withheldRetention = withheldRetention == null ? DEFAULT_WITHHELD_RETENTION : withheldRetention;
    }

    public SyncSettings withInterval(Duration value) {
        return new SyncSettings(events, value, spaces, beforeSearch, withheldRetention);
    }
}
