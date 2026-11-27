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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;

/**
 * A CAPTCHA on the ENGINE PAGES does not end the search: when the SERP delivered nothing because a
 * challenge blocked it, the run waits for the user (compensated time, cancel-aware) and then asks the
 * engines AGAIN. Solving the captcha leads back to the search — never into a dead end where the user
 * solved it for nothing.
 */
public class SerpChallengeRetryTest {

    /** Round 1: challenge-blocked, nothing usable. Round 2 (after the user solved it): asked again. */
    private static final class ChallengedThenEmptyStrategy implements SearchStrategy {
        int calls;

        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            calls++;
            if (calls == 1) {
                return new InitialSearchResult(new ArrayList<SearchResultCandidate>(),
                        new ArrayList<String>(),
                        Collections.singletonList(
                                new com.aresstack.askai.browser.search.repair.SearchChallengeState(
                                        "bing.com", "https://www.bing.com/search?q=x")),
                        new ArrayList<String>());
            }
            // The retry finds an honestly empty SERP — the run then ends with NO_RELEVANT_PATHS,
            // never with a technical or budget end.
            return new InitialSearchResult(new ArrayList<SearchResultCandidate>(),
                    new ArrayList<String>(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>
                            emptyList(),
                    new ArrayList<String>());
        }
    }

    @Test
    public void aSolvedSerpChallengeLeadsBackToTheSearch() {
        final ChallengedThenEmptyStrategy strategy = new ChallengedThenEmptyStrategy();
        // The challenge poll reports RESOLVED on the first probe — the user solved the captcha.
        ToolInvoker browser = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) {
                return "web_challenge_status".equals(tool) ? "RESOLVED: bing.com" : "";
            }
        };
        final long[] now = {0L};
        WebSearchApplicationService service = new WebSearchApplicationService(browser,
                ResearchRunBudget.defaults(), new ResearchRunProgress(),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return now[0];
                    }

                    public void sleepMillis(long millis) {
                        now[0] += Math.max(1L, millis); // waiting advances time, tests never sleep
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
                null /* reranker unused: both rounds return no candidates */,
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
                true /* wait for the user on a challenge */, 1, 48);

        ResearchStopReason reason = service.execute("puten preise");

        assertEquals("the engines are asked AGAIN after the user solved the challenge",
                2, strategy.calls);
        assertEquals("an honestly empty retry ends honestly",
                ResearchStopReason.NO_RELEVANT_PATHS, reason);
    }

    /**
     * A worker's user context that is lost while it waits on a CAPTCHA never answers again (unlike the chat
     * browser, which restarts): the wait ends technically, so the worker gives its lease back and the package
     * can be retried, instead of polling a dead user context until the user cancels the turn.
     */
    @Test(timeout = 10_000L)
    public void aLostWorkerUserContextEndsTheChallengeWait() {
        final ChallengedThenEmptyStrategy strategy = new ChallengedThenEmptyStrategy();
        ToolInvoker lost = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) throws EndpointUnavailable {
                throw new EndpointUnavailable("USER_CONTEXT_LOST — worker browser w1 was terminated");
            }
        };
        final long[] now = {0L};
        WebSearchApplicationService service = new WebSearchApplicationService(lost,
                ResearchRunBudget.defaults(), new ResearchRunProgress(),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return now[0];
                    }

                    public void sleepMillis(long millis) {
                        now[0] += Math.max(1L, millis);
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
                new AtomicBoolean(false), strategy, null, null,
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
                true, 1, 48);

        assertEquals("a technical, retryable end — not the user's cancel, not an endless wait",
                ResearchStopReason.MCP_UNAVAILABLE, service.execute("puten preise"));
        assertEquals("no second search on a dead user context", 1, strategy.calls);
    }
}
