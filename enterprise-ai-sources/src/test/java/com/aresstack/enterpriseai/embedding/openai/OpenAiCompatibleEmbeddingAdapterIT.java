package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import com.aresstack.enterpriseai.embedding.api.EmbeddingPort;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.embedding.api.testing.EmbeddingPortContractTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Echte HTTP-Grenze gegen einen lokalen Fake-Server ({@code com.sun.net.httpserver}). Erbt den Port-Vertrag.
 */
public class OpenAiCompatibleEmbeddingAdapterIT extends EmbeddingPortContractTest {

    private static final int DIMENSION = 8;
    private static final String TOKEN = "s3cr3t-token";

    private FakeEmbeddingServer server;

    @Before
    public void startServer() throws IOException {
        server = new FakeEmbeddingServer(DIMENSION);
    }

    @After
    public void stopServer() {
        server.close();
    }

    @Override
    protected EmbeddingPort newPort() {
        return adapter(EmbeddingInputMode.SINGLE_STRING);
    }

    private OpenAiCompatibleEmbeddingAdapter adapter(EmbeddingInputMode mode) {
        OpenAiCompatibleEmbeddingConfiguration config = OpenAiCompatibleEmbeddingConfiguration
                .builder(server.baseUrl(), "danielheinz/e5-base-sts-en-de", DIMENSION)
                .inputMode(mode).maxBatchSize(2).readTimeoutMillis(5000).build();
        return new OpenAiCompatibleEmbeddingAdapter(config, new BearerTokenSource() {
            @Override
            public char[] bearerToken() {
                return TOKEN.toCharArray();
            }
        });
    }

    private OpenAiCompatibleEmbeddingAdapter adapter(String baseUrl, HttpRoutePort routes, String userAgent) {
        OpenAiCompatibleEmbeddingConfiguration config = OpenAiCompatibleEmbeddingConfiguration
                .builder(baseUrl, "danielheinz/e5-base-sts-en-de", DIMENSION)
                .routes(routes).userAgent(userAgent).readTimeoutMillis(5000).build();
        return new OpenAiCompatibleEmbeddingAdapter(config, new BearerTokenSource() {
            @Override
            public char[] bearerToken() {
                return TOKEN.toCharArray();
            }
        });
    }

