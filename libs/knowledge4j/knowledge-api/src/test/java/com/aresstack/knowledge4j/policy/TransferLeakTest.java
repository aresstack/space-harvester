package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceQuery;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Internal content never reaches an external exit once the policy denies it, whatever the caller passes. */
class TransferLeakTest {

    private static final SourceRef WIKI = SourceRef.of(SourceKind.INTERNAL_DOCUMENT, "wiki:space/page-42");

    /** An external search that records every query it receives. */
    static final class RecordingSearch implements SourceSearchPort {
        final List<String> received = new ArrayList<>();

        @Override
        public List<SourceHit> search(SourceQuery query, CancellationSignal cancel) {
            received.add(query.text());
            return List.of();
        }
    }

    /** The host's internal document service: returns internal content. */
    static final class InternalFetch implements ContentFetchPort {
        @Override
        public boolean supports(SourceRef ref) {
            return true;
        }

        @Override
        public FetchedContent fetch(SourceRef ref, CancellationSignal cancel) {
            return new FetchedContent(ref, "Betriebsvereinbarung 7: interne Regel", Map.of(), List.of(),
                    Provenance.of(Classification.INTERNAL, ref));
        }
    }

    @Test
    void afterReadingInternalContentAPublicLabelledQueryStillCannotLeave() throws Exception {
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(),
                Provenance.classified(Classification.PUBLIC));
        RecordingSearch web = new RecordingSearch();
        SourceSearchPort guardedWeb = new GuardedSourceSearchPort(web, TransferTarget.external("web"), guard);
        ContentFetchPort guardedWiki = new GuardedContentFetchPort(new InternalFetch(),
                TransferTarget.internal("wiki"), guard);

        guardedWeb.search(new SourceQuery("public question", "", 5, Provenance.classified(Classification.PUBLIC)),
                CancellationSignal.NEVER);
        assertEquals(1, web.received.size(), "a public run may search the web");

        guardedWiki.fetch(WIKI, CancellationSignal.NEVER);
        SourceAccessException denied = assertThrows(SourceAccessException.class, () -> guardedWeb.search(
                new SourceQuery("derived from Betriebsvereinbarung 7", "", 5,
                        Provenance.classified(Classification.PUBLIC)), CancellationSignal.NEVER));

        assertEquals(SourceAccessException.Reason.DENIED_BY_POLICY, denied.reason());
        assertEquals(1, web.received.size(), "not one byte of the derived query reached the external search");
        assertFalse(denied.getMessage().contains("Betriebsvereinbarung"), "the reason never carries the payload");
    }

    @Test
    void aRunWithoutReleaseIsInternal() {
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(), null);
        RecordingSearch web = new RecordingSearch();
        SourceSearchPort guardedWeb = new GuardedSourceSearchPort(web, TransferTarget.external("web"), guard);

        assertThrows(SourceAccessException.class, () -> guardedWeb.search(
                new SourceQuery("anything", "", 5, null), CancellationSignal.NEVER));
        assertTrue(web.received.isEmpty());
    }

    @Test
    void approvalRequiredIsADenialNotADialog() {
        RunGuard guard = new RunGuard(request -> TransferDecision.approvalRequired("external search"),
                Provenance.classified(Classification.PUBLIC));
        RecordingSearch web = new RecordingSearch();
        SourceSearchPort guardedWeb = new GuardedSourceSearchPort(web, TransferTarget.external("web"), guard);

        SourceAccessException denied = assertThrows(SourceAccessException.class, () -> guardedWeb.search(
                new SourceQuery("q", "", 5, null), CancellationSignal.NEVER));
        assertEquals(SourceAccessException.Reason.APPROVAL_REQUIRED, denied.reason());
        assertTrue(web.received.isEmpty());
    }

    @Test
    void theMarkNeverDrops() {
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(),
                Provenance.classified(Classification.PUBLIC));
        guard.sawContent(Provenance.of(Classification.RESTRICTED, WIKI));
        guard.sawContent(Provenance.classified(Classification.PUBLIC));
        assertEquals(Classification.RESTRICTED, guard.mark().classification());
    }
}
