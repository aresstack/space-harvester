package com.aresstack.spaceharvester.application.query;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein bestimmter Stand des Caches für einen Space. Seitenliste und Baum einer Antwort beruhen immer auf genau einer
 * Generation; ein Cursor gilt nur für die Generation, aus der er stammt.
 *
 * @param id            stabile, undurchsichtige Kennung der Generation
 * @param state         Zustand dieses Stands
 * @param syncedAt      Ende des Abgleichs, aus dem der Stand stammt, oder {@code null}, wenn keiner abgeschlossen ist
 * @param lastCheckedAt letzte erfolgreiche Prüfung gegen die Quelle oder {@code null}, wenn unbekannt
 */
public record CacheGeneration(String id, CacheState state, Instant syncedAt, Instant lastCheckedAt) {

    public CacheGeneration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
    }
}
