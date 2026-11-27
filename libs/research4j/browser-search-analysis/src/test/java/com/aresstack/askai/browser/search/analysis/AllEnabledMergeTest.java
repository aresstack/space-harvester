package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ALL_ENABLED is a different question from FIRST_USABLE, not a longer fallback chain: asking several
 * engines is only worth anything if what they found is actually brought together, and if a hit can still
 * say which engine produced it.
 */
public class AllEnabledMergeTest {

    private static SearchResultCandidate hit(String url, String engineHost) {
        return new SearchResultCandidate("c-" + url, "snap", url, url, "T " + url, "S", "example.test",
                1, "c1", "b1", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList(),
                engineHost);
    }

    private static PreparedWebSearchResult organic(SearchResultCandidate... candidates) {
        return new PreparedWebSearchResult(WebSearchPreparationStatus.ORGANIC_RESULTS,
                Arrays.asList(candidates),
                Collections.<com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest>
                        emptyList(),
                Collections.<String>emptyList(),
                Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                Collections.<String>emptyList());
    }

    @Test
    public void everyEnginesHitsSurviveTheMergeWithTheirProvenance() {
        PreparedWebSearchResult ddg = organic(hit("https://a.test", "html.duckduckgo.com"),
                hit("https://b.test", "html.duckduckgo.com"));
        PreparedWebSearchResult bing = organic(hit("https://c.test", "www.bing.com"));

        PreparedWebSearchResult merged = WebSearchLayoutRepairService.merge(
                Arrays.asList(ddg, bing), EngineAcquisitionMode.ALL_ENABLED);

        assertEquals(WebSearchPreparationStatus.ORGANIC_RESULTS, merged.status);
        assertEquals(3, merged.candidates.size());
        assertEquals("html.duckduckgo.com", merged.candidates.get(0).engineHost);
        assertEquals("www.bing.com", merged.candidates.get(2).engineHost);
    }

    /** The same page found twice is one hit, and it keeps the engine that reported it first. */
    @Test
    public void aPageFoundByTwoEnginesAppearsOnce() {
        PreparedWebSearchResult ddg = organic(hit("https://same.test", "html.duckduckgo.com"));
        PreparedWebSearchResult bing = organic(hit("https://same.test", "www.bing.com"),
                hit("https://other.test", "www.bing.com"));

        List<SearchResultCandidate> merged = WebSearchLayoutRepairService.merge(
                Arrays.asList(ddg, bing), EngineAcquisitionMode.ALL_ENABLED).candidates;

        assertEquals(2, merged.size());
        assertEquals("https://same.test", merged.get(0).resolvedTargetUrl);
        assertEquals("html.duckduckgo.com", merged.get(0).engineHost);
    }

    /** FIRST_USABLE keeps its old meaning: the first engine that delivered answers alone. */
    @Test
    public void firstUsableTakesOnlyTheEngineThatDelivered() {
        PreparedWebSearchResult ddg = organic(hit("https://a.test", "html.duckduckgo.com"));
        PreparedWebSearchResult bing = organic(hit("https://c.test", "www.bing.com"));

        List<SearchResultCandidate> merged = WebSearchLayoutRepairService.merge(
                Arrays.asList(ddg, bing), EngineAcquisitionMode.FIRST_USABLE).candidates;

        assertEquals(1, merged.size());
        assertTrue(merged.get(0).engineHost.contains("duckduckgo"));
    }

    /**
     * One engine, several RESULT PAGES (the per-engine page count is a user setting): every page is
     * that engine's answer. FIRST_USABLE once returned at the first delivered PAGE, silently throwing
     * away the deeper pages it had just fetched.
     */
    @Test
    public void firstUsableKeepsEveryResultPageOfTheDeliveringEngine() {
        PreparedWebSearchResult page1 = organic(hit("https://a.test", "www.bing.com"),
                hit("https://b.test", "www.bing.com"));
        PreparedWebSearchResult page2 = organic(hit("https://b.test", "www.bing.com"),
                hit("https://c.test", "www.bing.com"));

        List<SearchResultCandidate> merged = WebSearchLayoutRepairService.merge(
                Arrays.asList(page1, page2), EngineAcquisitionMode.FIRST_USABLE).candidates;

        assertEquals("2 + 1 new — the repeated target is carried once", 3, merged.size());
        assertEquals("https://c.test", merged.get(2).resolvedTargetUrl);
    }
}
