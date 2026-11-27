package com.aresstack.askai.research.runtime.search;

/**
 * The session-level choice of how initial URLs are discovered. The first slice supports the unchanged
 * browser SERP path and a single API provider; fallback/multi-provider strategies are added later without
 * touching this seam. A running research session keeps its selection unchanged — it is fixed at session
 * start from the settings snapshot.
 */
public enum StrategySelection {
    LEGACY_BROWSER,
    API_PROVIDER
}
