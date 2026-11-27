package com.aresstack.askai.research.runtime.search.provider;

/**
 * The search INDEX/ranking a provider queries, modelled separately from the provider itself: Bright Data
 * and DataForSEO can both return Google results, so "Bright Data / Google" and "DataForSEO / Google" are
 * the SAME engine confirming one Google placement — not two independent search signals. {@code
 * PROVIDER_DEFAULT} lets a provider use whatever engine it is configured for.
 */
public enum SearchEngine {
    BRAVE,
    GOOGLE,
    BING,
    YANDEX,
    BAIDU,
    DUCK_DUCK_GO,
    YAHOO,
    NAVER,
    PROVIDER_DEFAULT
}
