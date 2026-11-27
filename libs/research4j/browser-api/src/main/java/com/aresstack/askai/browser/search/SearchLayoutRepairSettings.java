package com.aresstack.askai.browser.search;

/**
 * Bounds of the sidecar's per-session layout-repair ticket cache (A4 transport). Part of the versioned
 * {@link LegacyBrowserSearchSettings} contract and its digest, so the ticket cache is never configured
 * by literals in the sidecar main or the repair tools. Validated: a positive maximum and a positive
 * TTL.
 */
public final class SearchLayoutRepairSettings {

    /** Maximum number of low-confidence snapshots held at once; oldest evicted first. */
    public final int maximumCachedTickets;
    /** How long a repair ticket stays applicable before it expires. */
    public final long ticketTtlMillis;

    public SearchLayoutRepairSettings(int maximumCachedTickets, long ticketTtlMillis) {
        this.maximumCachedTickets = maximumCachedTickets;
        this.ticketTtlMillis = ticketTtlMillis;
    }
}
