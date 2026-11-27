package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What ONE discovery batch produced: the hits, the provider that produced them, and how to continue — the
 * single-batch {@link InitialSearchResult} plus provenance and continuation.
 * <p>
 * An empty {@link #continuation} means "there is no further batch". That is a normal, complete end of a
 * result set, not a failure: a run that stops because the provider has nothing more is exactly as valid as
 * one that stops at its batch limit.
 */
public final class DiscoveryBatchResult {

    /** Hits in provider order; ranking/selection happens later and elsewhere. */
    public final List<SearchResultCandidate> candidates;
    /** Search-engine transit hosts (never a source) — empty for API providers. */
    public final List<String> providerHosts;
    /** Typed CAPTCHA/consent challenge states — empty for API providers. */
    public final List<SearchChallengeState> challenges;
    /** Human-readable provenance/diagnostics for the run log. */
    public final List<String> diagnostics;
    /** Whether THIS batch produced results, was empty, or failed technically. */
    public final InitialSearchStatus status;
    /** Which provider produced this batch — a run may mix several. */
    public final String provider;
    /** The provider's own token for the next batch; "" when the result set ends here. */
    public final String continuation;

    public DiscoveryBatchResult(List<SearchResultCandidate> candidates, List<String> providerHosts,
                                List<SearchChallengeState> challenges, List<String> diagnostics,
                                InitialSearchStatus status, String provider, String continuation) {
        this.candidates = unmodifiable(candidates);
        this.providerHosts = unmodifiable(providerHosts);
        this.challenges = unmodifiable(challenges);
        this.diagnostics = unmodifiable(diagnostics);
        this.status = status == null ? InitialSearchStatus.NO_RESULTS : status;
        this.provider = provider == null ? "" : provider.trim();
        this.continuation = continuation == null ? "" : continuation.trim();
    }

    /** Adapt a single-batch strategy result: same content, no continuation (it cannot paginate). */
    public static DiscoveryBatchResult fromInitialSearch(InitialSearchResult result, String provider) {
        return new DiscoveryBatchResult(result.candidates, result.providerHosts, result.challenges,
                result.diagnostics, result.status, provider, "");
    }

    public boolean hasContinuation() {
        return !continuation.isEmpty();
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null || values.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
