package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.Assert.assertEquals;

/**
 * A5g: the reranker maps every client-level failure onto a typed {@link SearchResultRerankingOutcome},
 * never throwing and never silently returning engine order.
 */
public class SearchResultRerankerOutcomeTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String MODEL = "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest";

    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus;
    private volatile long sleepMillis;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rerank", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                exchange.getRequestBody().close();
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = responseBody.getBytes(UTF_8);
                exchange.sendResponseHeaders(responseStatus, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                out.close();
            }
        });
        server.start();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static SearchResultCandidate candidate(int i) {
        return new SearchResultCandidate("c" + i, "snap", "https://c" + i + ".example", "", "t" + i,
                "s" + i, "", i + 1, "rc", "rb", 0.9, 0.9,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    private static List<SearchResultCandidate> twoCandidates() {
        return Arrays.asList(candidate(0), candidate(1));
    }

    private SearchResultReranker reranker(RerankerSelectionConfiguration selection, long timeoutMillis) {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL,
                "http://127.0.0.1:" + server.getAddress().getPort(), MODEL,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT,
                timeoutMillis, selection);
        return new SearchResultReranker(new HttpRerankerClient(descriptor),
                new SearchResultSelectionPolicy(selection), MODEL, RerankerScoreSemantics.RAW_LOGIT);
    }

    private SearchResultReranker reranker() {
        return reranker(RerankerSelectionConfiguration.topN(10), 5_000L);
    }

    private SearchResultRerankingOutcome outcome(SearchResultReranker reranker) {
        return reranker.rerank("q", twoCandidates(), CancellationSignal.NONE).outcome;
    }

    @Test
    public void successCarriesModelAndDurations() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":["
                + "{\"index\":0,\"score\":0.4},{\"index\":1,\"score\":-1.0}],"
                + "\"total_duration\":99,\"load_duration\":7}";
        SearchResultRerankingResult result =
                reranker().rerank("q", twoCandidates(), CancellationSignal.NONE);
        assertEquals(SearchResultRerankingOutcome.SUCCESS, result.outcome);
        assertEquals(MODEL, result.modelName);
        assertEquals(99L, result.totalDurationNanos);
        assertEquals(2, result.selected.size());
    }

    @Test
    public void httpErrorBecomesRerankerUnavailable() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom\"}";
        assertEquals(SearchResultRerankingOutcome.RERANKER_UNAVAILABLE, outcome(reranker()));
    }

    @Test
    public void wrongModelBecomesInvalidResponse() {
        responseStatus = 200;
        responseBody = "{\"model\":\"other\",\"results\":["
                + "{\"index\":0,\"score\":0.4},{\"index\":1,\"score\":-1.0}]}";
        assertEquals(SearchResultRerankingOutcome.INVALID_RESPONSE, outcome(reranker()));
    }

    @Test
    public void timeoutBecomesTimeout() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[]}";
        sleepMillis = 1_500L;
        assertEquals(SearchResultRerankingOutcome.TIMEOUT,
                outcome(reranker(RerankerSelectionConfiguration.topN(10), 250L)));
    }

    @Test
    public void cancellationBecomesCancelled() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[]}";
        SearchResultRerankingResult result = reranker().rerank("q", twoCandidates(),
                new CancellationSignal() {
                    public boolean isCancelled() {
                        return true;
                    }
                });
        assertEquals(SearchResultRerankingOutcome.CANCELLED, result.outcome);
    }

    @Test
    public void emptyCandidatesBecomeNoCandidates() {
        assertEquals(SearchResultRerankingOutcome.NO_CANDIDATES,
                reranker().rerank("q", Collections.<SearchResultCandidate>emptyList(),
                        CancellationSignal.NONE).outcome);
    }

    @Test
    public void everythingBelowTheFloorBecomesNoSemanticMatches() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":["
                + "{\"index\":0,\"score\":-2.0},{\"index\":1,\"score\":-3.0}]}";
        RerankerSelectionConfiguration strict = new RerankerSelectionConfiguration(10,
                OptionalDouble.of(100.0), OptionalDouble.empty(), OptionalDouble.empty());
        assertEquals(SearchResultRerankingOutcome.NO_SEMANTIC_MATCHES,
                outcome(reranker(strict, 5_000L)));
    }
}
