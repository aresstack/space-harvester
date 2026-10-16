package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.SpaceFingerprint;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Eine bestätigte Generation eines Space: der Stand, den der Cache nach einem vollständigen Abgleich führt.
 *
 * @param sourceId      Quellen-ID
 * @param spaceKey      Space-Key
 * @param generation    fortlaufende Nummer je Space, beginnend bei 1
 * @param confirmedAt   Zeitpunkt der Bestätigung
 * @param pages         Stand je {@code pageId}
 * @param stalePageIds  Seiten, deren Stand aus einer älteren Generation übernommen wurde, weil ihr Abgleich
 *                      fehlschlug oder ihr Fehlen nicht bestätigt werden konnte; sie gelten als veraltet und werden
 *                      im nächsten Lauf erneut abgeglichen
 */
public record SyncBaseline(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                           SortedMap<String, BaselineEntry> pages, SortedSet<String> stalePageIds) {

    public SyncBaseline {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        if (generation < 1) {
            throw new IllegalArgumentException("generation beginnt bei 1");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        pages = natural(pages);
        pages.forEach((pageId, entry) -> {
            if (!pageId.equals(entry.pageId())) {
                throw new IllegalArgumentException("Schlüssel " + pageId + " passt nicht zur Seite " + entry.pageId());
            }
        });
        stalePageIds = natural(stalePageIds);
        if (!pages.keySet().containsAll(stalePageIds)) {
            throw new IllegalArgumentException("Veraltete Seiten müssen zur Generation gehören");
        }
    }

    @Override
    public SortedMap<String, BaselineEntry> pages() {
        return natural(pages);
    }

    @Override
    public SortedSet<String> stalePageIds() {
        return natural(stalePageIds);
    }

    /**
     * {@code spaceFingerprint} ({@value SpaceFingerprint#SCHEMA}) über den geführten Stand. Gleich dem der nächsten
     * bestätigten Inventur heißt: Cache und Quelle stimmen in allen Fingerprints überein.
     */
    public String spaceFingerprint() {
        return SpaceFingerprint.of(sourceId, spaceKey,
                pages.values().stream().map(BaselineEntry::fingerprint).toList());
    }

    /** Kopien mit natürlicher Ordnung, unabhängig vom Comparator der übergebenen Sammlung. */
    private static SortedMap<String, BaselineEntry> natural(Map<String, BaselineEntry> pages) {
        SortedMap<String, BaselineEntry> copy = new TreeMap<>();
        copy.putAll(pages);
        return copy;
    }

    private static SortedSet<String> natural(Collection<String> pageIds) {
        SortedSet<String> copy = new TreeSet<>();
        copy.addAll(pageIds);
        return copy;
    }
}
