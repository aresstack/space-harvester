package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.research.domain.search.SearchCandidate;
import com.aresstack.askai.research.domain.search.SearchOccurrence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the hits of one run into DISTINCT candidates without losing where each came from.
 * <p>
 * Deduplication is by normalized URL, and it is additive: a hit that appears on batch 1 and again on batch 3,
 * or that two providers return, becomes ONE candidate with several {@link SearchOccurrence}s. Neither
 * multiplying it into three candidates nor discarding two of the three appearances is acceptable — a
 * diversity-aware selection needs to see that a page was found repeatedly and by whom.
 * <p>
 * Ids are minted in first-appearance order and are stable for the life of the run, which is what makes a hit
 * addressable later ("open #18 again").
 */
public final class CandidatePool {

    private final Map<String, SearchCandidate> byNormalizedUrl =
            new LinkedHashMap<String, SearchCandidate>();

    /**
     * Add one batch. Returns how many candidates were NEW — the number that tells a progressive traversal
     * whether another batch is still paying off.
     */
    public int add(List<SearchResultCandidate> hits, String provider, int batchOrdinal) {
        int added = 0;
        int rank = 0;
        for (SearchResultCandidate hit : hits) {
            rank++;
            // askai #41: an UNRESOLVED hit has no verified target but stays a candidate under its provider URL.
            String target = hit.navigationUrl().trim();
            if (target.isEmpty()) {
                continue;
            }
            String normalized = SearchUrlNormalizer.normalize(target);
            SearchOccurrence occurrence = new SearchOccurrence(provider, batchOrdinal, rank,
                    hit.rawSearchHref == null || hit.rawSearchHref.isEmpty() ? target : hit.rawSearchHref);
            SearchCandidate existing = byNormalizedUrl.get(normalized);
            if (existing != null) {
                // Found AGAIN: keep the candidate, record where it turned up this time.
                byNormalizedUrl.put(normalized, existing.withOccurrence(occurrence));
                continue;
            }
            String candidateId = "c" + (byNormalizedUrl.size() + 1);
            byNormalizedUrl.put(normalized, new SearchCandidate(candidateId, normalized, hit.title,
                    hit.snippet, hit.displayedDomain, java.util.Collections.singletonList(occurrence),
                    hit.hasVerifiedTarget()));
            added++;
        }
        return added;
    }

    /** The distinct candidates, in first-appearance order. */
    public List<SearchCandidate> candidates() {
        return new ArrayList<SearchCandidate>(byNormalizedUrl.values());
    }

    public int size() {
        return byNormalizedUrl.size();
    }

    public boolean isEmpty() {
        return byNormalizedUrl.isEmpty();
    }

    /** How many distinct domains the pool covers — the cheapest usable diversity signal. */
    public int distinctDomains() {
        List<String> domains = new ArrayList<String>();
        for (SearchCandidate candidate : byNormalizedUrl.values()) {
            String domain = candidate.getDomain();
            if (!domain.isEmpty() && !domains.contains(domain)) {
                domains.add(domain);
            }
        }
        return domains.size();
    }
}
