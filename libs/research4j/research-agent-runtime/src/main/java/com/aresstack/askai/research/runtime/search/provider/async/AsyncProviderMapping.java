package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

/**
 * Pure mapping between the runtime search type-space
 * ({@link SearchProviderId}/{@link SearchEngine}) and the async module's own
 * ({@link com.aresstack.askai.research.search.api.SearchProviderId}/{@link
 * com.aresstack.askai.research.search.api.SearchEngine}). Only the migrated providers map; anything
 * else (e.g. the module's {@code COMPOSITE}) returns {@code null} so the caller can skip it.
 */
public final class AsyncProviderMapping {

    private AsyncProviderMapping() {
    }

    /** The runtime id for a module provider id, or {@code null} when it has no runtime counterpart. */
    public static SearchProviderId toRuntimeId(
            com.aresstack.askai.research.search.api.SearchProviderId moduleId) {
        if (moduleId == null) {
            return null;
        }
        switch (moduleId) {
            case BRAVE:
                return SearchProviderId.BRAVE_SEARCH_API;
            case BRIGHT_DATA:
                return SearchProviderId.BRIGHT_DATA;
            case DATA_FOR_SEO:
                return SearchProviderId.DATA_FOR_SEO;
            case SERP_API:
                return SearchProviderId.SERP_API;
            case GOOGLE_BROWSER:
                return SearchProviderId.GOOGLE_BROWSER;
            default:
                return null; // COMPOSITE and any future module-only ids are not wired into the runtime seam
        }
    }

    /** The search engine a migrated provider labels its results with, or {@code null} if not migrated. */
    public static SearchEngine defaultEngine(SearchProviderId runtimeId) {
        if (runtimeId == null) {
            return null;
        }
        switch (runtimeId) {
            case BRAVE_SEARCH_API:
                return SearchEngine.BRAVE;
            case BRIGHT_DATA:
            case DATA_FOR_SEO:
            case SERP_API:
                return SearchEngine.PROVIDER_DEFAULT; // the real engine comes from each result (toRuntimeEngine)
            case GOOGLE_BROWSER:
                return SearchEngine.GOOGLE;
            default:
                return null;
        }
    }

    /**
     * The runtime engine for the engine a module result reports, or {@code fallback} when the module engine
     * has no runtime counterpart ({@code MULTI}, {@code SEZNAM}) or is absent.
     */
    public static SearchEngine toRuntimeEngine(com.aresstack.askai.research.search.api.SearchEngine moduleEngine,
                                               SearchEngine fallback) {
        if (moduleEngine == null) {
            return fallback;
        }
        switch (moduleEngine) {
            case BRAVE:
                return SearchEngine.BRAVE;
            case GOOGLE:
                return SearchEngine.GOOGLE;
            case BING:
                return SearchEngine.BING;
            case YANDEX:
                return SearchEngine.YANDEX;
            case BAIDU:
                return SearchEngine.BAIDU;
            case DUCKDUCKGO:
                return SearchEngine.DUCK_DUCK_GO;
            case YAHOO:
                return SearchEngine.YAHOO;
            case NAVER:
                return SearchEngine.NAVER;
            default:
                return fallback;
        }
    }
}
