package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.List;

/**
 * Ergebnis einer Asset-Ernte für eine Seite (#19).
 *
 * @param listComplete {@code false}, wenn die Anhangsliste der Quelle Einträge auslassen musste
 */
public record AssetHarvest(String sourceId, String pageId, String spaceKey, boolean listComplete, List<Entry> entries,
                           Instant finishedAt) {

    public AssetHarvest {
        entries = List.copyOf(entries);
    }

    /** Ob alle Anhänge der vollständigen Liste jetzt abgelegt sind. */
    public boolean complete() {
        return listComplete && entries.stream().noneMatch(e -> e.status() == Status.FAILED);
    }

    public long count(Status status) {
        return entries.stream().filter(e -> e.status() == status).count();
    }

    public enum Status {
        /** Heruntergeladen und abgelegt. */
        STORED,
        /** In dieser Version schon abgelegt, nicht erneut übertragen. */
        REUSED,
        /** Nicht abgelegt; {@code code} nennt den Grund. */
        FAILED
    }

    /**
     * @param asset der abgelegte Anhang, bei {@link Status#FAILED} {@code null}
     * @param code  bei {@link Status#FAILED} ein fester Fehlercode wie {@code source-rate-limited} oder
     *              {@code asset-too-large}, sonst {@code null}
     */
    public record Entry(String attachmentId, Status status, Asset asset, String code) {
    }
}
