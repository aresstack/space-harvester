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
 * Regression for the "green check without a single searched page" bug: when the INITIAL search
 * THROWS (codec/prepare failure, cold browser, …), {@code initialStatus} used to keep its
 * NO_RESULTS initializer, so the empty frontier was labelled NO_RELEVANT_PATHS — reported to the
 * host as a successful completion (badge removed, no retry, query burned), although nothing was
 * ever searched. A thrown seed search must end as SEARCH_TECHNICAL_PROBLEM (→ manual_search_failed
 * → visible problem + retry), while an HONEST empty search keeps its NO_RELEVANT_PATHS completion.
 */
public class SeedSearchFailureOutcomeTest {

    private static final class ThrowingStrategy implements SearchStrategy {
        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            throw new IllegalStateException("prepare payload could not be decoded");
        }
    }

    private static final class EmptyStrategy implements SearchStrategy {
        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            return new InitialSearchResult(new ArrayList<SearchResultCandidate>(),
                    new ArrayList<String>(), Collections.<com.aresstack.askai.browser.search.repair
                            .SearchChallengeState>emptyList(), new ArrayList<String>());
        }
    }

    @Test
    public void aThrowingSeedSearchIsATechnicalProblemNeverAFakeSuccess() {
        assertEquals(ResearchStopReason.SEARCH_TECHNICAL_PROBLEM,
                service(new ThrowingStrategy()).execute("regionale kartoffelsalatstile"));
    }

    @Test
    public void anHonestEmptySearchStaysACompletion() {
        assertEquals(ResearchStopReason.NO_RELEVANT_PATHS,
                service(new EmptyStrategy()).execute("regionale kartoffelsalatstile"));
    }

    private static WebSearchApplicationService service(SearchStrategy strategy) {
        ToolInvoker noBrowser = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) {
                return "";
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
                null /* reranker unused: the run never reaches reranking */,
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
}
