package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.SpaceFingerprint;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * @param missingPageIds Seiten, die die Inventur gelistet hat, für die der Cache aber keinen Stand führt, weil ihr
 *                       erster Abruf fehlschlug; nicht in {@code pages}. Der nächste Lauf sieht sie wieder als neu.
 *                       Eine Generation mit solchen Seiten ist bestätigt, aber nicht vollständig ({@link #complete()})
 * @param withheldPageIds gesperrte Seiten ({@link RemovalCheck.Verdict#INACCESSIBLE}, ADR 0014) mit dem Beginn der
 *                        Sperre; nicht in {@code pages}. Nach Ablauf der Frist entfernt ein Lauf ihre Rohdaten, taucht
 *                        eine wieder in der Inventur auf, ist sie neu
 */
public record SyncBaseline(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                           SortedMap<String, BaselineEntry> pages, SortedSet<String> stalePageIds,
                           SortedSet<String> missingPageIds, SortedMap<String, Instant> withheldPageIds) {

    /** Eine Generation ohne fehlende und gesperrte Seiten. */
    public SyncBaseline(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                        SortedMap<String, BaselineEntry> pages, SortedSet<String> stalePageIds) {
        this(sourceId, spaceKey, generation, confirmedAt, pages, stalePageIds, new TreeSet<>());
    }

    /** Eine Generation ohne gesperrte Seiten. */
    public SyncBaseline(String sourceId, String spaceKey, long generation, Instant confirmedAt,
                        SortedMap<String, BaselineEntry> pages, SortedSet<String> stalePageIds,
                        SortedSet<String> missingPageIds) {
        this(sourceId, spaceKey, generation, confirmedAt, pages, stalePageIds, missingPageIds, new TreeMap<>());
    }

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
        missingPageIds = natural(missingPageIds == null ? Set.of() : missingPageIds);
        for (String missing : missingPageIds) {
            if (pages.containsKey(missing)) {
                throw new IllegalArgumentException("Fehlende Seite " + missing + " darf keinen Stand haben");
            }
        }
        withheldPageIds = new TreeMap<>(withheldPageIds == null ? Map.of() : withheldPageIds);
        for (Map.Entry<String, Instant> withheld : withheldPageIds.entrySet()) {
            Objects.requireNonNull(withheld.getValue(), "Beginn der Sperre");
            if (pages.containsKey(withheld.getKey()) || missingPageIds.contains(withheld.getKey())) {
                throw new IllegalArgumentException("Gesperrte Seite " + withheld.getKey()
                        + " darf weder Stand haben noch fehlen");
            }
        }
    }

    @Override
    public SortedMap<String, Instant> withheldPageIds() {
        return new TreeMap<>(withheldPageIds);
    }

    @Override
    public SortedSet<String> missingPageIds() {
        return natural(missingPageIds);
    }

    /**
     * Ob der Cache für jede gelistete Seite einen Stand führt. Leser zeigen eine Generation mit fehlenden Seiten als
     * unvollständig an, nie als vollständigen Abgleich; veraltete Seiten ({@link #stalePageIds()}) zählen nicht dazu,
     * sie haben einen älteren Stand.
     */
    public boolean complete() {
        return missingPageIds.isEmpty();
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
