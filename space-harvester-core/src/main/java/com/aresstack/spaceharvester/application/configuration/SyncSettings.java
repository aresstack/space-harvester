package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.sync.SyncMode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param mode              was ein Lauf aus Zeitplan oder Nachholen beim Start tut; Standard
 *                          {@link SyncMode#CATALOG_ONLY}
 * @param inventoryInterval Abstand der regelmäßigen Bestandsinventur (nur Metadaten) oder {@code null} ohne
 * @param sources           abweichende Regeln je Quellen-ID; ohne Eintrag gilt alles hier global
 * @param backgroundFill    Hintergrundfüllung (ADR 0016): lädt in Leerlaufzeit Seiten des Katalogs ohne oder mit
 *                          veraltetem Inhalt; Standard an, unabhängig von {@code mode}; je Quelle abschaltbar
 * @param backgroundParallelism so viele Seiten lädt der Hintergrund höchstens zugleich, 1 bis
 *                          {@value #MAX_BACKGROUND_PARALLELISM}, Standard {@value #DEFAULT_BACKGROUND_PARALLELISM};
 *                          gilt für alle Quellen zusammen
 */
public record SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                           Duration withheldRetention, boolean catchUpOnStart, SyncMode mode,
                           Duration inventoryInterval, Map<String, SourceSyncOverride> sources,
                           boolean backgroundFill, int backgroundParallelism) {

    public static final String EVENTS = "sync.events";
    public static final String INTERVAL = "sync.interval";
    public static final String SPACES = "sync.spaces";
    public static final String BEFORE_SEARCH = "sync.before-search";
    public static final String WITHHELD_RETENTION = "sync.withheld-retention";
    public static final String CATCH_UP_ON_START = "sync.catch-up-on-start";
    public static final String MODE = "sync.mode";
    public static final String INVENTORY_INTERVAL = "sync.inventory-interval";
    /** Präfix der Regeln je Quelle: {@code sync.sources.<Quelle>.<Feld>}. */
    public static final String SOURCES = "sync.sources";
    public static final String BACKGROUND_FILL = "sync.background-fill";
    public static final String BACKGROUND_PARALLELISM = "sync.background-parallelism";

    /** Standard der Parallelität im Hintergrund. */
    public static final int DEFAULT_BACKGROUND_PARALLELISM = 2;
    /** Höchste Parallelität im Hintergrund. */
    public static final int MAX_BACKGROUND_PARALLELISM = 16;

    /** Kleinster Abstand des Zeitplans, wie {@code Harvester.reconcile}. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    /** Wie SpaceSync.DEFAULT_WITHHELD_RETENTION. */
    public static final Duration DEFAULT_WITHHELD_RETENTION = Duration.ofDays(30);

    /**
     * Keine Änderungsquelle: keine Ereignisse, kein Nachholen beim Start, kein Zeitplan, nicht vor jeder Suche;
     * Hintergrundfüllung an.
     */
    public static final SyncSettings DEFAULT = new SyncSettings(false, null, List.of(), false,
            DEFAULT_WITHHELD_RETENTION, false, SyncMode.CATALOG_ONLY, null, Map.of());

    /** Mit Hintergrundfüllung nach Standard (an, {@value #DEFAULT_BACKGROUND_PARALLELISM} zugleich). */
    public SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                        Duration withheldRetention, boolean catchUpOnStart, SyncMode mode,
                        Duration inventoryInterval, Map<String, SourceSyncOverride> sources) {
        this(events, interval, spaces, beforeSearch, withheldRetention, catchUpOnStart, mode, inventoryInterval,
                sources, true, DEFAULT_BACKGROUND_PARALLELISM);
    }

    public SyncSettings {
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        withheldRetention = withheldRetention == null ? DEFAULT_WITHHELD_RETENTION : withheldRetention;
        mode = mode == null ? SyncMode.CATALOG_ONLY : mode;
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /** Ohne Bestandsinventur und ohne Regeln je Quelle. */
    public SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                        Duration withheldRetention, boolean catchUpOnStart, SyncMode mode) {
        this(events, interval, spaces, beforeSearch, withheldRetention, catchUpOnStart, mode, null, Map.of());
    }

    /** Ohne Nachholen beim Start, Läufe nur katalogisieren. */
    public SyncSettings(boolean events, Duration interval, List<String> spaces, boolean beforeSearch,
                        Duration withheldRetention) {
        this(events, interval, spaces, beforeSearch, withheldRetention, false, SyncMode.CATALOG_ONLY);
    }

    /** Die abweichenden Regeln einer Quelle oder {@link SourceSyncOverride#NONE}. */
    public SourceSyncOverride override(String sourceId) {
        return sources.getOrDefault(sourceId, SourceSyncOverride.NONE);
    }

    /**
     * Die wirksamen Regeln einer Quelle: globale Werte, überschrieben von ihren Abweichungen; ohne Regeln je Quelle im
     * Ergebnis. {@link #spaces} bleibt die globale Liste.
     */
    public SyncSettings forSource(String sourceId) {
        SourceSyncOverride o = override(sourceId);
        return new SyncSettings(o.events() != null ? o.events() : events,
                o.changeSearchInterval() != null ? o.changeSearchInterval() : interval, spaces,
                o.beforeSearch() != null ? o.beforeSearch() : beforeSearch,
                o.withheldRetention() != null ? o.withheldRetention() : withheldRetention,
                o.catchUpOnStart() != null ? o.catchUpOnStart() : catchUpOnStart,
                o.mode() != null ? o.mode() : mode,
                o.inventoryInterval() != null ? o.inventoryInterval() : inventoryInterval, Map.of(),
                o.backgroundFill() != null ? o.backgroundFill() : backgroundFill, backgroundParallelism);
    }

    public SyncSettings withInterval(Duration value) {
        return new SyncSettings(events, value, spaces, beforeSearch, withheldRetention, catchUpOnStart, mode,
                inventoryInterval, sources, backgroundFill, backgroundParallelism);
    }

    /** Mit Hintergrundfüllung an oder aus und dieser Parallelität. */
    public SyncSettings withBackground(boolean fill, int parallelism) {
        return new SyncSettings(events, interval, spaces, beforeSearch, withheldRetention, catchUpOnStart, mode,
                inventoryInterval, sources, fill, parallelism);
    }

    public SyncSettings withSource(String sourceId, SourceSyncOverride override) {
        Map<String, SourceSyncOverride> next = new LinkedHashMap<>(sources);
        if (override == null || override.equals(SourceSyncOverride.NONE)) {
            next.remove(sourceId);
        } else {
            next.put(sourceId, override);
        }
        return new SyncSettings(events, interval, spaces, beforeSearch, withheldRetention, catchUpOnStart, mode,
                inventoryInterval, next, backgroundFill, backgroundParallelism);
    }
}
