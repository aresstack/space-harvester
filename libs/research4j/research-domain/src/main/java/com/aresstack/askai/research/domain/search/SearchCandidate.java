package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ONE hit a search produced: an IMMUTABLE discovery record, addressable by its {@link #getCandidateId()} for
 * the rest of the project's life — "open hit #18 again, properly" must not require running the search again.
 * <p>
 * It deliberately carries NO inspection state. A candidate can be looked at more than once: skipped under a
 * scan profile, blocked by a consent dialog on the first attempt, read successfully on a later one. Letting
 * the hit itself walk through DISCOVERED → FAILED → INSPECTED would rewrite the discovery fact every time
 * something happened to the page, and "deepen this hit" (a NEW inspection) would look like a state change of
 * an old one. What happened to a page lives in {@link InspectionAttempt}; a UI may project the latest
 * attempt, but the canonical truth stays separate.
 * <p>
 * Two further things it is NOT:
 * <ul>
 * <li>NOT a navigation target — the acquisition frontier also holds links found while reading, which have no
 *     rank and no snippet.</li>
 * <li>NOT a source. A run may find a hundred candidates of which five enter the research corpus; the
 *     relation is candidate → inspection → source, not equality.</li>
 * </ul>
 */
public final class SearchCandidate {

    private final String candidateId;
    private final String normalizedUrl;
    private final String title;
    private final String snippet;
    private final String domain;
    private final List<SearchOccurrence> occurrences;
    /**
     * askai #41: {@code false} when the URL is a provider redirect whose target is not known yet (Google
     * {@code /goto}). Such a hit is a full candidate, but its URL is no verified target address; only the page the
     * browser lands on can become a source.
     */
    private final boolean targetVerified;

    public SearchCandidate(String candidateId, String normalizedUrl, String title, String snippet,
                           String domain, List<SearchOccurrence> occurrences) {
        this(candidateId, normalizedUrl, title, snippet, domain, occurrences, true);
    }

    public SearchCandidate(String candidateId, String normalizedUrl, String title, String snippet,
                           String domain, List<SearchOccurrence> occurrences, boolean targetVerified) {
        this.targetVerified = targetVerified;
        if (candidateId == null || candidateId.trim().isEmpty()) {
            throw new IllegalArgumentException("candidateId must not be empty");
        }
        if (normalizedUrl == null || normalizedUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("normalizedUrl must not be empty");
        }
        this.candidateId = candidateId.trim();
        this.normalizedUrl = normalizedUrl.trim();
        this.title = title == null ? "" : title.trim();
        this.snippet = snippet == null ? "" : snippet.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.occurrences = occurrences == null || occurrences.isEmpty()
                ? Collections.<SearchOccurrence>emptyList()
                : Collections.unmodifiableList(new ArrayList<SearchOccurrence>(occurrences));
    }

    public String getCandidateId() {
        return candidateId;
    }

    /** The identity-bearing URL: two occurrences that normalize to this are the same hit. */
    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    /** The best representation of the title across its occurrences. */
    public String getTitle() {
        return title;
    }

    /** What the result set promised — later also the expectation a readiness judge can check against. */
    public String getSnippet() {
        return snippet;
    }

    public String getDomain() {
        return domain;
    }

    /** Every place this hit appeared; several mean it was found repeatedly, not that it is duplicated. */
    public List<SearchOccurrence> getOccurrences() {
        return occurrences;
    }

    /** The earliest batch it appeared in — the natural sort key when order matters. */
    public int firstBatchOrdinal() {
        int earliest = Integer.MAX_VALUE;
        for (SearchOccurrence occurrence : occurrences) {
            earliest = Math.min(earliest, occurrence.getBatchOrdinal());
        }
        return earliest == Integer.MAX_VALUE ? 1 : earliest;
    }

    /** Its best (lowest) rank across all occurrences; {@link Integer#MAX_VALUE} when it has none. */
    public int bestRank() {
        int best = Integer.MAX_VALUE;
        for (SearchOccurrence occurrence : occurrences) {
            best = Math.min(best, occurrence.getRank());
        }
        return best;
    }

    /** Whether more than one provider returned this hit — a mild signal of prominence, never a score. */
    public boolean foundBySeveralProviders() {
        String first = null;
        for (SearchOccurrence occurrence : occurrences) {
            if (first == null) {
                first = occurrence.getProvider();
            } else if (!first.equals(occurrence.getProvider())) {
                return true;
            }
        }
        return false;
    }

    /** The same hit with one more occurrence recorded; discovery data itself never changes. */
    public SearchCandidate withOccurrence(SearchOccurrence occurrence) {
        if (occurrence == null) {
            return this;
        }
        List<SearchOccurrence> extended = new ArrayList<SearchOccurrence>(occurrences);
        extended.add(occurrence);
        return new SearchCandidate(candidateId, normalizedUrl, title, snippet, domain, extended, targetVerified);
    }

    /** Whether {@link #getNormalizedUrl()} is a verified target address (not an unresolved redirect). */
    public boolean isTargetVerified() {
        return targetVerified;
    }
}
