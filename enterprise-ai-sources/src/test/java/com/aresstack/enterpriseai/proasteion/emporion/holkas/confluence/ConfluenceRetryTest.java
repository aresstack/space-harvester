package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Begrenzte Wiederholungen bei Netzfehler, 429 und 5xx; nie bei 401, 403 oder 404. */
public class ConfluenceRetryTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>").storage("<p>Home</p>");
        confluence.homepage("DEV", "100");
    }

    private ConfluenceKnowledgeSource source(int maxRetries) {
        return new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).maxRetries(maxRetries).retryBackoffMillis(0).build(),
                confluence, new RecordingSecretProvider());
    }

    @Test
    public void temporaryServerErrorsAndTimeoutsAreRetried() throws Exception {
        confluence.failNext(503, 1).failNextWithTimeout(1).failNext(502, 1);

        assertEquals("Start", source(3).resourceMetadata("100").title());
        assertEquals(4, confluence.requestCount("/rest/api/content/100"));
    }

    @Test
    public void lastFailureIsReportedAfterTheRetriesWithoutEndlessAttempts() {
        confluence.failNext(503, 10);
        try {
            source(2).resourceMetadata("100");
            fail("expected UNAVAILABLE");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 503"));
            assertTrue(e.getMessage(), e.getMessage().contains("2 Wiederholungen"));
        }
        assertEquals(3, confluence.requestCount("/rest/api/content/100"));
    }

    @Test
    public void rateLimitStaysRecognizableAfterTheRetries() {
        confluence.failNext(429, 10);
        try {
            source(1).storedContent("100");
            fail("expected RateLimitedException");
        } catch (RateLimitedException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
        } catch (KnowledgeSourceException e) {
            fail("expected RateLimitedException, got " + e);
        }
        assertEquals(2, confluence.requestCount("/rest/api/content/100"));
    }

    @Test
    public void accessAndNotFoundAreNeverRetried() {
        confluence.failNext(401, 1);
        assertKind(Kind.ACCESS_DENIED);
        confluence.failNext(403, 1);
        assertKind(Kind.NOT_FOUND); // eine für das Konto gesperrte Seite
        assertKindFor(Kind.NOT_FOUND, "999");
        assertEquals(2, confluence.requestCount("/rest/api/content/100"));
        assertEquals(1, confluence.requestCount("/rest/api/content/999"));
    }

    @Test
    public void withoutRetriesTheFirstFailureCounts() {
        confluence.failNext(503, 1);
        assertKind(Kind.UNAVAILABLE, source(0));
        assertEquals(1, confluence.requestCount("/rest/api/content/100"));
    }

    @Test
    public void backoffGrowsAndStaysWithinItsLimit() {
        for (int attempt = 0; attempt < 40; attempt++) {
            long delay = ConfluenceRestClient.backoffMillis(attempt, 1000, 30000);
            long full = Math.min(30000L, 1000L << Math.min(attempt, 20));
            assertTrue(attempt + ": " + delay, delay >= full / 2 && delay <= full);
        }
        assertEquals(0, ConfluenceRestClient.backoffMillis(5, 0, 0));
    }

    @Test
    public void retriesAreConfigurableAndBounded() {
        assertEquals(0, ConfluenceConfig.builder(FakeConfluence.BASE).build().maxRetries());
        try {
            ConfluenceConfig.builder(FakeConfluence.BASE).maxRetries(11);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private void assertKind(Kind expected) {
        assertKind(expected, source(3));
    }

    private void assertKind(Kind expected, ConfluenceKnowledgeSource source) {
        try {
            source.resourceMetadata("100");
            fail("expected " + expected);
        } catch (KnowledgeSourceException e) {
            assertEquals(expected, e.kind());
        }
    }

    private void assertKindFor(Kind expected, String id) {
        try {
            source(3).resourceMetadata(id);
            fail("expected " + expected);
        } catch (KnowledgeSourceException e) {
            assertEquals(expected, e.kind());
        }
    }
}
