package com.aresstack.askai.research.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The validated {@code ASKAI_*} configuration contract of a research agent session — the same keys the host
 * used to hand to the agent PROCESS as launch environment, now passed as a map to the in-process
 * {@link ResearchAgentSession}: session identity, the session's snapshot files (browser search settings,
 * reranker, inference descriptor, search strategy) and the numeric/boolean settings hand-offs read through
 * {@link #get(String)}. Tool endpoints are no longer part of it — they are injected as in-process invokers.
 */
public final class ResearchAgentEnvironment {

    final String sessionId;
    final String projectId;
    /** Path of the legacy-browser-search config document (same settings the browser gets), or null. */
    final String browserSearchConfigPath;
    /** Path of the reranker start snapshot (the mandatory local cross-encoder endpoint), or null. */
    final String rerankerConfigPath;
    /**
     * Optional per-language reranker snapshots (published by the host so the NEXT search after a language
     * switch resolves its language's reranker without a host round-trip). Null when not published — the
     * session-start default then serves every language (today both entries are the same selected model).
     */
    final String rerankerConfigEnPath;
    final String rerankerConfigDePath;
    /** Path of the initial-search strategy snapshot (legacy browser vs. API provider), or null. */
    final String searchStrategyConfigPath;
    /** Path of the structured-inference descriptor (the central main model for SERP repair), or null. */
    final String inferenceConfigPath;
    /** Actionable reason the main model is unavailable when no descriptor was published, or null. */
    final String inferenceUnavailableReason;
    private final Map<String, String> values;

    private ResearchAgentEnvironment(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
        this.sessionId = orDefault(values.get("ASKAI_SESSION_ID"), "");
        this.projectId = orDefault(values.get("ASKAI_PROJECT_ID"), "");
        this.browserSearchConfigPath = blankToNull(values.get("ASKAI_BROWSER_SEARCH_CONFIG"));
        this.rerankerConfigPath = blankToNull(values.get("ASKAI_RERANKER_CONFIG"));
        this.rerankerConfigEnPath = blankToNull(values.get("ASKAI_RERANKER_CONFIG_EN"));
        this.rerankerConfigDePath = blankToNull(values.get("ASKAI_RERANKER_CONFIG_DE"));
        this.searchStrategyConfigPath = blankToNull(values.get("ASKAI_SEARCH_STRATEGY_CONFIG"));
        this.inferenceConfigPath = blankToNull(values.get("ASKAI_INFERENCE_CONFIG"));
        this.inferenceUnavailableReason = blankToNull(values.get("ASKAI_INFERENCE_UNAVAILABLE_REASON"));
    }

    /** @param values the ASKAI_* hand-off (null entries and blanks count as absent). */
    public static ResearchAgentEnvironment from(Map<String, String> values) {
        return new ResearchAgentEnvironment(values == null
                ? Collections.<String, String>emptyMap() : values);
    }

    /** A raw hand-off value (trimmed), or null when absent/blank. */
    public String get(String key) {
        return blankToNull(values.get(key));
    }

    /** The whole hand-off (e.g. for the run budget codec). */
    public Map<String, String> values() {
        return values;
    }

    boolean hasSearchStrategyConfig() {
        return searchStrategyConfigPath != null;
    }

    boolean hasReranker() {
        return rerankerConfigPath != null;
    }

    boolean hasInference() {
        return inferenceConfigPath != null;
    }

    private static String blankToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String orDefault(String v, String fallback) {
        return blankToNull(v) == null ? fallback : v.trim();
    }

    @Override
    public String toString() {
        return "ResearchAgentEnvironment{sessionId=" + sessionId + ", projectId=" + projectId
                + ", reranker=" + (hasReranker() ? "<configured>" : "absent")
                + ", inference=" + (hasInference() ? "<configured>" : "absent") + "}";
    }
}
