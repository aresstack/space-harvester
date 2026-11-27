package com.aresstack.askai.research.runtime.acquire;

/**
 * ONE navigation target the acquisition engine still has to work through — a runtime-internal work item,
 * deliberately NOT a search candidate.
 * <p>
 * The frontier is fed from several sources: selected search hits, links discovered on a visited page, and
 * URLs re-queued after a challenge was solved. Only the first kind has a rank, a snippet and a result page;
 * a discovered link has a parent page instead. Forcing all of them into one "candidate" type would mean
 * inventing ranks for links and losing the provenance that makes a later trace readable.
 * <p>
 * An entry that came from a search hit carries its {@link #getSearchCandidateId()}, so anything the engine
 * does with it can be reported back to exactly that candidate.
 */
public final class FrontierEntry {

    /** Where this target came from — provenance, not priority. */
    public enum Origin {
        /** A hit of the initial search, selected for inspection. */
        SEARCH_RESULT,
        /** A link found on a page that was visited. */
        DISCOVERED_LINK,
        /** Re-queued after the challenge that blocked its domain was resolved. */
        REQUEUED
    }

    private final String url;
    private final Origin origin;
    private final String searchCandidateId;
    private final String parentUrl;
    private final String expectedContent;

    private FrontierEntry(String url, Origin origin, String searchCandidateId, String parentUrl,
                          String expectedContent) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        this.url = url.trim();
        this.origin = origin == null ? Origin.DISCOVERED_LINK : origin;
        this.searchCandidateId = searchCandidateId == null ? "" : searchCandidateId.trim();
        this.parentUrl = parentUrl == null ? "" : parentUrl.trim();
        this.expectedContent = expectedContent == null ? "" : expectedContent.trim();
    }

    /** The same entry under another address (an engine redirect wrapper resolved to its target). */
    public FrontierEntry withUrl(String resolvedUrl) {
        return new FrontierEntry(resolvedUrl, origin, searchCandidateId, parentUrl, expectedContent);
    }

    /**
     * A selected search hit. {@code expectedContent} is what the result page promised (title + snippet) —
     * the semantic net the readiness judge checks a loaded page against.
     */
    public static FrontierEntry fromSearchResult(String url, String candidateId, String expectedContent) {
        return new FrontierEntry(url, Origin.SEARCH_RESULT, candidateId, "", expectedContent);
    }

    /** A link discovered on {@code parentUrl}: no rank, no snippet, but a traceable parent. */
    public static FrontierEntry fromDiscoveredLink(String url, String parentUrl) {
        return new FrontierEntry(url, Origin.DISCOVERED_LINK, "", parentUrl, "");
    }

    /** The same target again after its domain was unblocked; provenance and expectation are kept. */
    public FrontierEntry requeued() {
        return new FrontierEntry(url, Origin.REQUEUED, searchCandidateId, parentUrl, expectedContent);
    }

    public String getUrl() {
        return url;
    }

    public Origin getOrigin() {
        return origin;
    }

    /** The search candidate this target came from, or "" when it was not a search hit. */
    public String getSearchCandidateId() {
        return searchCandidateId;
    }

    /** The page this link was found on, or "" for a search hit. */
    public String getParentUrl() {
        return parentUrl;
    }

    /** What the source of this target promised the page contains; "" when nothing was promised. */
    public String getExpectedContent() {
        return expectedContent;
    }

    public boolean hasSearchCandidate() {
        return !searchCandidateId.isEmpty();
    }

    @Override
    public String toString() {
        return origin + " " + url + (hasSearchCandidate() ? " (candidate=" + searchCandidateId + ")" : "");
    }
}
