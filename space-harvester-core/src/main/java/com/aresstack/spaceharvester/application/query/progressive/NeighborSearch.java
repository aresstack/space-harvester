package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Die Suchmethode „Nachbarseiten“ (R2) zur Schlagwortsuche ohne progressive Suche, als eigener Aufruf nach einem
 * Stapel: Die Nachbarn der besten ausgelieferten Treffer ({@link NeighborSource}) sucht dieselbe
 * {@link KeywordSearch} mit derselben Anfrage, begrenzt auf diese Nachbarn innerhalb des Umfangs der Anfrage. Nur
 * was die Anfrage dort trifft, kommt zurück, in der Rangfolge des Volltextindex und mit der zusätzlichen Ursache
 * {@link HitReason.Kind#NEIGHBOR}; Rechte, Space-Filter und Dateitypen gelten wie in jeder Suche. Die Schlagwortsuche
 * selbst und ihr Cursor bleiben unberührt; die Treffer gehören hinter die bisherigen.
 */
public final class NeighborSearch {

    private final KeywordSearch keywords;
    private final NeighborSource neighbors;
    private final NeighborLoader loader;

    public NeighborSearch(KeywordSearch keywords, NeighborSource neighbors) {
        this(keywords, neighbors, NeighborLoader.NONE);
    }

    /** @param loader lädt fehlende Nachbarn im Hintergrund nach ({@link #search(AccessContext, KeywordSearch.Query, List, boolean)}) */
    public NeighborSearch(KeywordSearch keywords, NeighborSource neighbors, NeighborLoader loader) {
        this.keywords = Objects.requireNonNull(keywords, "keywords");
        this.neighbors = Objects.requireNonNull(neighbors, "neighbors");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Ergebnis mit Nachladen.
     *
     * @param hits wie {@link #search(AccessContext, KeywordSearch.Query, List)}
     * @param load das Nachladen fehlender Nachbarn, leer ohne; nach seinem Ende findet dieselbe Suche (ohne erneutes
     *             Laden) die geladenen, wenn sie die Anfrage treffen
     */
    public record Result(List<KeywordSearch.Hit> hits, Optional<NeighborLoader.Load> load) {

        public Result {
            hits = List.copyOf(hits);
            Objects.requireNonNull(load, "load");
        }
    }

    /**
     * @param query die Anfrage des Stapels
     * @param shown die schon angezeigten Treffer, beste zuerst; die ersten {@link NeighborCandidates#MAX_SEEDS}
     *              ohne Ursache {@link HitReason.Kind#NEIGHBOR} sind Ausgangsseiten, keiner von allen kommt erneut
     * @return bestätigte Nachbar-Treffer, die die Anfrage treffen, höchstens {@link NeighborCandidates#MAX_CANDIDATES}
     */
    public List<KeywordSearch.Hit> search(AccessContext caller, KeywordSearch.Query query,
                                          List<KeywordSearch.Hit> shown) {
        return search(caller, query, shown, false).hits();
    }

    /**
     * Wie {@link #search(AccessContext, KeywordSearch.Query, List)}; mit {@code load} beginnt dazu im Hintergrund das
     * Laden der Nachbarn, die lokal fehlen oder nur mit Kopfdaten liegen, innerhalb des Umfangs der Anfrage. Die Suche
     * wartet nicht darauf.
     */
    public Result search(AccessContext caller, KeywordSearch.Query query, List<KeywordSearch.Hit> shown,
                         boolean load) {
        Objects.requireNonNull(query, "query");
        // Ein Schritt: Ausgangsseiten sind nur eigentliche Treffer, keine schon angehängten Nachbarn.
        List<KeywordSearch.Hit> seeds = shown.stream().filter(hit -> hit.reasons().stream()
                .noneMatch(reason -> reason.kind() == HitReason.Kind.NEIGHBOR))
                .limit(NeighborCandidates.MAX_SEEDS).toList();
        if (seeds.isEmpty()) {
            return new Result(List.of(), Optional.empty());
        }
        Set<PageKey> known = new HashSet<>();
        shown.forEach(hit -> known.add(new PageKey(hit.sourceId(), hit.pageId())));
        NeighborSource.Found neighborhood = neighbors.find(caller, seeds);
        Map<PageKey, HitReason.Neighbor> candidates = new LinkedHashMap<>();
        neighborhood.local().forEach((key, via) -> {
            if (!known.contains(key) && keywords.includesSource(query, key.sourceId())
                    && NeighborScope.allows(query, key, neighborhood.places().get(key))) {
                candidates.put(key, via);
            }
        });
        List<KeywordSearch.Hit> found = new ArrayList<>();
        if (!candidates.isEmpty()) {
            KeywordSearch.Results results = keywords.search(caller,
                    query.within(PageSet.ofKeys(candidates.keySet())), null,
                    Math.min(KeywordSearch.MAX_LIMIT, NeighborCandidates.MAX_CANDIDATES));
            for (KeywordSearch.Hit hit : results.hits()) {
                HitReason.Neighbor via = candidates.get(new PageKey(hit.sourceId(), hit.pageId()));
                if (via != null) {
                    found.add(hit.withReasons(HitReason.union(hit.reasons(),
                            List.of(HitReason.neighbor(query.text(), via)))));
                }
            }
        }
        if (!load) {
            return new Result(found, Optional.empty());
        }
        // Zu laden: was fehlt, und lokale Kandidaten ohne Treffer (vielleicht nur Kopfdaten); der Lader lässt aus,
        // was schon mit Inhalt im Cache liegt.
        Set<PageKey> confirmed = new HashSet<>();
        found.forEach(hit -> confirmed.add(new PageKey(hit.sourceId(), hit.pageId())));
        Set<PageKey> pages = new LinkedHashSet<>();
        candidates.keySet().stream().filter(key -> !confirmed.contains(key)).forEach(pages::add);
        neighborhood.missing().keySet().stream().filter(key -> !known.contains(key) && keywords.includesSource(query, key.sourceId())
                && NeighborScope.allows(query, key, neighborhood.places().get(key))).forEach(pages::add);
        Optional<NeighborLoader.Load> loading = Optional.empty();
        if (!pages.isEmpty()) {
            try {
                loading = loader.load(caller, pages);
            } catch (RuntimeException e) {
                // Das Nachladen ergänzt nur; die Treffer bleiben.
                System.getLogger(NeighborSearch.class.getName()).log(System.Logger.Level.INFO,
                        "Nachbarseiten nicht nachgeladen ({0})", e.getClass().getName());
            }
        }
        return new Result(found, loading);
    }
}
