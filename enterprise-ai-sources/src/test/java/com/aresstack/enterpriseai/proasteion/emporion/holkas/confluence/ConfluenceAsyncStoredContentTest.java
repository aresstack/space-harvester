package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AsyncStoredContentSource} des Confluence-Adapters: dieselbe Anfrage, Anmeldung und Abbildung wie
 * {@code storedContent}, nur über {@link ConfluenceAsyncHttpTransport}. Der Testtransport hält jede Anfrage offen,
 * bis der Test sie auf einem eigenen Thread mit der Antwort der Fake-Confluence beantwortet.
 */
public class ConfluenceAsyncStoredContentTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private final HeldTransport async = new HeldTransport(confluence);
    private final ExecutorService mapping = Executors.newSingleThreadExecutor(r -> new Thread(r, "mapping"));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "io"));
    private ConfluenceKnowledgeSource sync;
    private AsyncConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>").labels("howto").storage("<p>Guide</p>");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
        sync = new ConfluenceKnowledgeSource(SOURCE, config(20 * 1024 * 1024), confluence,
                new RecordingSecretProvider());
        source = source(20 * 1024 * 1024);
    }

    @After
    public void tearDown() {
        mapping.shutdownNow();
        io.shutdownNow();
    }

    private static ConfluenceConfig config(int maxBytes) {
        return ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF)
                .maxResponseBytes(maxBytes).build();
    }

    private AsyncConfluenceKnowledgeSource source(int maxBytes) {
        RecordingSecretProvider secrets = new RecordingSecretProvider();
        return new AsyncConfluenceKnowledgeSource(SOURCE, config(maxBytes), confluence, async, mapping, secrets,
                new VaultEntryOptions(secrets, RecordingSecretProvider.REF));
    }

    @Test
    public void deliversTheSameSnapshotAsTheBlockingCall() throws Exception {
        StoredContent expected = sync.storedContent("101");

        StoredContent stored = answered(source.storedContentAsync("101"));

        assertArrayEquals(expected.payload(), stored.payload());
        assertEquals(expected.sha256(), stored.sha256());
        assertEquals(expected.nativeFormat(), stored.nativeFormat());
        assertEquals(expected.mediaType(), stored.mediaType());
        assertEquals(expected.resource().id(), stored.resource().id());
        assertEquals(expected.resource().revision(), stored.resource().revision());
        assertEquals(expected.resource().metadata(), stored.resource().metadata());
        assertEquals(confluence.requests().get(0), async.uris.get(0));
    }

    @Test
    public void returnsBeforeConfluenceAnswersAndSendsTheSameHeaders() throws Exception {
        CompletionStage<StoredContent> stage = source.storedContentAsync("101");

        assertFalse(stage.toCompletableFuture().isDone());
        assertEquals(1, async.pending.size());
        Map<String, String> headers = async.headers.get(0);
        assertTrue(headers.get("Authorization").startsWith("Basic "));
        assertEquals("application/json", headers.get("Accept"));

        answerAll();
        assertEquals("101", stage.toCompletableFuture().get(5, TimeUnit.SECONDS).resource().metadata()
                .get("confluence.contentId").get());
    }

    @Test
    public void parsingRunsOnTheMappingExecutorNotOnTheIoThread() throws Exception {
        AtomicReference<String> thread = new AtomicReference<String>();
        CompletionStage<StoredContent> stage = source.storedContentAsync("101")
                .whenComplete((content, error) -> thread.set(Thread.currentThread().getName()));

        answered(stage);

        assertEquals("mapping", thread.get());
    }

    @Test
    public void aRestrictedPageLooksLikeAnUnknownOneAsInTheBlockingCall() throws Exception {
        confluence.restricted.add("101");

        assertKind(Kind.NOT_FOUND, source.storedContentAsync("101"));
        assertKind(Kind.NOT_FOUND, source.storedContentAsync("999"));
    }

    @Test
    public void transientFailuresAreRetriedWithTheConfiguredRules() throws Exception {
        RecordingSecretProvider secrets = new RecordingSecretProvider();
        AsyncConfluenceKnowledgeSource retrying = new AsyncConfluenceKnowledgeSource(SOURCE,
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF)
                        .maxRetries(2).retryBackoffMillis(1).maxRetryBackoffMillis(1).build(),
                confluence, async, mapping, secrets, new VaultEntryOptions(secrets, RecordingSecretProvider.REF));
        confluence.forcedFailure = new IOException("Verbindung zurückgesetzt");

        CompletionStage<StoredContent> stage = retrying.storedContentAsync("101");
        Throwable failure = failureAfterRetries(stage);

        assertEquals(3, async.uris.size());
        assertEquals(Kind.UNAVAILABLE, ((KnowledgeSourceException) failure).kind());
        assertTrue(failure.getMessage(), failure.getMessage().contains("nach 2 Wiederholungen"));
    }

    @Test
    public void aRetryRecoversWhenConfluenceAnswersAgain() throws Exception {
        RecordingSecretProvider secrets = new RecordingSecretProvider();
        AsyncConfluenceKnowledgeSource retrying = new AsyncConfluenceKnowledgeSource(SOURCE,
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF)
                        .maxRetries(1).retryBackoffMillis(1).maxRetryBackoffMillis(1).build(),
                confluence, async, mapping, secrets, new VaultEntryOptions(secrets, RecordingSecretProvider.REF));
        confluence.forcedFailure = new IOException("Verbindung zurückgesetzt");
        CompletionStage<StoredContent> stage = retrying.storedContentAsync("101");
        answerAll();
        confluence.forcedFailure = null;

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!stage.toCompletableFuture().isDone() && System.nanoTime() < deadline) {
            answerAll();
            Thread.sleep(5);
        }

        assertEquals(2, async.uris.size());
        assertEquals("101", stage.toCompletableFuture().get(1, TimeUnit.SECONDS).resource().metadata()
                .get("confluence.contentId").get());
    }

    @Test
    public void aForeignIdFailsTheStageWithoutARequest() throws Exception {
        assertKind(Kind.UNSUPPORTED, source.storedContentAsync("att123"));
        assertTrue(async.uris.isEmpty());
    }

    @Test
    public void aResponseOverTheLimitFailsTheStage() throws Exception {
        Throwable failure = failure(source(100).storedContentAsync("101"));

        assertTrue(String.valueOf(failure), failure instanceof ResponseTooLargeException);
        assertEquals(100, ((ResponseTooLargeException) failure).maxBytes());
    }

    @Test
    public void aTransportFailureIsUnavailable() throws Exception {
        confluence.forcedFailure = new IOException("Verbindung abgelehnt");

        Throwable failure = failure(source.storedContentAsync("101"));

        assertEquals(Kind.UNAVAILABLE, ((KnowledgeSourceException) failure).kind());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Verbindung abgelehnt"));
    }

    @Test
    public void cancellingTheStageCancelsTheRequest() {
        CompletionStage<StoredContent> stage = source.storedContentAsync("101");

        assertTrue(stage.toCompletableFuture().cancel(true));

        assertTrue(async.pending.get(0).isCancelled());
    }

    @Test
    public void missingCredentialsFailTheStage() throws Exception {
        RecordingSecretProvider secrets = new RecordingSecretProvider();
        secrets.failWith = com.aresstack.enterpriseai.adyton.SecretUnavailableException.Reason.NOT_AVAILABLE;
        AsyncConfluenceKnowledgeSource locked = new AsyncConfluenceKnowledgeSource(SOURCE, config(1 << 20),
                confluence, async, mapping, secrets, new VaultEntryOptions(secrets, RecordingSecretProvider.REF));

        assertKind(Kind.UNAVAILABLE, locked.storedContentAsync("101"));
        assertTrue(async.uris.isEmpty());
    }

    @Test
    public void theProviderOffersTheCapabilityOnlyWithAnAsyncTransport() {
        RecordingSecretProvider secrets = new RecordingSecretProvider();
        ConfluenceSourceProvider provider = new ConfluenceSourceProvider(secrets, null, null, null,
                (alias, file, ref) -> null);
        List<String> certificates = new ArrayList<String>();
        ConfluenceSourceProvider withAsync = provider.withAsyncTransports(
                (id, alias, file, ref, connect, read) -> {
                    certificates.add(alias + "|" + file + "|" + ref + "|" + connect + "|" + read);
                    return async;
                }, mapping);

        Map<String, String> anonymous = new HashMap<String, String>();
        anonymous.put("baseUrl", "https://confluence.example.org/wiki");
        Map<String, String> pkcs12 = new HashMap<String, String>(anonymous);
        pkcs12.put("clientCertificate.alias", "harvester");
        pkcs12.put("clientCertificate.keyStoreFile", "client.p12");
        pkcs12.put("clientCertificate.keyStorePasswordRef", "keepass:p12");
        pkcs12.put("readTimeoutMillis", "5000");
        Map<String, String> vaultAlias = new HashMap<String, String>(anonymous);
        vaultAlias.put("credentialRef", RecordingSecretProvider.REF.toString());

        assertFalse(open(provider, anonymous) instanceof AsyncStoredContentSource);
        assertTrue(open(provider, anonymous) instanceof StoredContentSource);
        assertTrue(open(withAsync, anonymous) instanceof AsyncStoredContentSource);
        assertTrue(open(withAsync, pkcs12) instanceof AsyncStoredContentSource);
        assertFalse(open(withAsync, vaultAlias) instanceof AsyncStoredContentSource);
        assertEquals(2, certificates.size());
        assertEquals("null|null|null|15000|60000", certificates.get(0));
        assertEquals("harvester|" + Paths.get("client.p12") + "|" + SecretRef.of("keepass:p12") + "|15000|5000",
                certificates.get(1));
    }

    @Test
    public void aProviderTransportOfNullKeepsTheSourceSynchronous() {
        ConfluenceSourceProvider provider = new ConfluenceSourceProvider(new RecordingSecretProvider(), null, null,
                null, (alias, file, ref) -> null).withAsyncTransports((id, a, f, r, c, t) -> null, mapping);
        Map<String, String> settings = new HashMap<String, String>();
        settings.put("baseUrl", "https://confluence.example.org/wiki");

        KnowledgeSourcePort port = open(provider, settings);

        assertFalse(port instanceof AsyncStoredContentSource);
    }

    private static KnowledgeSourcePort open(ConfluenceSourceProvider provider, Map<String, String> settings) {
        return provider.open(SOURCE, SourceSettings.of(settings));
    }

    private <T> T answered(CompletionStage<T> stage) throws Exception {
        answerAll();
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private Throwable failure(CompletionStage<?> stage) throws Exception {
        answerAll();
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("expected failure");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private Throwable failureAfterRetries(CompletionStage<?> stage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!stage.toCompletableFuture().isDone() && System.nanoTime() < deadline) {
            answerAll();
            Thread.sleep(5);
        }
        return failure(stage);
    }

    private void assertKind(Kind expected, CompletionStage<?> stage) throws Exception {
        Throwable failure = failure(stage);
        assertTrue(String.valueOf(failure), failure instanceof KnowledgeSourceException);
        assertEquals(expected, ((KnowledgeSourceException) failure).kind());
    }

    /** Beantwortet alle offenen Anfragen auf dem Thread {@code io}, wie ein Netty-Event-Loop. */
    private void answerAll() throws Exception {
        io.submit(async::answerAll).get(5, TimeUnit.SECONDS);
    }

    /** Hält Anfragen offen, bis {@link #answerAll} sie mit der Antwort des synchronen Fakes beendet. */
    private static final class HeldTransport implements ConfluenceAsyncHttpTransport {

        final List<URI> uris = new ArrayList<URI>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        final List<CompletableFuture<ConfluenceHttpResponse>> pending =
                new ArrayList<CompletableFuture<ConfluenceHttpResponse>>();
        private final List<Integer> limits = new ArrayList<Integer>();
        private final FakeConfluence confluence;

        HeldTransport(FakeConfluence confluence) {
            this.confluence = confluence;
        }

        @Override
        public synchronized CompletionStage<ConfluenceHttpResponse> getAsync(URI uri, Map<String, String> headers,
                                                                          int maxBytes) {
            CompletableFuture<ConfluenceHttpResponse> future = new CompletableFuture<ConfluenceHttpResponse>();
            uris.add(uri);
            this.headers.add(headers);
            limits.add(maxBytes);
            pending.add(future);
            return future;
        }

        @Override
        public CompletionStage<ConfluenceHttpResponse> getAnonymousAsync(URI uri, Map<String, String> headers,
                                                                         int maxBytes) {
            throw new AssertionError("Snapshots folgen keinen fremden Weiterleitungen");
        }

        synchronized void answerAll() {
            for (int i = 0; i < pending.size(); i++) {
                CompletableFuture<ConfluenceHttpResponse> future = pending.get(i);
                if (future.isDone()) {
                    continue;
                }
                try {
                    future.complete(confluence.get(uris.get(i), headers.get(i), limits.get(i)));
                } catch (IOException e) {
                    future.completeExceptionally(e);
                }
            }
        }
    }
}