    @Test
    public void anUnavailableRouteFailsAsUnavailableBeforeAnyRequest() {
        HttpRoutePort unavailable = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(java.net.URI target) {
                return HttpRoute.unavailable("windows-native-proxy-settings-not-implemented", "Modus nicht umgesetzt");
            }
        };
        try {
            adapter(server.baseUrl(), unavailable, null).embed(Collections.singletonList("x"));
            fail("expected EmbeddingException");
        } catch (EmbeddingException e) {
            assertEquals(EmbeddingFailureKind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("windows-native-proxy-settings-not-implemented"));
        }
        assertTrue("keine Anfrage ohne Route", server.requests().isEmpty());
    }

    @Test
    public void aProxyRouteSendsTheAbsoluteTargetUrlToTheProxyAndTheUserAgent() {
        final java.net.URI proxy = java.net.URI.create(server.baseUrl());
        HttpRoutePort viaProxy = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(java.net.URI target) {
                return HttpRoute.proxy(proxy.getHost(), proxy.getPort(), "test");
            }
        };
        EmbeddingBatch batch = adapter("http://ki.intern.invalid/v1", viaProxy, "EnterpriseAiClient/test")
                .embed(Collections.singletonList("eins"));
        assertEquals(1, batch.size());
        FakeEmbeddingServer.Recorded request = server.requests().get(0);
        assertEquals("http://ki.intern.invalid/v1/embeddings", request.path);
        assertEquals("EnterpriseAiClient/test", request.userAgent);
    }

    @Test
    public void aDirectRouteConnectsWithoutProxy() {
        EmbeddingBatch batch = adapter(server.baseUrl(), HttpRoutePort.direct(), null)
                .embed(Collections.singletonList("eins"));
        assertEquals(1, batch.size());
        assertEquals("/v1/embeddings", server.requests().get(0).path);
    }

    @Test
    public void defaultModeSendsOneStringRequestPerTextInOrder() {
        List<String> texts = Arrays.asList("eins", "zwei", "drei");
        EmbeddingBatch batch = adapter(EmbeddingInputMode.SINGLE_STRING).embed(texts);

        List<FakeEmbeddingServer.Recorded> requests = server.requests();
        assertEquals(3, requests.size());
        for (int i = 0; i < texts.size(); i++) {
            assertTrue(requests.get(i).json().get("input").isJsonPrimitive());
            assertEquals(texts.get(i), requests.get(i).json().get("input").getAsString());
            assertArrayEquals(server.vectorFor(texts.get(i)), batch.get(i).values(), 0f);
        }
    }

    @Test
    public void sendsHeadersAsVerifiedForTheChatEndpoint() {
        adapter(EmbeddingInputMode.SINGLE_STRING).embed(Collections.singletonList("x"));
        FakeEmbeddingServer.Recorded request = server.requests().get(0);
        assertEquals("POST", request.method);
        assertEquals("/v1/embeddings", request.path);
        assertEquals("application/json; charset=utf-8", request.contentType);
        assertEquals("Bearer " + TOKEN, request.authorization);
        // kein text/event-stream (am Chat-Endpunkt real abgelehnt); JDK-Default ist erlaubt
        assertTrue(request.accept == null || !request.accept.contains("event-stream"));
        assertEquals("danielheinz/e5-base-sts-en-de", request.json().get("model").getAsString());
    }

    @Test
    public void sendsUtf8Bytes() {
        String text = "Erkläre REST – Grüße ß €";
        adapter(EmbeddingInputMode.SINGLE_STRING).embed(Collections.singletonList(text));
        String raw = new String(server.requests().get(0).body, FakeEmbeddingServer.UTF8);
        assertTrue(raw.contains(text));
    }

    @Test
    public void noAuthorizationHeaderWithoutToken() {
        OpenAiCompatibleEmbeddingConfiguration config = OpenAiCompatibleEmbeddingConfiguration
                .builder(server.baseUrl(), "m", DIMENSION).build();
        new OpenAiCompatibleEmbeddingAdapter(config, new BearerTokenSource() {
            @Override
            public char[] bearerToken() {
                return null;
            }
        }).embed(Collections.singletonList("x"));
        assertNull(server.requests().get(0).authorization);
    }

    @Test
    public void defaultModeWorksAgainstAStringOnlyBackend() {
        server.stringInputOnly();
        EmbeddingBatch batch = adapter(EmbeddingInputMode.SINGLE_STRING).embed(Arrays.asList("a", "b"));
        assertEquals(2, batch.size());
    }

    @Test
    public void unverifiedArrayModeChunksAndKeepsOrder() {
        server.shuffleIndexedEntries();
        List<String> texts = Arrays.asList("a", "b", "c", "d", "e");
        EmbeddingBatch batch = adapter(EmbeddingInputMode.ARRAY_UNVERIFIED).embed(texts);

        List<FakeEmbeddingServer.Recorded> requests = server.requests();
        assertEquals(3, requests.size());
        assertEquals("[\"a\",\"b\"]", requests.get(0).json().get("input").toString());
        assertEquals("[\"c\",\"d\"]", requests.get(1).json().get("input").toString());
        assertEquals("[\"e\"]", requests.get(2).json().get("input").toString());
        for (int i = 0; i < texts.size(); i++) {
            assertArrayEquals(server.vectorFor(texts.get(i)), batch.get(i).values(), 0f);
        }
    }

    @Test
    public void unverifiedArrayModeAgainstStringOnlyBackendFailsLoudly() {
        server.stringInputOnly();
        try {
            adapter(EmbeddingInputMode.ARRAY_UNVERIFIED).embed(Arrays.asList("a", "b"));
            fail("expected REJECTED");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.REJECTED, expected.kind());
            assertTrue(expected.getMessage().contains("HTTP 422"));
        }
    }

    @Test
    public void httpErrorsBecomePortNeutralFailuresWithoutSecrets() {
        assertFailure(401, "{\"error\":{\"message\":\"invalid api key\"}}", EmbeddingFailureKind.AUTHENTICATION);
        // 429 einmal: der Adapter wiederholt und bekommt die Vektoren (dauerhaftes 429 im Unit-Test)
        server.failNext(429, "");
        assertEquals(1, adapter(EmbeddingInputMode.SINGLE_STRING).embed(Collections.singletonList("x")).size());
        assertFailure(500, "{\"error\":\"internal_error\"}", EmbeddingFailureKind.PROVIDER_ERROR);
        assertFailure(400, "{\"detail\":\"bad\"}", EmbeddingFailureKind.REJECTED);
    }

    /**
     * Konfiguriert ist eine andere Dimension, als der Dienst liefert (Feldbefund: 768 konfiguriert, 2000 geliefert):
     * der Adapter übernimmt die gelieferte, seine Welt wechselt; uneinheitliche Längen bleiben ungültig.
     */
    @Test
    public void deviatingDimensionOfTheServiceIsAdoptedButMixedLengthsAreRejected() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter(EmbeddingInputMode.SINGLE_STRING);
        com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity configured = adapter.modelIdentity();
        server.respondWith("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}");
        com.aresstack.enterpriseai.embedding.api.EmbeddingBatch batch =
                adapter.embed(Collections.singletonList("x"));
        assertEquals(3, batch.get(0).dimension());
        assertEquals(3, adapter.modelIdentity().dimension());
        assertEquals(adapter.modelIdentity(), batch.identity());
        assertFalse("neue Welt, neuer Namensraum",
                configured.fingerprint().equals(adapter.modelIdentity().fingerprint()));
        assertEquals(configured.attributes(), adapter.modelIdentity().attributes());

        server.respondWith("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]},{\"index\":1,\"embedding\":[1,2]}]}");
        assertInvalid(java.util.Arrays.asList("x", "y"), EmbeddingInputMode.ARRAY_UNVERIFIED);
    }

    @Test
    public void nonFiniteValuesAreRejected() {
        server.respondWith("{\"data\":[{\"index\":0,\"embedding\":[1e39,0,0,0,0,0,0,0]}]}");
        assertInvalid(Collections.singletonList("x"));
        server.respondWith("{\"data\":[{\"index\":0,\"embedding\":[NaN,0,0,0,0,0,0,0]}]}");
        assertInvalid(Collections.singletonList("x"));
    }

    @Test
    public void oversizedSuccessResponseIsRejected() {
        StringBuilder huge = new StringBuilder("{\"data\":[{\"embedding\":[");
        for (int i = 0; i < 200000; i++) {
            huge.append("0.0000000001,");
        }
        huge.append("0]}]}");
        server.respondWith(huge.toString());
        try {
            adapter(EmbeddingInputMode.SINGLE_STRING).embed(Collections.singletonList("x"));
            fail("expected INVALID_RESPONSE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
            assertTrue(expected.getMessage().contains("implausibly large"));
        }
    }

    @Test
    public void wrongCountIsRejected() {
        server.respondWith("{\"data\":[]}");
        assertInvalid(Collections.singletonList("x"));
    }

    @Test
    public void failureInTheMiddleOfABatchYieldsNoPartialResult() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter(EmbeddingInputMode.SINGLE_STRING);
        adapter.embed(Collections.singletonList("warm-up"));
        // Eine abweichende Länge übernimmt der Adapter inzwischen (Modellerkennung); ungültig bleibt ein Wert,
        // der nicht endlich ist.
        server.respondWith("{\"data\":[{\"index\":0,\"embedding\":[1e39,0,0,0,0,0,0,0]}]}");
        try {
            adapter.embed(Arrays.asList("a", "b"));
            fail("expected INVALID_RESPONSE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
        }
    }

    @Test
    public void unreachableServerIsUnavailable() {
        String baseUrl = server.baseUrl();
        server.close();
        OpenAiCompatibleEmbeddingConfiguration config = OpenAiCompatibleEmbeddingConfiguration
                .builder(baseUrl, "m", DIMENSION).connectTimeoutMillis(2000).readTimeoutMillis(2000).build();
        try {
            new OpenAiCompatibleEmbeddingAdapter(config, new BearerTokenSource() {
                @Override
                public char[] bearerToken() {
                    return TOKEN.toCharArray();
                }
            }).embed(Collections.singletonList("x"));
            fail("expected UNAVAILABLE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.UNAVAILABLE, expected.kind());
            assertTrue(expected.isRetryable());
            assertFalse(expected.getMessage().contains(TOKEN));
        }
    }

    @Test
    public void vectorsCarryTheConfiguredIdentity() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter(EmbeddingInputMode.SINGLE_STRING);
        EmbeddingVector vector = adapter.embed(Collections.singletonList("x")).get(0);
        assertEquals(adapter.modelIdentity(), vector.identity());
        assertFalse(adapter.toString().contains(TOKEN));
    }

    private void assertFailure(int status, String body, EmbeddingFailureKind kind) {
        server.failNext(status, body);
        try {
            adapter(EmbeddingInputMode.SINGLE_STRING).embed(Collections.singletonList("vertraulicher Text"));
            fail("expected " + kind);
        } catch (EmbeddingException expected) {
            assertEquals(kind, expected.kind());
            assertTrue(expected.getMessage().contains("HTTP " + status));
            assertFalse(expected.getMessage().contains(TOKEN));
            assertFalse(expected.getMessage().contains("vertraulicher Text"));
        }
    }

    private void assertInvalid(List<String> texts) {
        assertInvalid(texts, EmbeddingInputMode.SINGLE_STRING);
    }

    private void assertInvalid(List<String> texts, EmbeddingInputMode mode) {
        try {
            adapter(mode).embed(texts);
            fail("expected INVALID_RESPONSE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
        }
    }
}
