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

/**
 * Closing the browser window IS the user's stop signal. The sidecar reports it as the typed
 * BROWSER_CLOSED tool error; the acquisition then ends the run as USER_CANCELLED — the user's decision,
 * sources kept — instead of the historic behaviour, where 'Target closed' matched no classifier and the
 * run kept polling a dead browser until some budget gave out (with the Websuche bubble stuck forever).
 */
public class BrowserClosedByUserTest {

    /** One candidate, so the run has work — the browser then reports the closed window on every call. */
    private static final class OneHitStrategy implements SearchStrategy {
        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
            candidates.add(new SearchResultCandidate("c-1", "snap-1", "https://example.org/a",
                    "https://example.org/a", "A", "snippet", "example.org", 1, "cont-1", "cont-1",
                    1.0, 1.0, Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList()));
            return new InitialSearchResult(candidates, new ArrayList<String>(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                    new ArrayList<String>());
        }
    }

    /** Selects the one candidate, so the run reaches its first BROWSER call (where the window is gone). */
    private static final class SelectAllReranker
            implements com.aresstack.askai.research.runtime.rerank.CandidateReranker {
        public com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult rerank(
                String query, List<SearchResultCandidate> candidates, CancellationSignal cancellation) {
            List<com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate> ranked =
                    new ArrayList<com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(new com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate(
                        candidates.get(i), 0.5, i + 1));
            }
            return new com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult(
                    com.aresstack.askai.research.runtime.rerank.SearchResultRerankingOutcome.SUCCESS,
                    ranked, ranked, "fake",
                    com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics.RAW_LOGIT,
                    "test", 0L, 0L);
        }

        public com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
                String query, java.util.LinkedHashMap<String, String> documentsById,
                CancellationSignal cancellation) {
            return com.aresstack.askai.research.domain.search.RelevanceAssessment
                    .unavailable("never reached — the browser call fails first");
        }
    }

    @Test
    public void aClosedBrowserWindowEndsTheRunAsTheUsersCancel() {
        // The realistic live shape: the SEARCH worked, the user closed the window, and the FIRST browser
        // call of the visit (probe/read/HUD) reports the typed BROWSER_CLOSED tool error.
        ToolInvoker closedBrowser = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) throws ToolFailure {
                throw new ToolFailure(
                        "BROWSER_CLOSED — the browser window was closed by the user.");
            }
        };
        WebSearchApplicationService service = new WebSearchApplicationService(closedBrowser,
                ResearchRunBudget.defaults(), new ResearchRunProgress(),
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
                new AtomicBoolean(false), new OneHitStrategy(), null,
                new SelectAllReranker(),
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
        ResearchStopReason reason = service.execute("blashuener");
        assertEquals("the user's window close is the user's cancel — never a budget or technical end",
                ResearchStopReason.USER_CANCELLED, reason);
    }

    /**
     * The window can also be closed DURING the SERP phase (web_search_prepare) — the marker then
     * surfaces from the search strategy, not from a page-visit browser call. It once fell into the
     * generic seed-failure catch and ended as "Websuche technisch fehlgeschlagen".
     */
    @Test
    public void aWindowClosedDuringTheSerpPhaseIsStillTheUsersCancel() {
        SearchStrategy closedDuringSerp = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request,
                    CancellationSignal cancellation, SearchBudgetGate budget) {
                throw new IllegalStateException(
                        "BROWSER_CLOSED — the browser window was closed by the user.");
            }
        };
        ToolInvoker neverReached = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) {
                return "";
            }
        };
        WebSearchApplicationService service = new WebSearchApplicationService(neverReached,
                ResearchRunBudget.defaults(), new ResearchRunProgress(),
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
                new AtomicBoolean(false), closedDuringSerp, null,
                new SelectAllReranker(),
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
        assertEquals(ResearchStopReason.USER_CANCELLED, service.execute("blashuener"));
    }
}
