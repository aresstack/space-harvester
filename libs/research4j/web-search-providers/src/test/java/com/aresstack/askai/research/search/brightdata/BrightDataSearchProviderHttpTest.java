package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.application.SearchProviderFactory;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.http.HttpResponseException;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Bright Data (synchronous SERP) HTTP contract: the {@code Authorization: Bearer} header carries the
 * decrypted key, an organic JSON body maps to hits, and transport errors surface as module exceptions with
 * no secret leak.
 */
public final class BrightDataSearchProviderHttpTest {

    private static final String API_KEY = "brightdata-secret-token";

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<String>();
    private final AtomicReference<String> lastMethod = new AtomicReference<String>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<String>(
            "{\"organic\":[{\"title\":\"PF4J\",\"link\":\"https://pf4j.org/\",\"description\":\"Plugins.\"}]}");

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastMethod.set(exchange.getRequestMethod());
                drain(exchange);
                byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), out.length);
                OutputStream os = exchange.getResponseBody();
                os.write(out);
                os.close();
            }
        });
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private WebSearchProvider provider() throws Exception {
        SecretValueService secrets = new SecretValueService(new AesGcmSecretCipher(
                new FileSecretKeyProvider(Files.createTempDirectory("bd-http").resolve("key"))));
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BrightDataSearchConfiguration config = new BrightDataSearchConfiguration();
        config.setEnabled(true);
        config.setZone("test-zone");
        config.setExecutionMode(BrightDataExecutionMode.SYNCHRONOUS);
        config.setSynchronousEndpoint(base + "/request");
        config.setApiKey(secrets.encrypt(API_KEY.toCharArray()));
        return new SearchProviderFactory(new Gson(), new AsyncHttpClientFactory(), secrets)
                .createLegacyBrightData(config);
    }

    private static WebSearchRequest request() {
        return WebSearchRequest.builder("wearable research").countryCode("DE").languageCode("de")
                .maximumResults(5).build();
    }

    @Test
    public void sendsBearerTokenAndMapsResults() throws Exception {
        WebSearchProvider provider = provider();
        try {
            WebSearchResult result = provider.search(request()).get(5, TimeUnit.SECONDS);
            assertEquals("POST", lastMethod.get());
            assertEquals("Bearer " + API_KEY, lastAuth.get());
            assertEquals(1, result.getHits().size());
            assertEquals("https://pf4j.org/", result.getHits().get(0).getUrl());
        } finally {
            provider.close();
        }
    }

    @Test
    public void httpErrorsSurfaceAsHttpResponseExceptions() throws Exception {
        for (int code : new int[]{401, 403, 429, 503}) {
            status.set(code);
            WebSearchProvider provider = provider();
            try {
                provider.search(request()).get(5, TimeUnit.SECONDS);
                fail("HTTP " + code + " must fail");
            } catch (ExecutionException expected) {
                assertEquals(code, ((HttpResponseException) expected.getCause()).getStatusCode());
                assertFalse(String.valueOf(expected.getCause()).contains(API_KEY));
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void noSecretLeaksIntoToString() throws Exception {
        WebSearchProvider provider = provider();
        try {
            assertFalse(provider.toString().contains(API_KEY));
        } finally {
            provider.close();
        }
    }

    private static void drain(HttpExchange exchange) throws java.io.IOException {
        byte[] buffer = new byte[4096];
        while (exchange.getRequestBody().read(buffer) != -1) {
            // consume the request body so the client sees a clean exchange
        }
    }
}
