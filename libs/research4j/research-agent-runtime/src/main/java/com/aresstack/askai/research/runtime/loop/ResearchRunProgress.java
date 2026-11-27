package com.aresstack.askai.research.runtime.loop;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mutable state of one run. Page counting is precise: {@link #pageVisited} increments ONLY for a successful
 * navigation to a NEW canonical URL (web_open/web_follow); web_read/web_links/web_back and re-opens never
 * count. {@code consecutiveErrors} means truly consecutive — any success resets it to 0 (a separate
 * {@code totalErrors} keeps the overall count).
 */
public final class ResearchRunProgress {

    private int toolCalls;
    private int pagesVisited;
    private int acceptedSources;
    private int consecutiveErrors;
    private int totalErrors;
    private final Set<String> visitedCanonicalUrls = new LinkedHashSet<String>();
    private final Set<String> distinctHosts = new LinkedHashSet<String>();

    public synchronized void toolCall() {
        toolCalls++;
    }

    /** @return true when this canonical URL is NEW (and counts as a visited page). */
    public synchronized boolean pageVisited(String canonicalUrl, String host) {
        if (canonicalUrl == null || canonicalUrl.isEmpty() || !visitedCanonicalUrls.add(canonicalUrl)) {
            return false;
        }
        pagesVisited++;
        if (host != null && !host.isEmpty()) {
            distinctHosts.add(host);
        }
        return true;
    }

    public synchronized boolean alreadyVisited(String canonicalUrl) {
        return visitedCanonicalUrls.contains(canonicalUrl);
    }

    /**
     * Mark a canonical URL as visited WITHOUT counting a page or host — used for the requested (pre-redirect)
     * address of a page whose FINAL URL was counted, and to seed exclusions when a run continues.
     */
    public synchronized void noteVisitedAlias(String canonicalUrl) {
        if (canonicalUrl != null && !canonicalUrl.isEmpty()) {
            visitedCanonicalUrls.add(canonicalUrl);
        }
    }

    public synchronized void sourceAccepted() {
        acceptedSources++;
    }

    // ------------------------------------------------------------------ link funnel
    // The user-facing search funnel: how many links the run has SEEN at all (SERP candidates + links on
    // visited pages), how many of them were ASSESSED for relevance, and how many were SELECTED to follow.
    // Together with pagesVisited/acceptedSources this answers "what did the search actually do?" instead
    // of showing only the accepted end of the funnel.

    private int linksDiscovered;
    private int linksAssessed;
    private int linksSelected;

    public synchronized void linksDiscovered(int count) {
        if (count > 0) {
            linksDiscovered += count;
        }
    }

    public synchronized void linksAssessed(int count) {
        if (count > 0) {
            linksAssessed += count;
        }
    }

    public synchronized void linksSelected(int count) {
        if (count > 0) {
            linksSelected += count;
        }
    }

    /** Which engine delivered how many SERP result pages this run ("duckduckgo.com 3 Seiten"). */
    private String serpSummary = "";

    public synchronized void setSerpSummary(String summary) {
        this.serpSummary = summary == null ? "" : summary;
    }

    public synchronized String getSerpSummary() { return serpSummary; }

    public synchronized int getLinksDiscovered() { return linksDiscovered; }
    public synchronized int getLinksAssessed() { return linksAssessed; }
    public synchronized int getLinksSelected() { return linksSelected; }

    public synchronized void error() {
        consecutiveErrors++;
        totalErrors++;
    }

    public synchronized void success() {
        consecutiveErrors = 0;
    }

    public synchronized int getToolCalls() { return toolCalls; }
    public synchronized int getPagesVisited() { return pagesVisited; }
    public synchronized int getAcceptedSources() { return acceptedSources; }
    public synchronized int getConsecutiveErrors() { return consecutiveErrors; }
    public synchronized int getTotalErrors() { return totalErrors; }
    public Set<String> getDistinctHosts() { return distinctHosts; }
    public Set<String> getVisitedCanonicalUrls() { return visitedCanonicalUrls; }
}
