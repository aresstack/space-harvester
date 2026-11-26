package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.sync.SyncMode;
import java.util.Arrays;
import java.util.Optional;

/**
 * Voreinstellungen der Beschaffung einer Quelle (ADR 0037, Punkt 6, F37-2): Abkürzungen über die vorhandenen Regeln
 * ({@link SourceSyncOverride}), kein zweites Synchronisierungsmodell. Eine Voreinstellung setzt Betriebsart,
 * Hintergrundfüllung und „Suchtreffer nachladen“ ausdrücklich; die einzelnen Regeln bleiben danach einzeln änderbar.
 * Gilt keine der beiden, sind die Regeln „Benutzerdefiniert“ ({@link #of} leer).
 *
 * <ul>
 *   <li><b>Vollständig:</b> alle Inhalte des eingestellten Umfangs ({@link SyncMode#FULL}), Hintergrundfüllung und
 *       Suchtreffer nachladen an.</li>
 *   <li><b>Selektiv:</b> nur gecachte Inhalte aktuell halten ({@link SyncMode#SYNC_CACHE}), keine Hintergrundfüllung
 *       und kein Nachladen von Suchtreffern; in den Bestand kommt nur, was ausdrücklich indexiert, markiert oder geöffnet
 *       wird (IndexMarks).</li>
 * </ul>
 */
public enum SyncPreset {

    FULL("Vollständig", SyncMode.FULL, true, true),
    SELECTIVE("Selektiv", SyncMode.SYNC_CACHE, false, false);

    private final String label;
    private final SyncMode mode;
    private final boolean backgroundFill;
    private final boolean prefetchHits;

    SyncPreset(String label, SyncMode mode, boolean backgroundFill, boolean prefetchHits) {
        this.label = label;
        this.mode = mode;
        this.backgroundFill = backgroundFill;
        this.prefetchHits = prefetchHits;
    }

    /** Anzeigename, etwa „Selektiv“. */
    public String label() {
        return label;
    }

    /** Die Regel mit den Werten dieser Voreinstellung; alle übrigen Felder bleiben, wie sie sind. */
    public SourceSyncOverride applyTo(SourceSyncOverride rule) {
        return new SourceSyncOverride(rule.changeSearchInterval(), rule.inventoryInterval(), rule.events(),
                rule.catchUpOnStart(), rule.beforeSearch(), mode, rule.refreshStrategy(), rule.maxAge(),
                rule.withheldRetention(), backgroundFill, prefetchHits);
    }

    /** Ob die wirksamen Werte dieser Voreinstellung entsprechen. */
    public boolean matches(SyncMode effectiveMode, boolean effectiveBackgroundFill, boolean effectivePrefetchHits) {
        return mode == effectiveMode && backgroundFill == effectiveBackgroundFill
                && prefetchHits == effectivePrefetchHits;
    }

    /**
     * Die Voreinstellung, der die wirksamen Werte entsprechen; leer heißt „Benutzerdefiniert“.
     *
     * @param effectiveMode           wirksame Betriebsart der Quelle (eigene Regel oder global)
     * @param effectiveBackgroundFill wirksame Hintergrundfüllung der Quelle
     * @param effectivePrefetchHits   wirksames „Suchtreffer nachladen“ der Quelle
     */
    public static Optional<SyncPreset> of(SyncMode effectiveMode, boolean effectiveBackgroundFill,
                                          boolean effectivePrefetchHits) {
        return Arrays.stream(values())
                .filter(preset -> preset.matches(effectiveMode, effectiveBackgroundFill, effectivePrefetchHits))
                .findFirst();
    }
}
