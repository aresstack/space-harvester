package com.aresstack.askai.research.search.dataforseo;

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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DataForSEO HTTP contract: HTTP Basic auth from the decrypted API key, a well-formed live task
 * body (with {@code depth} not capped below the caller's request), organic items mapped to hits, and
 * transport errors surfaced without leaking the password.
 */
public final class DataForSeoSearchProviderHttpTest {

    private static final String USERNAME = "dfs-login";
    private static final String PASSWORD = "dfs-secret-pass";
    /** DataForSEO API key = Base64(login:password), stored as one secret. */
    private static final String API_KEY = java.util.Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<String>();
    private final AtomicReference<String> lastBody = new AtomicReference<String>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<String>(
            "{\"status_code\":20000,\"tasks\":[{\"status_code\":20000,\"result\":[{\"items\":["
                    + "{\"type\":\"organic\",\"rank_group\":1,\"title\":\"Example\","
                    + "\"url\":\"https://example.org\",\"description\":\"Snippet\"}]}]}]}");

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastBody.set(readBody(exchange));
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
                new FileSecretKeyProvider(Files.createTempDirectory("dfs-http").resolve("key"))));
        DataForSeoSearchConfiguration config = new DataForSeoSearchConfiguration();
        config.setEnabled(true);
        config.setEndpointBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey(secrets.encrypt(API_KEY.toCharArray()));
        return new SearchProviderFactory(new Gson(), new AsyncHttpClientFactory(), secrets)
                .createLegacyDataForSeo(config);
    }

    private static WebSearchRequest request() {
        return WebSearchRequest.builder("wearable research").languageCode("de").maximumResults(5).build();
    }

    @Test
    public void sendsBasicAuthAndAWellFormedTaskAndMapsResults() throws Exception {
        WebSearchProvider provider = provider();
        try {
            WebSearchResult result = provider.search(request()).get(5, TimeUnit.SECONDS);
            assertTrue("Basic auth header", lastAuth.get().startsWith("Basic "));
            String decoded = new String(Base64.getDecoder().decode(
                    lastAuth.get().substring("Basic ".length())), StandardCharsets.UTF_8);
            assertEquals(USERNAME + ":" + PASSWORD, decoded);
            assertTrue("task carries the keyword", lastBody.get().contains("\"keyword\":\"wearable research\""));
            assertTrue("task carries a depth", lastBody.get().contains("\"depth\":"));
            assertFalse("depth is not capped to zero", lastBody.get().contains("\"depth\":0"));
            assertEquals(1, result.getHits().size());
            assertEquals("https://example.org", result.getHits().get(0).getUrl());
        } finally {
            provider.close();
        }
    }

    @Test
    public void httpErrorsSurfaceAsHttpResponseExceptions() throws Exception {
        for (int code : new int[]{401, 429, 503}) {
            status.set(code);
            WebSearchProvider provider = provider();
            try {
                provider.search(request()).get(5, TimeUnit.SECONDS);
                fail("HTTP " + code + " must fail");
            } catch (ExecutionException expected) {
                assertEquals(code, ((HttpResponseException) expected.getCause()).getStatusCode());
                assertFalse("no password in the exception", String.valueOf(expected.getCause()).contains(PASSWORD));
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void noPasswordLeaksIntoToString() throws Exception {
        WebSearchProvider provider = provider();
        try {
            assertFalse(provider.toString().contains(PASSWORD));
            assertFalse(provider.toString().contains(API_KEY));
        } finally {
            provider.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
