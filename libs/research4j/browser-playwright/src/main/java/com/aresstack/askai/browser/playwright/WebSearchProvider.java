package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;

/**
 * playwright4j brings no search-engine integration, so {@code web_search} on the Playwright backend is
 * plain browser NAVIGATION to a configured provider URL ({@code {query}} placeholder, no hardcoded
 * account) — the result extraction from the loaded provider page sits behind this interface. Without a
 * configured provider URL the session reports search honestly as unavailable; there is no hidden
 * default engine and the search page itself is never a source capture.
 *
 * <p>Since A3 the extraction judges the STRUCTURED rendered page (container hierarchy, repeated
 * result blocks, primary title links, snippets) — never a flat anchor list, in no code path.</p>
 */
interface WebSearchProvider {

    /** Extract the typed candidates (with honest outcome) from the structured rendered page. */
    SearchResultExtractionResult extract(RenderedPageDocument document);

    /**
     * The productive extraction: the mechanical SERP analysis of {@code :browser-search-analysis}
     * (region classification, repeated result blocks, primary link, snippet), configured entirely
     * by the settings snapshot. An ununderstood layout yields the typed EXTRACTION_FAILED — the
     * session then tries its fallback engines; there is NO all-links degrade anywhere.
     */
    final class OrganicResultSearchProvider implements WebSearchProvider {

        private final LegacySearchResultExtractor extractor;

        OrganicResultSearchProvider(LegacyBrowserSearchSettings settings) {
            this.extractor = new LegacySearchResultExtractor(settings);
        }

        public SearchResultExtractionResult extract(RenderedPageDocument document) {
            return extractor.extract(document);
        }
    }
}
