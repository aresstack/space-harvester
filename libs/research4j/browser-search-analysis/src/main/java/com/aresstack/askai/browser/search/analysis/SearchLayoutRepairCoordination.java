package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;

import java.util.Collections;
import java.util.List;

/**
 * The runtime's decision for one repair request: either SUBMIT a validated layout back to the sidecar
 * (from a profile hit or a successful AI resolution) or GIVE_UP so the runtime moves to the next
 * repair request / engine. Carries the typed AI attempt history and whether a profile served it —
 * the diagnostics the research session surfaces.
 */
public final class SearchLayoutRepairCoordination {

    public enum Outcome {
        SUBMIT,
        GIVE_UP
    }

    public final Outcome outcome;
    public final SearchLayoutRepairSubmission submission;
    public final boolean profileHit;
    /** The AI attempt result, or null when a profile served the request without any model call. */
    public final SearchPageLayoutResolverResult resolverResult;
    public final List<String> diagnostics;

    private SearchLayoutRepairCoordination(Outcome outcome, SearchLayoutRepairSubmission submission,
                                           boolean profileHit,
                                           SearchPageLayoutResolverResult resolverResult,
                                           List<String> diagnostics) {
        this.outcome = outcome;
        this.submission = submission;
        this.profileHit = profileHit;
        this.resolverResult = resolverResult;
        this.diagnostics = diagnostics == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(diagnostics);
    }

    static SearchLayoutRepairCoordination submit(SearchLayoutRepairSubmission submission,
                                                 boolean profileHit,
                                                 SearchPageLayoutResolverResult resolverResult,
                                                 List<String> diagnostics) {
        return new SearchLayoutRepairCoordination(Outcome.SUBMIT, submission, profileHit,
                resolverResult, diagnostics);
    }

    static SearchLayoutRepairCoordination giveUp(SearchPageLayoutResolverResult resolverResult,
                                                 List<String> diagnostics) {
        return new SearchLayoutRepairCoordination(Outcome.GIVE_UP, null, false, resolverResult,
                diagnostics);
    }

    public boolean shouldSubmit() {
        return outcome == Outcome.SUBMIT && submission != null;
    }
}
