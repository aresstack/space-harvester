package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.EndpointAuthorization;
import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The reranker on an OpenAI-compatible API ({@code POST /rerank}, the wire of Space Harvester's
 * {@code KipitzRerankAdapter}): request with {@code top_n} = all, scores back in document order, the key per request,
 * the readiness probe on {@code /models}, and errors that name no document.
 */
public class OpenAiRerankOverApiTest {

    private static final String MODEL = "nvidia/llama-nemotron-rerank-1b-v2";

    private HttpServer server;
    private String base;
    private volatile String answer;
    private volatile int status = 200;
    private final List<String> requests = new CopyOnWriteArrayList<String>();
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            requests.add(exchange.getRequestURI().getPath() + " " + read(exchange.getRequestBody()));
            byte[] body = (exchange.getRequestURI().getPath().endsWith("/models")
                    ? "{\"data\":[{\"id\":\"" + MODEL + "\"}]}" : answer).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/models") ? 200 : status,
                    body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        EndpointAuthorization.install(url -> url.startsWith(base + "/") ? "sk-rerank".toCharArray() : null);
    }

    @After
    public void stopServer() {
        EndpointAuthorization.install(null);
        server.stop(0);
    }

    private HttpRerankerClient client() {
        return new HttpRerankerClient(new RerankerEndpointDescriptor(RerankerProvider.OPENAI_COMPATIBLE, base, MODEL,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 5000L,
                RerankerSelectionConfiguration.topN(10)));
    }

    @Test
    public void scoresComeBackInDocumentOrderWithTheKey() throws Exception {
        answer = "{\"id\":\"r1\",\"results\":[{\"index\":2,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":-1.5}]}";

        RerankResponse response = client().rerank("frage", Arrays.asList("a", "b", "c"));

        assertEquals(3, response.scores.size());
        assertEquals(0, response.scores.get(0).documentIndex);
        assertEquals(0.1, response.scores.get(0).score, 1e-9);
        assertEquals(0.9, response.scores.get(2).score, 1e-9);
        assertEquals("/v1/rerank {\"model\":\"" + MODEL + "\",\"query\":\"frage\",\"documents\":[\"a\",\"b\",\"c\"],"
                + "\"top_n\":3}", requests.get(0));
        assertEquals("Bearer sk-rerank", authorizations.get(0));

        client().probeReadiness();
        assertTrue(requests.get(1).startsWith("/v1/models"));
    }

    @Test
    public void incompleteOrBrokenAnswersAndErrorsFailWithoutNamingDocuments() {
        answer = "{\"results\":[{\"index\":0,\"score\":1.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE, "of 2");
        answer = "{\"results\":[{\"index\":0,\"score\":1.0},{\"index\":0,\"score\":2.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE, "duplicated");
        answer = "{\"error\":{\"message\":\"document geheimes-dokument too long\",\"type\":\"invalid_request\"}}";
        status = 400;
        String message = expectFailure(RerankerClientFailure.HTTP_STATUS, "HTTP 400 (invalid_request)");
        assertFalse(message.contains("geheimes-dokument"));
    }

    private String expectFailure(RerankerClientFailure kind, String part) {
        try {
            client().rerank("frage", Arrays.asList("geheimes-dokument", "b"));
            fail("expected " + kind);
            return "";
        } catch (RerankerClientException e) {
            assertEquals(e.getMessage(), kind, e.getFailure());
            assertTrue(e.getMessage(), e.getMessage().contains(part));
            return e.getMessage();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
