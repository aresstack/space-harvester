package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The seam through which the deterministic web-acquisition path accepts a visited capture as a persistent
 * research source, WITHOUT knowing whether the caller is the autonomous agent loop or a user-triggered service.
 * The two callers differ only in origin/authorization, never in the acquisition code:
 * <pre>
 *   AgentSourceAcceptancePort   → source_accept          (phase-gated agent MCP tool)
 *   ManualSourceAcceptancePort  → manual_source_accept   (internal, phase-independent; wired in T2c)
 * </pre>
 * The returned string is the acceptance result line exactly as the underlying tool returns it
 * ({@code status=ACCEPTED source_id=… duplicate=…}); {@code null}/unknown handling stays with the caller so
 * behavior is preserved during the extraction.
 */
public interface SourceAcceptancePort {

    String accept(String captureId) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /**
     * Accept a capture, additionally recording whether the USER marked the page relevant (the HUD ⭐ toggle).
     * Only the manual path carries this (the autonomous agent has no HUD); the default ignores the flag so the
     * agent path is unchanged.
     */
    default String accept(String captureId, boolean userRelevant)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return accept(captureId);
    }

    /**
     * As {@link #accept(String, boolean)} but with the search's AUTHORITATIVE language snapshot ("en"/"de"),
     * so the host can stamp the knowledge-processing job with the language world of the search that found the
     * page. Empty = no snapshot. The default ignores it (agent path).
     */
    default String accept(String captureId, boolean userRelevant, String languageCode)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return accept(captureId, userRelevant);
    }

    /**
     * Park a reranked candidate as a scored source BEFORE it is visited (empty full text, status PARKED).
     * Best-effort bookkeeping — a failure must never abort the search. Mirrors {@link #accept}'s split:
     * <pre>
     *   AgentSourceAcceptancePort   → source_park          (phase-gated agent MCP tool)
     *   ManualSourceAcceptancePort  → manual_source_park   (internal, phase-independent)
     * </pre>
     */
    void park(String url, String title, String excerpt, double rerankScore)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /** The park status of an address whose page is already a captured source: the search does not read it again. */
    String ALREADY_CAPTURED = "status=ALREADY_CAPTURED";

    /**
     * As {@link #park} but returns the host's park result line
     * ({@code status=PARKED|ALREADY_PRESENT|ALREADY_CAPTURED source_id=…}). A line starting with
     * {@link #ALREADY_CAPTURED} means the page was read and accepted before (in this or an earlier run, also
     * before a restart): the search skips it instead of reading it again. The default parks and reports nothing.
     */
    default String parkReporting(String url, String title, String excerpt, double rerankScore)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        park(url, title, excerpt, rerankScore);
        return "";
    }
}
