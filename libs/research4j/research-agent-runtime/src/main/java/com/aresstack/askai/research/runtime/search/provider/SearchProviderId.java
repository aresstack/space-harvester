package com.aresstack.askai.research.runtime.search.provider;

/**
 * The stable identity of every catalogued search provider. The first slice implements DATA_FOR_SEO
 * productively; BRAVE_SEARCH_API and BRIGHT_DATA are registered as neutral contracts and become productive
 * over the same port in follow-up commits; every other id is registered but throws
 * {@link SearchProviderNotImplementedException} when used.
 */
public enum SearchProviderId {

    BRAVE_SEARCH_API,
    BRIGHT_DATA,
    DATA_FOR_SEO,

    SERP_API,
    /** Google in AskAI's own search browser (askai #31: Serp4J serp4j-playwright-google). */
    GOOGLE_BROWSER,
    AWS_AGENTCORE_WEB_SEARCH,
    AZURE_OPENAI_WEB_SEARCH,
    YANDEX_CLOUD_SEARCH_API,
    BAIDU_QIANFAN_WEB_SEARCH,
    GOOGLE_CUSTOM_SEARCH_JSON_API,
    BING_WEB_SEARCH_API,
    SEARXNG,
    TAVILY,
    EXA
}
