package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5c/A5-hardening proof: the strict client returns validated rows verbatim and hard-fails (never
 * guesses) on any non-2xx status, timeout, or contract-invalid body — non-finite score, duplicate or
 * out-of-range index, a wrong response model, or an incomplete result set.
 */
public class HttpRerankerClientTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String MODEL = "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest";

    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus;
    private volatile long handlerSleepMillis;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rerank", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                exchange.getRequestBody().close();
                if (handlerSleepMillis > 0) {
                    try {
                        Thread.sleep(handlerSleepMillis);
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
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpRerankerClient client(long timeoutMillis) {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL,
                "http://127.0.0.1:" + server.getAddress().getPort(), MODEL,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT,
                timeoutMillis, RerankerSelectionConfiguration.topN(10));
        return new HttpRerankerClient(descriptor);
    }

    private HttpRerankerClient client() {
        return client(5_000L);
    }

    private List<String> docs() {
        return Arrays.asList("doc a", "doc b", "doc c");
    }

    @Test
    public void returnsValidatedRowsInResponseOrderWithDurations() throws Exception {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":["
                + "{\"index\":2,\"score\":-0.5},"
                + "{\"index\":0,\"score\":0.12},"
                + "{\"index\":1,\"score\":-3.4}],"
                + "\"total_duration\":123,\"load_duration\":45}";
        RerankResponse response = client().rerank("q", docs());

        assertEquals(MODEL, response.model);
        assertEquals(3, response.scores.size());
        assertEquals(2, response.scores.get(0).documentIndex);
        assertEquals(-0.5, response.scores.get(0).score, 1e-9);
        assertEquals(0.12, response.scores.get(1).score, 1e-9);
        assertEquals(123L, response.totalDurationNanos);
        assertEquals(45L, response.loadDurationNanos);
    }

    @Test
    public void rejectsWrongResponseModel() {
        responseStatus = 200;
        responseBody = "{\"model\":\"some/other-model\",\"results\":["
                + "{\"index\":0,\"score\":1.0},{\"index\":1,\"score\":0.5},{\"index\":2,\"score\":0.1}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsIncompleteResultSet() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[{\"index\":0,\"score\":1.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsNonFiniteScore() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":["
                + "{\"index\":0,\"score\":1e400},{\"index\":1,\"score\":0.5},{\"index\":2,\"score\":0.1}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsDuplicateIndex() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":["
                + "{\"index\":0,\"score\":1.0},{\"index\":0,\"score\":2.0},{\"index\":2,\"score\":0.1}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsOutOfRangeIndex() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[{\"index\":9,\"score\":1.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsNonJsonBody() {
        responseStatus = 200;
        responseBody = "not json at all";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void surfacesNonSuccessStatus() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom\"}";
        expectFailure(RerankerClientFailure.HTTP_STATUS);
    }

    @Test
    public void classifiesSocketTimeoutAsTimeout() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[]}";
        handlerSleepMillis = 1_500L;
        try {
            client(250L).rerank("q", docs());
            fail("expected a TIMEOUT failure");
        } catch (RerankerClientException e) {
            assertEquals(e.getMessage(), RerankerClientFailure.TIMEOUT, e.getFailure());
        }
    }

    @Test
    public void cancellationBeforeCallYieldsCancelled() {
        responseStatus = 200;
        responseBody = "{\"model\":\"" + MODEL + "\",\"results\":[]}";
        try {
            client().rerank("q", docs(), new com.aresstack.askai.browser.search.inference
                    .CancellationSignal() {
                public boolean isCancelled() {
                    return true;
                }
            });
            fail("expected a CANCELLED failure");
        } catch (RerankerClientException e) {
            assertEquals(RerankerClientFailure.CANCELLED, e.getFailure());
        }
    }

    private void expectFailure(RerankerClientFailure expected) {
        try {
            client().rerank("q", docs());
            fail("expected a " + expected + " failure");
        } catch (RerankerClientException e) {
            assertEquals(e.getMessage(), expected, e.getFailure());
            assertTrue(e.getMessage(), e.getMessage().length() > 0);
        }
    }
}
