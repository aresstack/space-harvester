package com.aresstack.askai.browser.search.repair;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import java.util.Collections;
import java.util.List;

/**
 * The typed result of {@code web_search_apply_layout}: the guard/apply status and, on
 * {@link SearchLayoutRepairStatus#ORGANIC_RESULTS}, the candidates produced by the EXISTING A3 block
 * extraction over the cached snapshot. No candidate is ever synthesized here — they come from the one
 * A3/A4 extraction implementation.
 */
public final class SearchLayoutRepairResult {

    public final SearchLayoutRepairStatus status;
    public final List<SearchResultCandidate> candidates;
    public final List<String> diagnostics;

    public SearchLayoutRepairResult(SearchLayoutRepairStatus status,
                                    List<SearchResultCandidate> candidates,
                                    List<String> diagnostics) {
        this.status = status == null ? SearchLayoutRepairStatus.EXTRACTION_FAILED : status;
        this.candidates = candidates == null
                ? Collections.<SearchResultCandidate>emptyList()
                : Collections.unmodifiableList(candidates);
        this.diagnostics = diagnostics == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(diagnostics);
    }

    public boolean isOrganic() {
        return status == SearchLayoutRepairStatus.ORGANIC_RESULTS;
    }
}
