package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Berechnen, Aktualität und Fehlerisolierung der Anreicherungen. */
class EnrichmentsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T21:00:00Z"), ZoneOffset.UTC);

    /** Projiziert {@code test-text}-Payloads als Text, lehnt {@code broken} ab und unterstützt sonst nichts. */
    private static final PageTextProjector PROJECTOR = snapshot -> switch (snapshot.payload().nativeFormat()) {
        case "test-text" -> Optional.of(new PageText("test/v1",
                new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
        case "broken" -> throw new IllegalArgumentException("kaputt");
        default -> Optional.empty();
    };

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Test
    void independentProcessorsEachStoreTheirResult() {
        Enrichments enrichments = enrichments(new AcronymProcessor(), new OutlineProcessor());
        PageSnapshot page = text("wiki", "101", "3", "Die API (Application Programming Interface) ist neu.");

        Enrichments.Run run = enrichments.refresh(page);

        assertThat(run).isEqualTo(new Enrichments.Run(true, 2, 0, 0));
        assertThat(store.find(page.key())).extracting(Enrichment::processorId, Enrichment::processorVersion,
                Enrichment::revision, Enrichment::status, Enrichment::computedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("acronyms", "2", "3", Enrichment.Status.OK,
                                CLOCK.instant()),
                        org.assertj.core.groups.Tuple.tuple("outline", "1", "3", Enrichment.Status.OK,
                                CLOCK.instant()));
        assertThat(store.find(page.key()).get(0).payloadJson()).contains("\"expansion\":\"Application Programming"
                + " Interface\"");
    }

    @Test
    void aFailingProcessorDoesNotStopTheOthersAndLeaksNothing() {
        Enrichments enrichments = enrichments(new Fixed("aaa", "1", null), new OutlineProcessor());
        PageSnapshot page = text("wiki", "101", "1", "geheimer Text");

        Enrichments.Run run = enrichments.refresh(page);

        assertThat(run).isEqualTo(new Enrichments.Run(true, 2, 0, 1));
        Enrichment failed = store.find(page.key()).get(0);
        assertThat(failed.status()).isEqualTo(Enrichment.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo(Enrichments.PROCESSOR_ERROR);
        assertThat(failed.payloadJson()).isEqualTo("{}");
        assertThat(failed.toString()).doesNotContain("geheim");
        assertThat(store.find(page.key()).get(1).status()).isEqualTo(Enrichment.Status.OK);
    }

    @Test
    void currentResultsAreNotRecomputedAndAFailedOneIsNotRetried() {
        Fixed counting = new Fixed("aaa", "1", null);
        Enrichments enrichments = enrichments(counting, new OutlineProcessor());
        PageSnapshot page = text("wiki", "101", "1", "Text");
        enrichments.refresh(page);

        Enrichments.Run again = enrichments.refresh(page);

        assertThat(again).isEqualTo(new Enrichments.Run(true, 0, 2, 0));
        assertThat(counting.calls.get()).isEqualTo(1);
    }

    @Test
    void aNewProcessorVersionInvalidatesOnlyItsOwnResult() {
        PageSnapshot page = text("wiki", "101", "1", "Text");
        enrichments(new Fixed("aaa", "1", "{\"v\":1}"), new Fixed("bbb", "1", "{\"v\":1}")).refresh(page);
        Enrichments upgraded = enrichments(new Fixed("aaa", "2", "{\"v\":2}"), new Fixed("bbb", "1", "{\"v\":9}"));

        assertThat(upgraded.view(page)).extracting(Enrichments.View::freshness)
                .containsExactly(Enrichment.Freshness.STALE, Enrichment.Freshness.CURRENT);
        assertThat(upgraded.refresh(page)).isEqualTo(new Enrichments.Run(true, 1, 1, 0));
        assertThat(store.find(page.key())).extracting(Enrichment::payloadJson)
                .containsExactly("{\"v\":2}", "{\"v\":1}");
    }

    @Test
    void changedInputMakesEveryResultStaleUntilRefreshed() {
        Enrichments enrichments = enrichments(new OutlineProcessor());
        enrichments.refresh(text("wiki", "101", "1", "alt"));
        PageSnapshot changed = text("wiki", "101", "2", "neuer Text");

        assertThat(enrichments.view(changed)).extracting(Enrichments.View::freshness)
                .containsExactly(Enrichment.Freshness.STALE);
        enrichments.refresh(changed);
        assertThat(enrichments.view(changed)).singleElement().satisfies(view -> {
            assertThat(view.freshness()).isEqualTo(Enrichment.Freshness.CURRENT);
            assertThat(view.enrichment().revision()).isEqualTo("2");
            assertThat(view.enrichment().payloadJson()).startsWith("{\"words\":2,");
        });
    }

    @Test
    void sameTextInANewRevisionStaysCurrent() {
        Enrichments enrichments = enrichments(new OutlineProcessor());
        enrichments.refresh(text("wiki", "101", "1", "gleich"));

        assertThat(enrichments.refresh(text("wiki", "101", "2", "gleich")))
                .isEqualTo(new Enrichments.Run(true, 0, 1, 0));
    }

    @Test
    void resultsOfARemovedProcessorAreStale() {
        PageSnapshot page = text("wiki", "101", "1", "Text");
        enrichments(new Fixed("aaa", "1", "{}"), new OutlineProcessor()).refresh(page);

        assertThat(enrichments(new OutlineProcessor()).view(page)).extracting(view -> view.enrichment().processorId(),
                Enrichments.View::freshness).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("aaa", Enrichment.Freshness.STALE),
                        org.assertj.core.groups.Tuple.tuple("outline", Enrichment.Freshness.CURRENT));
    }

    @Test
    void recomputeRunsCurrentProcessorsAgain() {
        Fixed counting = new Fixed("aaa", "1", "{}");
        Enrichments enrichments = enrichments(counting, new OutlineProcessor());
        PageSnapshot page = text("wiki", "101", "1", "Text");
        enrichments.refresh(page);

        assertThat(enrichments.recompute(page, "aaa")).isEqualTo(new Enrichments.Run(true, 1, 0, 0));
        assertThat(enrichments.recompute(page, null)).isEqualTo(new Enrichments.Run(true, 2, 0, 0));
        assertThat(counting.calls.get()).isEqualTo(3);
        assertThatThrownBy(() -> enrichments.recompute(page, "unbekannt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedOrMalformedPayloadsComputeNothingAndLeaveThePayloadAlone() {
        Enrichments enrichments = enrichments(new OutlineProcessor());
        byte[] bytes = "{\"unverändert\":true}".getBytes(StandardCharsets.UTF_8);
        PageSnapshot other = EnrichmentTestPages.page("wiki", "101", "1", bytes, "anderes-format");
        PageSnapshot broken = EnrichmentTestPages.page("wiki", "102", "1", bytes, "broken");

        assertThat(enrichments.refresh(other)).isEqualTo(new Enrichments.Run(false, 0, 0, 0));
        assertThat(enrichments.refresh(broken)).isEqualTo(new Enrichments.Run(false, 0, 0, 0));
        assertThat(store.find(other.key())).isEmpty();
        assertThat(other.payload().bytes()).isEqualTo(bytes);
    }

    @Test
    void refreshingLeavesThePayloadByteIdentical() {
        PageSnapshot page = text("wiki", "101", "1", "Die API (Application Programming Interface).");
        String before = page.payload().rawSha256();

        enrichments(new AcronymProcessor(), new OutlineProcessor()).refresh(page);

        assertThat(page.payload().rawSha256()).isEqualTo(before);
    }

    @Test
    void processorIdsMustBeValidAndUnique() {
        assertThatThrownBy(() -> enrichments(new Fixed("Groß", "1", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enrichments(new Fixed("aaa", "1", "{}"), new Fixed("aaa", "2", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(enrichments(new OutlineProcessor(), new AcronymProcessor()).processorIds())
                .containsExactly("outline", "acronyms");
    }

    private Enrichments enrichments(EnrichmentProcessor... processors) {
        return new Enrichments(PROJECTOR, List.of(processors), store, CLOCK);
    }

    /** Liefert immer dasselbe Ergebnis oder scheitert, wenn keines vorgegeben ist. */
    private static final class Fixed implements EnrichmentProcessor {

        private final String id;
        private final String version;
        private final String payload;
        private final AtomicInteger calls = new AtomicInteger();

        private Fixed(String id, String version, String payload) {
            this.id = id;
            this.version = version;
            this.payload = payload;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            calls.incrementAndGet();
            if (payload == null) {
                throw new IllegalStateException("Fehler mit Seitentext: " + text.text());
            }
            return EnrichmentOutput.of(payload);
        }
    }
}
