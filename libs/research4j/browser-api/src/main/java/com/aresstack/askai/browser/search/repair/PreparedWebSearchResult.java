package com.aresstack.askai.browser.search.repair;

import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.search.SearchResultCandidate;

import java.util.Collections;
import java.util.List;

/**
 * The typed result of {@code web_search_prepare}: either directly-extracted organic candidates, or —
 * on {@link WebSearchPreparationStatus#REPAIR_REQUIRED} — an ORDERED list of bounded repair requests,
 * one per low-confidence engine page, that the runtime works through until one yields a valid
 * extraction. It also carries the SAME search metadata the legacy {@code web_search} surface exposed —
 * provider hosts (transit), typed per-engine attempts and manual-challenge states — so switching the
 * research loop to this path loses no A3 behaviour. A low-confidence page is never lost just because a
 * later engine was also probed.
 */
public final class PreparedWebSearchResult {

    public final WebSearchPreparationStatus status;
    public final List<SearchResultCandidate> candidates;
    public final List<SearchLayoutRepairRequest> repairRequests;
    public final List<String> providerHosts;
    public final List<LegacySearchEngineAttemptResult> engineAttempts;
    public final List<SearchChallengeState> challenges;
    public final List<String> diagnostics;

    public PreparedWebSearchResult(WebSearchPreparationStatus status,
                                   List<SearchResultCandidate> candidates,
                                   List<SearchLayoutRepairRequest> repairRequests,
                                   List<String> providerHosts,
                                   List<LegacySearchEngineAttemptResult> engineAttempts,
                                   List<SearchChallengeState> challenges, List<String> diagnostics) {
        this.status = status == null ? WebSearchPreparationStatus.FAILED : status;
        this.candidates = candidates == null
                ? Collections.<SearchResultCandidate>emptyList()
                : Collections.unmodifiableList(candidates);
        this.repairRequests = repairRequests == null
                ? Collections.<SearchLayoutRepairRequest>emptyList()
                : Collections.unmodifiableList(repairRequests);
        this.providerHosts = strings(providerHosts);
        this.engineAttempts = engineAttempts == null
                ? Collections.<LegacySearchEngineAttemptResult>emptyList()
                : Collections.unmodifiableList(engineAttempts);
        this.challenges = challenges == null
                ? Collections.<SearchChallengeState>emptyList()
                : Collections.unmodifiableList(challenges);
        this.diagnostics = strings(diagnostics);
    }

    private static List<String> strings(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }
}
