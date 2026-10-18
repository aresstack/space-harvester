package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.model.api.RerankException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@code POST <baseUrl>/rerank}: every document scored, results mapped back to document order, token only as header. */
public class KipitzRerankAdapterTest {

    private static final String TOKEN = "geheimer-api-key";
    private HttpServer server;
    private final List<JsonObject> bodies = new CopyOnWriteArrayList<JsonObject>();
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();
    private volatile int status = 200;
    private volatile String answer = "{\"model\":\"bge\",\"results\":[{\"index\":1,\"relevance_score\":0.9},"
            + "{\"index\":0,\"relevance_score\":-1.5},{\"index\":2,\"relevance_score\":0.2}]}";

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            bodies.add(JsonParser.parseString(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void scoresComeBackInDocumentOrder() throws Exception {
        KipitzRerankAdapter adapter = adapter();

        double[] scores = adapter.rerank("bge", "Was ist Quark?", Arrays.asList("Käse", "Quark", "Milch"));

        assertArrayEquals(new double[]{-1.5, 0.9, 0.2}, scores, 0.0);
        JsonObject body = bodies.get(0);
        assertEquals("bge", body.get("model").getAsString());
        assertEquals("Was ist Quark?", body.get("query").getAsString());
        assertEquals(3, body.getAsJsonArray("documents").size());
        assertEquals("never pre-truncated by the endpoint", 3, body.get("top_n").getAsInt());
        assertEquals("Bearer " + TOKEN, authorizations.get(0));
    }

    @Test
    public void incompleteOrFailedAnswersAreErrorsWithoutSecrets() throws Exception {
        answer = "{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}";
        expectFailure(adapter());
        status = 503;
        answer = "{\"error\":{\"message\":\"model loading\"}}";
        String message = expectFailure(adapter());
        assertTrue(message, message.contains("503"));
        assertFalse(message.contains(TOKEN));
        assertFalse(message.contains("Quark"));
    }

    @Test
    public void scoreAliasAndDataArrayAreRead() throws Exception {
        assertArrayEquals(new double[]{0.5, 0.1}, KipitzRerankAdapter.parse(
                "{\"data\":[{\"index\":1,\"score\":0.1},{\"index\":0,\"score\":0.5}]}", 2), 0.0);
    }

    private String expectFailure(KipitzRerankAdapter adapter) {
        try {
            adapter.rerank("bge", "Was ist Quark?", Arrays.asList("Käse", "Quark"));
            fail("expected a RerankException");
            return "";
        } catch (RerankException expected) {
            return expected.getMessage();
        }
    }

    private KipitzRerankAdapter adapter() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        return new KipitzRerankAdapter(KipitzModelCatalogConfig.builder(base).bearerToken(() -> TOKEN).build());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
