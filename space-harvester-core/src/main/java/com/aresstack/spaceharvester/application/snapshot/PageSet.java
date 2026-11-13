package com.aresstack.spaceharvester.application.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Eine feste Menge von Seiten, je Quelle die Seiten-IDs (ADR 0029). Damit begrenzen Suche, Begriffe und Graph ihre
 * Ergebnisse auf eine aufgelöste Auswahl, ohne vom Bestand abzuhängen. Prüfung je Seite in O(1); der
 * {@link #fingerprint() Fingerabdruck} hängt nur am Inhalt, nicht an der Reihenfolge, und gehört in jeden Cursor, der
 * mit der Menge blättert.
 */
public final class PageSet {

    /** Die leere Menge; eine Abfrage mit ihr findet nichts. */
    public static final PageSet EMPTY = new PageSet(Map.of());

    private final Map<String, Set<String>> pages;
    private final int size;
    private volatile String fingerprint;

    private PageSet(Map<String, Set<String>> pages) {
        this.pages = pages;
        this.size = pages.values().stream().mapToInt(Set::size).sum();
    }

    /** Je Quelle die Seiten-IDs; Quellen ohne Seiten fallen weg. */
    public static PageSet of(Map<String, ? extends Collection<String>> pages) {
        Map<String, Set<String>> copy = new TreeMap<>();
        pages.forEach((sourceId, pageIds) -> {
            Objects.requireNonNull(sourceId, "sourceId");
            if (!pageIds.isEmpty()) {
                copy.put(sourceId, Set.copyOf(pageIds));
            }
        });
        return copy.isEmpty() ? EMPTY : new PageSet(Collections.unmodifiableMap(copy));
    }

    /** Seiten nach ihren Schlüsseln. */
    public static PageSet ofKeys(Collection<PageKey> keys) {
        Map<String, Set<String>> bySource = new TreeMap<>();
        keys.forEach(key -> bySource.computeIfAbsent(key.sourceId(), ignored -> new TreeSet<>()).add(key.pageId()));
        return of(bySource);
    }

    /** Ob die Seite dazugehört; O(1). */
    public boolean contains(String sourceId, String pageId) {
        Set<String> pageIds = pages.get(sourceId);
        return pageIds != null && pageIds.contains(pageId);
    }

    public boolean contains(PageKey key) {
        return contains(key.sourceId(), key.pageId());
    }

    /** Die Seiten einer Quelle; leer, wenn die Menge dort keine hat. */
    public Set<String> pagesOf(String sourceId) {
        return pages.getOrDefault(sourceId, Set.of());
    }

    /** Die Quellen mit mindestens einer Seite, sortiert. */
    public Set<String> sourceIds() {
        return pages.keySet();
    }

    /** Je Quelle die Seiten-IDs, Quellen sortiert. */
    public Map<String, Set<String>> asMap() {
        return pages;
    }

    /** Anzahl der Seiten über alle Quellen. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Fingerabdruck über die sortierten Seiten: gleich für gleiche Mengen, verschieden bei jeder Änderung (bis auf
     * Kollisionen von 96 Bit). Enthält keine Seiten-ID im Klartext und darf in Cursor und Protokolle.
     */
    public String fingerprint() {
        String known = fingerprint;
        if (known == null) {
            known = digest();
            fingerprint = known;
        }
        return known;
    }

    private String digest() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
        pages.forEach((sourceId, pageIds) -> {
            digest.update(sourceId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 1);
            for (String pageId : new TreeSet<>(pageIds)) {
                digest.update(pageId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update((byte) 2);
        });
        return HexFormat.of().formatHex(digest.digest(), 0, 12);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PageSet set && pages.equals(set.pages);
    }

    @Override
    public int hashCode() {
        return pages.hashCode();
    }

    /** Nur Umfang und Fingerabdruck, keine Seiten-IDs. */
    @Override
    public String toString() {
        return "PageSet[" + size + " Seiten, " + fingerprint() + "]";
    }
}
