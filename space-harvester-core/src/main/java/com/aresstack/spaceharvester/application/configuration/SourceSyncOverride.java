package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.time.Duration;

/**
 * Abweichende Synchronisierungsregeln einer Quelle, unter {@code space-harvester.sync.sources.<Quelle>.*} (auch
 * {@code sync.sources[<Quelle>].*}); der Reiter „Synchronisierung“ der Wissensquellen bearbeitet sie. Jedes Feld ist
 * optional: {@code null} übernimmt den globalen Standard aus {@link SyncSettings} bzw. {@link SearchSettings}. Modelle
 * gelten immer global und haben hier keinen Platz.
 *
 * <p>Gruppiert wie in der Oberfläche: <b>Erkennung</b> ({@link #changeSearchInterval}, {@link #inventoryInterval},
 * {@link #events}, {@link #catchUpOnStart}, {@link #beforeSearch}, {@link #mode}), <b>Aktualisierung</b>
 * ({@link #refreshStrategy}, {@link #maxAge}), <b>Bereinigung</b> ({@link #withheldRetention}), <b>Beschaffung</b>
 * ({@link #backgroundFill}).
 *
 * @param changeSearchInterval automatische Änderungssuche (CQL {@code lastmodified}) in diesem Abstand
 * @param inventoryInterval    regelmäßige Bestandsinventur (nur Metadaten) in diesem Abstand
 * @param events               Webhook-Benachrichtigungen annehmen
 * @param catchUpOnStart       beim Start nachholen
 * @param beforeSearch         vor jeder Suche nachholen
 * @param mode                 Betriebsart der Läufe
 * @param refreshStrategy      Aktualität vorhandener Inhalte
 * @param maxAge               Zeitfenster der zeitbasierten Strategien
 * @param withheldRetention    wie lange gesperrte Rohdaten bleiben
 * @param backgroundFill       Hintergrundfüllung für diese Quelle (ADR 0016); {@code false} schaltet sie nur hier ab
 */
public record SourceSyncOverride(Duration changeSearchInterval, Duration inventoryInterval, Boolean events,
                                 Boolean catchUpOnStart, Boolean beforeSearch, SyncMode mode,
                                 RefreshStrategy refreshStrategy, Duration maxAge, Duration withheldRetention,
                                 Boolean backgroundFill) {

    /** Unterschlüssel unter {@code sync.sources.<Quelle>.}. */
    public static final String CHANGE_SEARCH_INTERVAL = "interval";
    public static final String INVENTORY_INTERVAL = "inventory-interval";
    public static final String EVENTS = "events";
    public static final String CATCH_UP_ON_START = "catch-up-on-start";
    public static final String BEFORE_SEARCH = "before-search";
    public static final String MODE = "mode";
    public static final String REFRESH_STRATEGY = "refresh-strategy";
    public static final String MAX_AGE = "max-age";
    public static final String WITHHELD_RETENTION = "withheld-retention";
    public static final String BACKGROUND_FILL = "background-fill";

    /** Übernimmt alles global. */
    public static final SourceSyncOverride NONE = new SourceSyncOverride(null, null, null, null, null, null, null, null,
            null, null);

    /** Ohne Abweichung bei der Hintergrundfüllung. */
    public SourceSyncOverride(Duration changeSearchInterval, Duration inventoryInterval, Boolean events,
                              Boolean catchUpOnStart, Boolean beforeSearch, SyncMode mode,
                              RefreshStrategy refreshStrategy, Duration maxAge, Duration withheldRetention) {
        this(changeSearchInterval, inventoryInterval, events, catchUpOnStart, beforeSearch, mode, refreshStrategy,
                maxAge, withheldRetention, null);
    }

    /** Schlüssel eines Felds dieser Quelle, z. B. {@code sync.sources.wiki.interval}. */
    public static String key(String sourceId, String field) {
        return "sync.sources." + sourceId + "." + field;
    }
}
