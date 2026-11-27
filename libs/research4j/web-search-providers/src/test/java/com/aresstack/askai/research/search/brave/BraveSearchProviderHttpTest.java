package com.aresstack.askai.research.search.brave;

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
 * Brave HTTP contract against a local mock server: the {@code X-Subscription-Token} header carries the
 * decrypted key, the query is well-formed, a JSON body maps to hits, transport errors surface as module
 * exceptions, and no secret leaks into {@code toString()} or exceptions.
 */
public final class BraveSearchProviderHttpTest {

    private static final String API_KEY = "brave-secret-key-XYZ";

    private HttpServer server;
    private final AtomicReference<String> lastToken = new AtomicReference<String>();
    private final AtomicReference<String> lastQuery = new AtomicReference<String>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<String>(
            "{\"web\":{\"results\":[{\"title\":\"PF4J\",\"url\":\"https://pf4j.org/\","
                    + "\"description\":\"A plugin framework.\"}]}}");

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                lastToken.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
                lastQuery.set(exchange.getRequestURI().getRawQuery());
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
                new FileSecretKeyProvider(Files.createTempDirectory("brave-http").resolve("key"))));
        BraveSearchConfiguration config = new BraveSearchConfiguration();
        config.getResultFilter().add(BraveResultType.WEB);
        config.setEnabled(true);
        config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search");
        config.setApiKey(secrets.encrypt(API_KEY.toCharArray()));
        return new SearchProviderFactory(new Gson(), new AsyncHttpClientFactory(), secrets)
                .createLegacyBrave(config);
    }

    private static WebSearchRequest request() {
        return WebSearchRequest.builder("wearable research").countryCode("DE").languageCode("de")
                .maximumResults(5).build();
    }

    @Test
    public void sendsTheSubscriptionTokenAndMapsResults() throws Exception {
        WebSearchProvider provider = provider();
        try {
            WebSearchResult result = provider.search(request()).get(5, TimeUnit.SECONDS);
            assertEquals("the decrypted key is sent as X-Subscription-Token", API_KEY, lastToken.get());
            assertTrue("the query carries the search term", lastQuery.get().contains("q="));
            assertTrue("the query carries the count", lastQuery.get().contains("count="));
            assertEquals(1, result.getHits().size());
            assertEquals("PF4J", result.getHits().get(0).getTitle());
            assertEquals("https://pf4j.org/", result.getHits().get(0).getUrl());
        } finally {
            provider.close();
        }
    }

    @Test
    public void httpErrorsSurfaceAsHttpResponseExceptions() throws Exception {
        assertStatusMapsToHttpException(401);
        assertStatusMapsToHttpException(403);
        assertStatusMapsToHttpException(429);
        assertStatusMapsToHttpException(503);
    }

    @Test
    public void malformedJsonFailsTheFuture() throws Exception {
        status.set(200);
        body.set("this is not json");
        WebSearchProvider provider = provider();
        try {
            provider.search(request()).get(5, TimeUnit.SECONDS);
            fail("a malformed body must fail the search");
        } catch (ExecutionException expected) {
            assertFalse(String.valueOf(expected.getCause()).contains(API_KEY));
        } finally {
            provider.close();
        }
    }

    @Test
    public void noSecretLeaksIntoToStringOrExceptions() throws Exception {
        status.set(401);
        WebSearchProvider provider = provider();
        try {
            assertFalse(provider.toString().contains(API_KEY));
            provider.search(request()).get(5, TimeUnit.SECONDS);
            fail("401 must fail");
        } catch (ExecutionException expected) {
            HttpResponseException http = (HttpResponseException) expected.getCause();
            assertFalse("no secret in the exception message", http.getMessage().contains(API_KEY));
        } finally {
            provider.close();
        }
    }

    private void assertStatusMapsToHttpException(int code) throws Exception {
        status.set(code);
        WebSearchProvider provider = provider();
        try {
            provider.search(request()).get(5, TimeUnit.SECONDS);
            fail("HTTP " + code + " must fail the search");
        } catch (ExecutionException expected) {
            HttpResponseException http = (HttpResponseException) expected.getCause();
            assertEquals(code, http.getStatusCode());
        } finally {
            provider.close();
        }
    }
}
