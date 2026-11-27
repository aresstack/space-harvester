package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Regression for the merged language pipeline (the dev language feature through arch's full manual-search
 * pipeline): the AUTHORITATIVE per-request language snapshot set via
 * {@link WebSearchApplicationService#setSearchLanguage} reaches the {@link InitialSearchRequest} of THAT
 * search, and a language switch takes effect on the NEXT search (each manual search builds a fresh service
 * with the current snapshot) — no session restart, and a missing snapshot keeps the provider default (null).
 */
public class SearchLanguageSnapshotTest {

    /** Captures the request and returns an honest empty search, so execute() ends without a browser. */
    private static final class CapturingStrategy implements SearchStrategy {
        InitialSearchRequest lastRequest;

        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            this.lastRequest = request;
            return new InitialSearchResult(new ArrayList<SearchResultCandidate>(),
                    new ArrayList<String>(), Collections.<com.aresstack.askai.browser.search.repair
                            .SearchChallengeState>emptyList(), new ArrayList<String>());
        }
    }

    private static WebSearchApplicationService service(CapturingStrategy strategy) {
        ToolInvoker noBrowser = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) {
                return ""; // HUD render/poll tolerate an empty reply; nothing else is reached
            }
        };
        return new WebSearchApplicationService(noBrowser, ResearchRunBudget.defaults(),
                new ResearchRunProgress(),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return 0L;
                    }

                    public void sleepMillis(long millis) {
                    }
                },
                new ResearchLoopListener() {
                    public void status(String message) {
                    }

                    public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String family, String url, boolean resolved) {
                    }
                },
                new AtomicBoolean(false), strategy, null,
                null /* reranker unused: an empty search never reranks */,
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                new SourceAcceptancePort() {
                    public String accept(String captureId) {
                        return "";
                    }

                    public void park(String url, String title, String excerpt, double score) {
                    }
                },
                0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(WebSearchApplicationService.AcceptedSource source,
                                                         WebSearchApplicationService.ToolBudget budget) {
                        return null;
                    }
                },
                false, 1, 48);
    }

    private static String terms() {
        return "wearables";
    }

    @Test
    public void theLanguageSnapshotReachesTheInitialSearchRequest() {
        CapturingStrategy strategy = new CapturingStrategy();
        WebSearchApplicationService en = service(strategy);
        en.setSearchLanguage("en");
        en.execute(terms());
        assertEquals("en", strategy.lastRequest.getLanguage());
    }

    @Test
    public void aLanguageSwitchTakesEffectOnTheNextSearchWithoutASessionRestart() {
        CapturingStrategy strategy = new CapturingStrategy();
        // First manual search under "en" …
        WebSearchApplicationService first = service(strategy);
        first.setSearchLanguage("en");
        first.execute(terms());
        assertEquals("en", strategy.lastRequest.getLanguage());
        // … the user switches the session language; the NEXT search (a fresh per-search service, exactly
        // how handleManualSearch builds one per request) carries the new snapshot.
        WebSearchApplicationService second = service(strategy);
        second.setSearchLanguage("de");
        second.execute(terms());
        assertEquals("de", strategy.lastRequest.getLanguage());
    }

    /**
     * The engines receive the user's ORIGINAL text. The query was once rebuilt from the ASCII term
     * set, which split every umlaut word apart and dropped short leftovers — "hühner unterschiede
     * puten" reached Bing as "hner unterschiede puten".
     */
    @Test
    public void theSearchEngineReceivesTheOriginalQueryTextUmlautsAndAll() {
        CapturingStrategy strategy = new CapturingStrategy();
        WebSearchApplicationService service = service(strategy);
        service.execute("hühner unterschiede puten");
        assertEquals("hühner unterschiede puten", strategy.lastRequest.getQuery());
    }

    /** The sidecar's serp-pages diagnostics render as the card's "Suche:" line — or stay silent. */
    @Test
    public void theSerpPageCountsRenderForTheFunnelCard() {
        assertEquals("html.duckduckgo.com 3 Seiten, www.bing.com 1 Seite",
                WebSearchApplicationService.serpSummaryOf(java.util.Arrays.asList(
                        "prepare status=ORGANIC_RESULTS",
                        "serp-pages: html.duckduckgo.com=3, www.bing.com=1")));
        assertEquals("no serp diagnostics (API provider) — no line", "",
                WebSearchApplicationService.serpSummaryOf(java.util.Arrays.asList("other")));
        assertEquals("", WebSearchApplicationService.serpSummaryOf(null));
    }

    @Test
    public void aMissingSnapshotKeepsTheProviderDefault() {
        CapturingStrategy strategy = new CapturingStrategy();
        WebSearchApplicationService legacy = service(strategy);
        legacy.setSearchLanguage("  "); // legacy envelope without a language field
        legacy.execute(terms());
        assertNull(strategy.lastRequest.getLanguage());
    }
}
