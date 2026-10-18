package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Adapterlogik ohne Netz, über die paketinterne Transport-Naht. */
public class OpenAiCompatibleEmbeddingAdapterTest {

    private static final OpenAiCompatibleEmbeddingConfiguration CONFIG =
            OpenAiCompatibleEmbeddingConfiguration.builder("https://ai.intern/v1", "m", 2).build();

    @Test
    public void emptyInputDoesNotCallTheServer() {
        RecordingTransport transport = new RecordingTransport("{\"data\":[]}");
        new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), transport).embed(Collections.<String>emptyList());
        assertTrue(transport.bodies.isEmpty());
    }

    @Test
    public void measureDimensionReturnsTheVectorLengthWithoutCheckingTheConfiguredDimension() {
        RecordingTransport transport = new RecordingTransport("{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4,0.5]}]}");
        int dimension = new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), transport).measureDimension("probe");
        assertEquals(5, dimension);
        assertEquals(1, transport.bodies.size());
    }

    @Test
    public void tokenIsReadPerRequestAndWipedAfterwards() {
        final List<char[]> handedOut = new ArrayList<char[]>();
        BearerTokenSource source = new BearerTokenSource() {
            @Override
            public char[] bearerToken() {
                char[] token = "tok".toCharArray();
                handedOut.add(token);
                return token;
            }
        };
        RecordingTransport transport = new RecordingTransport("{\"data\":[{\"embedding\":[1,0]}]}");
        new OpenAiCompatibleEmbeddingAdapter(CONFIG, source, transport).embed(Arrays.asList("a", "b"));

        assertEquals(2, handedOut.size());
        assertEquals(Arrays.asList("tok", "tok"), transport.tokens);
        for (char[] token : handedOut) {
            assertArrayEquals(new char[] {0, 0, 0}, token);
        }
    }

    @Test
    public void overloadIsRetriedWithBackoffBeforeItFails() {
        final List<Integer> statuses = new ArrayList<Integer>(Arrays.asList(429, 503, 200));
        final List<Long> waits = new ArrayList<Long>();
        EmbeddingHttpTransport busy = new EmbeddingHttpTransport() {
            @Override
            public HttpResult post(URI endpoint, String jsonBody, char[] bearerToken, long max) {
                int status = statuses.remove(0);
                return new HttpResult(status, status == 200 ? "{\"data\":[{\"embedding\":[1,0]}]}" : "");
            }
        };
        new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), busy, waits::add).embed(Collections.singletonList("a"));
        assertEquals(Arrays.asList(500L, 1500L), waits);

        statuses.addAll(Arrays.asList(429, 429, 429, 200));
        try {
            new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), busy, millis -> { })
                    .embed(Collections.singletonList("a"));
            fail("expected RATE_LIMITED after three attempts");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.RATE_LIMITED, expected.kind());
            assertEquals("kein vierter Versuch", Collections.singletonList(200), statuses);
            // Die Meldung landet im Protokoll: Pfad des Endpunkts, kein Host.
            assertEquals("embedding endpoint /v1/embeddings returned HTTP 429", expected.getMessage());
        }
    }

    @Test
    public void retryAfterOfTheServiceIsHonouredButCapped() {
        final List<HttpResult> answers = new ArrayList<HttpResult>(Arrays.asList(
                new HttpResult(429, "", 2000L), new HttpResult(429, "", 60000L),
                new HttpResult(200, "{\"data\":[{\"embedding\":[1,0]}]}")));
        final List<Long> waits = new ArrayList<Long>();
        EmbeddingHttpTransport gateway = (endpoint, body, token, max) -> answers.remove(0);
        new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), gateway, waits::add)
                .embedQueries(Collections.singletonList("frage"));
        assertEquals(Arrays.asList(2000L, OpenAiCompatibleEmbeddingAdapter.MAX_RETRY_AFTER_MILLIS), waits);
        assertEquals(2000L, HttpResult.retryAfterMillis(" 2 "));
        assertEquals(-1L, HttpResult.retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(-1L, HttpResult.retryAfterMillis(null));
    }

    /**
     * Ein Gateway mit einer Anfrage je Schlüssel sieht vom Adapter nie zwei gleichzeitig, und die Frage überholt die
     * wartende Aufbereitung: Während ein Passage-Request läuft, warten ein weiterer Passage-Request und die Frage; als
     * Nächstes geht die Frage hinaus.
     */
    @Test
    public void onlyOneRequestAtATimeAndTheQueryGoesFirst() throws Exception {
        final java.util.concurrent.CountDownLatch firstStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
        EmbeddingHttpTransport gateway = (endpoint, body, token, max) -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                order.add(body.contains("passage-1") ? "passage-1" : body.contains("passage-2") ? "passage-2"
                        : "frage");
                if (body.contains("passage-1")) {
                    firstStarted.countDown();
                    releaseFirst.await();
                }
                return new HttpResult(200, "{\"data\":[{\"embedding\":[1,0]}]}");
            } catch (InterruptedException e) {
                throw new IOException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        };
        final OpenAiCompatibleEmbeddingAdapter adapter = new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(),
                gateway, millis -> { });
        Thread first = new Thread(() -> adapter.embed(Collections.singletonList("passage-1")));
        first.start();
        assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread second = new Thread(() -> adapter.embed(Collections.singletonList("passage-2")));
        second.start();
        Thread question = new Thread(() -> adapter.embedQueries(Collections.singletonList("frage")));
        question.start();
        waitUntilWaiting(second);
        waitUntilWaiting(question);
        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);
        question.join(5000);

        assertEquals(Arrays.asList("passage-1", "frage", "passage-2"), order);
        assertEquals(1, maxInFlight.get());
    }

    private static void waitUntilWaiting(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    @Test
    public void transportExceptionsBecomeUnavailable() {
        EmbeddingHttpTransport failing = new EmbeddingHttpTransport() {
            @Override
            public HttpResult post(URI endpoint, String jsonBody, char[] bearerToken, long max) throws IOException {
                throw new SocketTimeoutException("Read timed out");
            }
        };
        try {
            new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), failing).embed(Collections.singletonList("a"));
            fail("expected UNAVAILABLE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.UNAVAILABLE, expected.kind());
            assertTrue(expected.getMessage().contains("SocketTimeoutException"));
            assertFalse(expected.getMessage(), expected.getMessage().contains("ai.intern"));
        }
    }

    @Test
    public void malformedTokenIsRejectedWithoutEchoAndWiped() {
        final char[] token = "abc\r\nX-Injected: 1".toCharArray();
        RecordingTransport transport = new RecordingTransport("{\"data\":[{\"embedding\":[1,0]}]}");
        try {
            new OpenAiCompatibleEmbeddingAdapter(CONFIG, new BearerTokenSource() {
                @Override
                public char[] bearerToken() {
                    return token;
                }
            }, transport).embed(Collections.singletonList("a"));
            fail("expected AUTHENTICATION");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.AUTHENTICATION, expected.kind());
            assertTrue(!expected.getMessage().contains("abc") && !expected.getMessage().contains("Injected"));
        }
        assertTrue(transport.bodies.isEmpty());
        assertArrayEquals(new char[token.length], token);
    }

    @Test
    public void responseLimitScalesWithBatchAndTooLargeIsInvalid() {
        final List<Long> limits = new ArrayList<Long>();
        EmbeddingHttpTransport tooLarge = new EmbeddingHttpTransport() {
            @Override
            public HttpResult post(URI endpoint, String jsonBody, char[] bearerToken, long max) throws IOException {
                limits.add(max);
                throw new ResponseTooLargeException(max);
            }
        };
        try {
            new OpenAiCompatibleEmbeddingAdapter(CONFIG, tokens(), tooLarge).embed(Collections.singletonList("a"));
            fail("expected INVALID_RESPONSE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
        }
        assertTrue(limits.get(0) > 2 * 64 && limits.get(0) < 1024 * 1024);
    }

    @Test
    public void rejectsMissingCollaborators() {
        try {
            new OpenAiCompatibleEmbeddingAdapter(null, tokens());
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new OpenAiCompatibleEmbeddingAdapter(CONFIG, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static BearerTokenSource tokens() {
        return new BearerTokenSource() {
            @Override
            public char[] bearerToken() {
                return "tok".toCharArray();
            }
        };
    }

    private static final class RecordingTransport implements EmbeddingHttpTransport {
        private final String response;
        final List<String> bodies = new ArrayList<String>();
        final List<String> tokens = new ArrayList<String>();

        RecordingTransport(String response) {
            this.response = response;
        }

        @Override
        public HttpResult post(URI endpoint, String jsonBody, char[] bearerToken, long max) {
            bodies.add(jsonBody);
            tokens.add(bearerToken == null ? null : new String(bearerToken));
            return new HttpResult(200, response);
        }
    }
}
