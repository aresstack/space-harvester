package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The productive search provider is a thin adapter over the mechanical SERP analysis: repeated
 * result blocks with resolved DIRECT targets become typed candidates; engine navigation never
 * does; a page without result structure is a typed EXTRACTION_FAILED — there is no flat-anchor
 * code path left (the detailed judgement is covered by the :browser-search-analysis tests).
 */
public class WebSearchProviderTest {

    private final WebSearchProvider provider = new WebSearchProvider.OrganicResultSearchProvider(
            LegacyBrowserSearchDefaults.create());

    private static String bingWrapped(String target) {
        return "https://www.bing.com/ck/a?!&&p=abc&u=a1"
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(target.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static RenderedPageDocument bingSerp(String... titleUrlPairs) {
        List<PlaywrightPageState.Anchor> anchors = new ArrayList<PlaywrightPageState.Anchor>();
        anchors.add(new PlaywrightPageState.Anchor("Videos",
                "https://www.bing.com/videos/search?q=pf4j"));
        anchors.add(new PlaywrightPageState.Anchor("Einstellungen",
                "https://www.bing.com/account/general"));
        for (int i = 0; i + 1 < titleUrlPairs.length; i += 2) {
            anchors.add(new PlaywrightPageState.Anchor(titleUrlPairs[i], titleUrlPairs[i + 1]));
        }
        return SyntheticRenderedDocuments.fromState(new PlaywrightPageState(
                        "https://www.bing.com/search?q=pf4j", "pf4j - Suchen", "results", anchors),
                new PublicSuffixDomainKeyResolver(), 1L);
    }

    @Test
    public void repeatedExternalBlocksBecomeCandidatesNavigationNever() {
        String wrapped = bingWrapped("https://pf4j.org/doc/getting-started.html");
        SearchResultExtractionResult result = provider.extract(bingSerp(
                "PF4J - Plugin Framework for Java", wrapped,
                "pf4j/pf4j: Plugin Framework", "https://github.com/pf4j/pf4j",
                "PF4J tutorial", "https://www.baeldung.com/pf4j"));

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(3, result.candidates.size());
        assertEquals("the resolved DIRECT target is the navigation candidate",
                "https://pf4j.org/doc/getting-started.html",
                result.candidates.get(0).resolvedTargetUrl);
        assertTrue("the raw wrapper stays diagnostic provenance",
                result.candidates.get(0).rawSearchHref.contains("/ck/"));
        for (com.aresstack.askai.browser.search.SearchResultCandidate candidate
                : result.candidates) {
            assertFalse(candidate.resolvedTargetUrl.contains("/videos/"));
            assertFalse(candidate.resolvedTargetUrl.contains("/account/"));
        }
    }

    @Test
    public void aPageWithoutResultStructureIsATypedExtractionFailure() {
        SearchResultExtractionResult result = provider.extract(bingSerp());
        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
        assertTrue(result.candidates.isEmpty());
    }
}
