package com.aresstack.spaceharvester.application.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Bestand eines Space im Cache, ohne Quellenzugriff (ADR 0014): wie viele Seiten der Katalog kennt und wie viele davon
 * mit Inhalt vorliegen. Seitenkatalog und Inhalte haben getrennte Lebenszyklen; viele bekannte Seiten ohne Inhalt sind
 * kein Fehler, Inhalte beschafft die Suche.
 *
 * @param sourceId      Quellen-ID
 * @param spaceKey      Space-Key
 * @param state         ob der Katalog den Space erfasst hat
 * @param knownPages    Seiten mit Kopfdaten im Katalog
 * @param cachedPages   davon Seiten mit Inhalt im Cache
 * @param stalePages    Seiten, deren Stand laut letzter Generation veraltet ist
 * @param withheldPages gesperrte Seiten: für das technische Konto nicht sichtbar, nicht in der Suche
 * @param generation    bestätigte Generation oder {@code null}
 * @param confirmedAt   wann sie bestätigt wurde, oder {@code null}
 * @param changesSince  ab wann die nächste Änderungssuche fragt, oder {@code null}
 */
public record SpaceCatalog(String sourceId, String spaceKey, State state, int knownPages, int cachedPages,
                           int stalePages, int withheldPages, Long generation, Instant confirmedAt,
                           Instant changesSince) {

    public enum State {
        /** Der Cache kennt keine Seite des Space. */
        UNKNOWN,
        /** Einzelne Seiten sind bekannt, etwa durch Suche oder Abruf, aber keine Inventur hat den Space erfasst. */
        PARTIAL,
        /** Eine bestätigte Generation führt den Space, meist aus einer Inventur. */
        CATALOGED
    }

    public SpaceCatalog {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(state, "state");
    }

    static SpaceCatalog of(String sourceId, String spaceKey, SyncBaseline baseline, int knownPages,
                           int cachedPages) {
        State state = baseline != null ? State.CATALOGED : knownPages > 0 ? State.PARTIAL : State.UNKNOWN;
        return new SpaceCatalog(sourceId, spaceKey, state, knownPages, cachedPages,
                baseline == null ? 0 : baseline.stalePageIds().size(),
                baseline == null ? 0 : baseline.withheldPageIds().size(),
                baseline == null ? null : baseline.generation(), baseline == null ? null : baseline.confirmedAt(),
                baseline == null ? null : baseline.changesSince());
    }
}
