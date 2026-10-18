package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;

import static com.aresstack.enterpriseai.chat.openai.FakeOpenAiServer.data;
import static com.aresstack.enterpriseai.chat.openai.FakeOpenAiServer.deltaChunk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OpenAiCompatibleChatAdapterTest {

    private static final String TOKEN = "sk-test-geheim";

    private FakeOpenAiServer server;
    private OpenAiCompatibleChatAdapter adapter;

    @Before
    public void startServer() throws Exception {
        server = new FakeOpenAiServer();
        adapter = new OpenAiCompatibleChatAdapter(config(server.baseUrl()));
    }

    @After
    public void stopServer() {
        server.stop();
    }

    @Test
    public void nonStreamingRequestSendsUtf8JsonWithBearerAndParsesTheAnswer() {
        server.respond(200, "application/json", "{\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Grüß dich 😀\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}");

        ChatResponse response = adapter.complete(request("Grüße aus Köln 😀"));

        assertEquals("Grüß dich 😀", response.content());
        assertEquals(ChatFinishReason.STOP, response.finishReason());
        assertFalse("usage 0 means not reported", response.usage().isReported());

        FakeOpenAiServer.Recorded recorded = server.lastRequest();
        assertEquals("POST", recorded.method);
        assertEquals("/v1/chat/completions", recorded.uri.getPath());
        assertNull("stream is never sent as query parameter", recorded.uri.getQuery());
        assertEquals("Bearer " + TOKEN, recorded.header("Authorization"));
        assertEquals("application/json; charset=utf-8", recorded.header("Content-Type"));
        JsonObject body = JsonParser.parseString(recorded.bodyText()).getAsJsonObject();
        assertEquals("Grüße aus Köln 😀",
                body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
    }

    @Test
    public void streamingDeliversDeltasInOrderSkipsNullChunksAndEndsAtDone() throws Exception {
        server.respond(200, "text/event-stream",
                data("{\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}]}"),
                deltaChunk("\"Hal\"", "null"),
                deltaChunk("null", "null"),
                ": keep-alive\n\n",
                deltaChunk("\"lo Wält\"", "null"),
                deltaChunk("\"\"", "\"stop\""),
                data("{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}"),
                data("[DONE]"));

        RecordingStreamListener listener = new RecordingStreamListener();
        ChatTask task = adapter.stream(request("Hi"), listener);
        listener.awaitTerminal();

        assertEquals(Arrays.asList("start", "delta:Hal", "delta:lo Wält", "complete"), listener.events());
        assertEquals("Hallo Wält", listener.response.content());
        assertEquals(ChatFinishReason.STOP, listener.response.finishReason());
        assertEquals("gpt-intern", listener.response.model());
        assertFalse(listener.response.usage().isReported());
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());

        FakeOpenAiServer.Recorded recorded = server.lastRequest();
        assertTrue("streaming is requested in the JSON body",
                JsonParser.parseString(recorded.bodyText()).getAsJsonObject().get("stream").getAsBoolean());
        assertNull(recorded.uri.getQuery());
        String accept = recorded.header("Accept");
        assertTrue("server rejects Accept: text/event-stream, got " + accept,
                accept == null || !accept.contains("text/event-stream"));
        assertEquals("application/json; charset=utf-8", recorded.header("Content-Type"));
    }

    @Test
    public void textAfterDoneIsIgnored() throws Exception {
        server.respond(200, "text/event-stream", deltaChunk("\"a\"", "null"), data("[DONE]"),
                deltaChunk("\"zu spät\"", "null"));
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals("a", listener.response.content());
    }

    @Test
    public void streamWithFinishReasonButWithoutDoneCompletes() throws Exception {
        server.respond(200, "text/event-stream", deltaChunk("\"a\"", "null"), deltaChunk("null", "\"length\""));
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(ChatFinishReason.LENGTH, listener.response.finishReason());
        assertEquals("a", listener.response.content());
    }

    @Test
    public void interruptedStreamIsATransportError() throws Exception {
        server.respond(200, "text/event-stream", deltaChunk("\"a\"", "null"));
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(Arrays.asList("start", "delta:a", "error"), listener.events());
        assertEquals(ChatErrorKind.TRANSPORT, listener.error.kind());
    }

    @Test
    public void serverIgnoringStreamFlagStillWorks() throws Exception {
        server.respond(200, "application/json; charset=utf-8",
                "{\"choices\":[{\"message\":{\"content\":\"am Stück\"},\"finish_reason\":\"stop\"}]}");
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(Arrays.asList("start", "delta:am Stück", "complete"), listener.events());
    }

    @Test
    public void errorEventInsideStreamFails() throws Exception {
        server.respond(200, "text/event-stream", deltaChunk("\"a\"", "null"),
                data("{\"error\":{\"message\":\"overloaded\"}}"));
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(ChatErrorKind.PROVIDER_ERROR, listener.error.kind());
    }

    @Test
    public void developerRoleRejectedByServerSurfacesAsProviderError() throws Exception {
        // Beobachtetes Verhalten des Zielservers: "developer" -> HTTP 500.
        OpenAiCompatibleChatAdapter passThrough = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(server.baseUrl(), "gpt-intern").bearerToken(token())
                        .developerRolePolicy(DeveloperRolePolicy.SEND_AS_DEVELOPER).build());
        server.respond(500, "application/json", "{\"error\":{\"message\":\"Internal Server Error\"}}");

        RecordingStreamListener listener = new RecordingStreamListener();
        passThrough.stream(new ChatRequest(Arrays.asList(ChatMessage.developer("regel"), ChatMessage.user("x")),
                null), listener);
        listener.awaitTerminal();

        assertEquals(Collections.singletonList("error"), listener.events());
        assertEquals(ChatErrorKind.PROVIDER_ERROR, listener.error.kind());
        assertEquals(500, listener.error.statusCode());
        assertTrue(server.lastRequest().bodyText().contains("\"developer\""));
    }

    @Test
    public void developerRoleIsRejectedBeforeSendingByDefault() throws Exception {
        try {
            adapter.complete(new ChatRequest(Arrays.asList(ChatMessage.developer("regel"), ChatMessage.user("x")), null));
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.INVALID_REQUEST, e.kind());
        }
        RecordingStreamListener listener = new RecordingStreamListener();
        ChatTask task = adapter.stream(new ChatRequest(Arrays.asList(ChatMessage.developer("regel"),
                ChatMessage.user("x")), null), listener);
        listener.awaitTerminal();
        assertEquals(Collections.singletonList("error"), listener.events());
        assertEquals(ChatErrorKind.INVALID_REQUEST, listener.error.kind());
        assertTrue(task.isDone());
        assertEquals("nothing reaches the server", 0, server.requestCount());
    }

    @Test
    public void nullContentWithFinishReasonLengthIsAValidAnswer() throws Exception {
        // Beobachtet mit max_tokens = 20: finish_reason "length" und message.content null.
        server.respond(200, "application/json", "{\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":\"length\"}]}");
        ChatResponse response = adapter.complete(new ChatRequest(Collections.singletonList(ChatMessage.user("x")),
                ChatOptions.builder().maxTokens(20).build()));
        assertEquals("", response.content());
        assertEquals(ChatFinishReason.LENGTH, response.finishReason());
        assertEquals(20, JsonParser.parseString(server.lastRequest().bodyText()).getAsJsonObject()
                .get("max_tokens").getAsInt());
    }

    @Test
    public void baseUrlWithTrailingSlashResolvesTheSameEndpoint() {
        URI withSlash = URI.create(server.baseUrl() + "/");
        assertEquals(config(server.baseUrl()).endpoint(), config(withSlash).endpoint());
        assertEquals("/v1/chat/completions", config(withSlash).endpoint().getPath());
    }

    @Test
    public void httpStatusesMapToErrorKinds() {
        assertStatus(400, ChatErrorKind.INVALID_REQUEST);
        assertStatus(401, ChatErrorKind.AUTHENTICATION);
        assertStatus(403, ChatErrorKind.AUTHENTICATION);
        assertStatus(429, ChatErrorKind.RATE_LIMITED);
        assertStatus(503, ChatErrorKind.PROVIDER_ERROR);
    }

    @Test
    public void unauthorizedWithChallengeIsAnAuthenticationErrorWhenStreaming() throws Exception {
        server.respond(401, "application/json", "{\"error\":{\"message\":\"bad token\"}}")
                .header("WWW-Authenticate", "Bearer realm=\"api\"");
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(ChatErrorKind.AUTHENTICATION, listener.error.kind());
        assertEquals(401, listener.error.statusCode());
    }

    @Test
    public void tokenEchoedByServerNeverAppearsInErrors() {
        server.respond(401, "application/json", "{\"error\":{\"message\":\"invalid token " + TOKEN + "\"}}");
        try {
            adapter.complete(request("x"));
            fail();
        } catch (ChatCompletionException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(TOKEN));
            assertTrue(e.getMessage().contains("***"));
        }
    }

    @Test
    public void tokenEchoedInAnInBandErrorEventIsRedacted() throws Exception {
        server.respond(200, "text/event-stream", data("{\"error\":{\"message\":\"bad key " + TOKEN + "\"}}"));
        RecordingStreamListener listener = new RecordingStreamListener();
        adapter.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(ChatErrorKind.PROVIDER_ERROR, listener.error.kind());
        assertFalse(listener.error.getMessage(), listener.error.getMessage().contains(TOKEN));

        server.respond(200, "application/json", "{\"error\":{\"message\":\"bad key " + TOKEN + "\"}}");
        try {
            adapter.complete(request("x"));
            fail();
        } catch (ChatCompletionException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(TOKEN));
        }
    }

    @Test
    public void transientTokenFailureIsNotReportedAsAuthenticationError() throws Exception {
        OpenAiCompatibleChatAdapter vaultDown = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(server.baseUrl(), "m").bearerToken(
                        new OpenAiCompatibleChatConfig.TokenSource() {
                            @Override
                            public String token() {
                                throw new IllegalStateException("vault not reachable");
                            }

                            @Override
                            public boolean isTransient(RuntimeException failure) {
                                return true;
                            }
                        }).build());
        RecordingStreamListener listener = new RecordingStreamListener();
        vaultDown.stream(request("x"), listener);
        listener.awaitTerminal();

        assertEquals(ChatErrorKind.CREDENTIALS_UNAVAILABLE, listener.error.kind());
        assertEquals("token source temporarily unavailable: IllegalStateException", listener.error.getMessage());
        assertEquals(0, server.requestCount());
        try {
            vaultDown.complete(request("x"));
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.CREDENTIALS_UNAVAILABLE, e.kind());
        }
    }

    @Test
    public void failingTokenSourceEndsTheStreamWithAnAuthenticationError() throws Exception {
        OpenAiCompatibleChatAdapter broken = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(server.baseUrl(), "m").bearerToken(
                        new OpenAiCompatibleChatConfig.TokenSource() {
                            @Override
                            public String token() {
                                throw new IllegalStateException("store locked: " + TOKEN);
                            }
                        }).build());
        RecordingStreamListener listener = new RecordingStreamListener();
        ChatTask task = broken.stream(request("x"), listener);
        listener.awaitTerminal();

        assertEquals(Collections.singletonList("error"), listener.events());
        assertEquals(ChatErrorKind.AUTHENTICATION, listener.error.kind());
        assertFalse(listener.error.getMessage().contains(TOKEN));
        assertNull(listener.error.getCause());
        assertTrue(task.isDone());
        assertEquals(0, server.requestCount());
        try {
            broken.complete(request("x"));
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.AUTHENTICATION, e.kind());
        }
    }

    @Test
    public void cancelDuringStreamingEndsWithCancelledAndNothingElse() throws Exception {
        server.respond(200, "text/event-stream", deltaChunk("\"Teil\"", "null")).hangAfterParts();

        RecordingStreamListener listener = new RecordingStreamListener();
        ChatTask task = adapter.stream(request("x"), listener);
        listener.awaitFirstDelta();
        task.cancel();
        task.cancel();
        listener.awaitTerminal();
        Thread.sleep(100L);

        assertEquals(Arrays.asList("start", "delta:Teil", "cancelled"), listener.events());
        assertTrue(task.isDone());
        assertTrue(task.isCancelled());
    }

    @Test
    public void cancelBeforeTheRequestStartsSendsNothing() throws Exception {
        final Runnable[] pending = new Runnable[1];
        OpenAiCompatibleChatAdapter deferred = new OpenAiCompatibleChatAdapter(config(server.baseUrl()),
                new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        pending[0] = command;
                    }
                });
        RecordingStreamListener listener = new RecordingStreamListener();
        ChatTask task = deferred.stream(request("x"), listener);
        task.cancel();
        pending[0].run();

        assertEquals(Collections.singletonList("cancelled"), listener.events());
        assertEquals(0, server.requestCount());
    }

    @Test
    public void unreachableServerIsATransportError() throws Exception {
        int freePort;
        ServerSocket socket = new ServerSocket(0);
        try {
            freePort = socket.getLocalPort();
        } finally {
            socket.close();
        }
        OpenAiCompatibleChatAdapter unreachable = new OpenAiCompatibleChatAdapter(
                config(URI.create("http://127.0.0.1:" + freePort + "/v1")));

        RecordingStreamListener listener = new RecordingStreamListener();
        unreachable.stream(request("x"), listener);
        listener.awaitTerminal();
        assertEquals(Collections.singletonList("error"), listener.events());
        assertEquals(ChatErrorKind.TRANSPORT, listener.error.kind());
        try {
            unreachable.complete(request("x"));
            fail();
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.TRANSPORT, e.kind());
        }
    }

    @Test
    public void withoutTokenNoAuthorizationHeaderIsSent() {
        server.respond(200, "application/json", "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        new OpenAiCompatibleChatAdapter(OpenAiCompatibleChatConfig.builder(server.baseUrl(), "m").build())
                .complete(request("x"));
        assertNull(server.lastRequest().header("Authorization"));
    }

    @Test
    public void stopSequenceStaysInOutputAsTheServerReturnsIt() {
        // Beobachtet: der Server lässt die Stop-Sequenz im Output; der Adapter verändert den Text nicht.
        server.respond(200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"Antwort ENDE\"},\"finish_reason\":\"stop\"}]}");
        ChatResponse response = adapter.complete(new ChatRequest(Collections.singletonList(ChatMessage.user("x")),
                ChatOptions.builder().stop(Collections.singletonList("ENDE")).build()));
        assertEquals("Antwort ENDE", response.content());
        assertTrue(JsonParser.parseString(server.lastRequest().bodyText()).getAsJsonObject().get("stop").isJsonArray());
    }

    @Test
    public void configToStringHidesTheToken() {
        String text = config(server.baseUrl()).toString();
        assertFalse(text.contains(TOKEN));
        assertTrue(text.contains("***"));
    }

    @Test
    public void configRejectsCredentialsInTheUrl() {
        try {
            OpenAiCompatibleChatConfig.builder(URI.create("https://user:pw@host/v1"), "m");
            fail();
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("pw"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void configRejectsBaseUrlWithoutHost() {
        OpenAiCompatibleChatConfig.builder(URI.create("http:/v1"), "m");
    }

    @Test
    public void configRejectsBaseUrlsThatAlreadyNameAnEndpoint() {
        for (String bad : new String[] {"https://host/v1/chat/completions", "https://host/v1/Chat/Completions/",
                "https://host/v1/embeddings", "https://host/v1/models/"}) {
            try {
                OpenAiCompatibleChatConfig.builder(URI.create(bad), "m");
                fail("expected rejection of " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("endpoint path"));
            }
        }
        OpenAiCompatibleChatConfig ok = OpenAiCompatibleChatConfig.builder(URI.create("https://host/v1/"), "m").build();
        assertEquals("https://host/v1/chat/completions", ok.endpoint().toString());
        assertEquals("https://host/v1/models", ok.modelsEndpoint().toString());
        assertNull(OpenAiCompatibleChatConfig.endpointSuffix(URI.create("https://host/v1")));
        assertEquals("/models", OpenAiCompatibleChatConfig.endpointSuffix(URI.create("https://host/models")));
    }

    @Test
    public void anUnavailableRouteFailsBeforeAnyRequestAndNamesTheReason() {
        HttpRoutePort unavailable = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.unavailable("pac-download-failed", "PAC-Skript nicht ladbar (HTTP 404)");
            }
        };
        OpenAiCompatibleChatAdapter routed = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(server.baseUrl(), "gpt-intern").bearerToken(token())
                        .routes(unavailable).build());
        try {
            routed.complete(request("x"));
            fail("expected ChatCompletionException");
        } catch (ChatCompletionException e) {
            assertEquals(ChatErrorKind.TRANSPORT, e.kind());
            assertTrue(e.getCause() instanceof java.io.IOException);
            assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("pac-download-failed"));
            assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("HTTP 404"));
        }
        assertEquals("keine Verbindung ohne Route", 0, server.requestCount());
    }

    @Test
    public void aProxyRouteSendsTheRequestToTheProxyWithTheAbsoluteTargetUrl() {
        final URI proxy = server.baseUrl();
        HttpRoutePort viaProxy = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.proxy(proxy.getHost(), proxy.getPort(), "test");
            }
        };
        server.respond(200, "application/json", "{\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        OpenAiCompatibleChatAdapter routed = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(URI.create("http://ki.intern.invalid/v1"), "gpt-intern")
                        .bearerToken(token()).routes(viaProxy).userAgent("EnterpriseAiClient/test").build());

        assertEquals("ok", routed.complete(request("Hi")).content());

        FakeOpenAiServer.Recorded recorded = server.lastRequest();
        assertEquals("der Zielhost wird nie selbst aufgelöst; der Proxy bekommt die absolute URL",
                "ki.intern.invalid", recorded.uri.getHost());
        assertEquals("/v1/chat/completions", recorded.uri.getPath());
        assertEquals("EnterpriseAiClient/test", recorded.header("User-Agent"));
    }

    @Test
    public void aDirectRouteConnectsWithoutProxy() {
        server.respond(200, "application/json", "{\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"direkt\"},\"finish_reason\":\"stop\"}]}");
        OpenAiCompatibleChatAdapter routed = new OpenAiCompatibleChatAdapter(
                OpenAiCompatibleChatConfig.builder(server.baseUrl(), "gpt-intern").bearerToken(token())
                        .routes(HttpRoutePort.direct()).build());
        assertEquals("direkt", routed.complete(request("Hi")).content());
        assertEquals("/v1/chat/completions", server.lastRequest().uri.getPath());
        String userAgent = server.lastRequest().header("User-Agent");
        assertTrue("ohne Angabe bleibt der User-Agent der JVM: " + userAgent,
                userAgent == null || userAgent.startsWith("Java/"));
    }

    private void assertStatus(int status, ChatErrorKind expected) {
        server.respond(status, "application/json", "{\"error\":{\"message\":\"nope\"}}");
        try {
            adapter.complete(request("x"));
            fail("expected error for HTTP " + status);
        } catch (ChatCompletionException e) {
            assertEquals(expected, e.kind());
            assertEquals(status, e.statusCode());
            assertTrue(e.getMessage(), e.getMessage().contains("nope"));
        }
    }

    private static ChatRequest request(String userText) {
        return new ChatRequest(Arrays.asList(ChatMessage.system("Sei hilfreich."), ChatMessage.user(userText)),
                ChatOptions.builder().temperature(0.2).build());
    }

    private static OpenAiCompatibleChatConfig config(URI endpoint) {
        return OpenAiCompatibleChatConfig.builder(endpoint, "gpt-intern").bearerToken(token()).readTimeoutMillis(10000).build();
    }

    private static OpenAiCompatibleChatConfig.TokenSource token() {
        return OpenAiCompatibleChatConfig.TokenSource.fixed(TOKEN);
    }
}
