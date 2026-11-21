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
import java.util.Objects;

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

    public NeighborSearch(KeywordSearch keywords, NeighborSource neighbors) {
        this.keywords = Objects.requireNonNull(keywords, "keywords");
        this.neighbors = Objects.requireNonNull(neighbors, "neighbors");
    }

    /**
     * @param query die Anfrage des Stapels
     * @param shown die schon angezeigten Treffer, beste zuerst; die ersten {@link NeighborCandidates#MAX_SEEDS}
     *              bestätigten sind Ausgangsseiten, keiner von ihnen kommt erneut
     * @return bestätigte Nachbar-Treffer, die die Anfrage treffen, höchstens {@link NeighborCandidates#MAX_CANDIDATES}
     */
    public List<KeywordSearch.Hit> search(AccessContext caller, KeywordSearch.Query query,
                                          List<KeywordSearch.Hit> shown) {
        Objects.requireNonNull(query, "query");
        List<KeywordSearch.Hit> seeds = shown.stream().limit(NeighborCandidates.MAX_SEEDS).toList();
        if (seeds.isEmpty()) {
            return List.of();
        }
        Map<PageKey, HitReason.Neighbor> candidates = new LinkedHashMap<>();
        neighbors.neighbors(caller, seeds).forEach((key, via) -> {
            boolean known = shown.stream().anyMatch(hit -> hit.sourceId().equals(key.sourceId())
                    && hit.pageId().equals(key.pageId()));
            if (!known && (query.pages() == null || query.pages().contains(key))) {
                candidates.put(key, via);
            }
        });
        if (candidates.isEmpty()) {
            return List.of();
        }
        KeywordSearch.Results results = keywords.search(caller, query.within(PageSet.ofKeys(candidates.keySet())),
                null, Math.min(KeywordSearch.MAX_LIMIT, NeighborCandidates.MAX_CANDIDATES));
        List<KeywordSearch.Hit> found = new ArrayList<>();
        for (KeywordSearch.Hit hit : results.hits()) {
            HitReason.Neighbor via = candidates.get(new PageKey(hit.sourceId(), hit.pageId()));
            if (via != null) {
                found.add(hit.withReasons(HitReason.union(hit.reasons(),
                        List.of(HitReason.neighbor(query.text(), via)))));
            }
        }
        return List.copyOf(found);
    }
}
