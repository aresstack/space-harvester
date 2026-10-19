package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationBuilder;
import com.aresstack.spaceharvester.application.passages.NavigationProcessor;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.PassageChunks;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
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
                        org.assertj.core.groups.Tuple.tuple("acronyms", "3", "3", Enrichment.Status.OK,
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

    @Test
    void backgroundProcessorsRunOutsideTheCallerAndOnlyOncePerPage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Remote remote = new Remote(key -> true, release);
        ExecutorService background = Enrichments.background(10);
        try {
            Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote, new OutlineProcessor()), store,
                    CLOCK, background);
            PageSnapshot page = text("wiki", "101", "1", "Text");

            // Der Aufrufer wartet nicht auf den Dienst; dieselbe Seite wird nicht doppelt eingeplant.
            assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 1, 0, 0));
            assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 0, 1, 0));
            assertThat(store.find(page.key())).extracting(Enrichment::processorId).containsExactly("outline");

            release.countDown();
            background.shutdown();
            assertThat(background.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(remote.calls.get()).isEqualTo(1);
            assertThat(store.find(page.key())).extracting(Enrichment::processorId)
                    .containsExactlyInAnyOrder("outline", "remote");
            assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 0, 2, 0));
        } finally {
            background.shutdownNow();
        }
    }

    @Test
    void aFullBackgroundDropsWorkWithoutFailingTheCaller() {
        ExecutorService closed = Enrichments.background(1);
        closed.shutdown();
        Remote remote = new Remote(key -> true, new CountDownLatch(0));
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(remote), store, CLOCK, closed);
        PageSnapshot page = text("wiki", "101", "1", "Text");

        assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 0, 0, 0));
        assertThat(store.find(page.key())).isEmpty();
        // Auf ausdrücklichen Wunsch im aufrufenden Thread.
        assertThat(enrichments.recompute(page, "remote")).isEqualTo(new Enrichments.Run(true, 1, 0, 0));
    }

    @Test
    void navigationOfAnUnapprovedSourceIsComputedAtOnceAndStaysCurrent() {
        // Ein geschlossener Hintergrund nähme nichts an: Was eingeplant würde, fehlte im Ergebnis.
        ExecutorService closed = Enrichments.background(1);
        closed.shutdown();
        EmbeddingProcessor embedding = new EmbeddingProcessor(() -> new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "remote@1";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new AssertionError("nicht freigegeben");
            }
        }, 1000, KnowledgeTextSegmentation.defaults(), sourceId -> sourceId.equals("approved"))
                .withPassages(new PassageChunks(new PassageSegmentation(OpenNlpSentenceSegmenter.german(
                        SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults())),
                        KnowledgeTextSegmentation.defaults())));
        NavigationProcessor navigation = new NavigationProcessor(new DocumentNavigationBuilder(), embedding,
                store::find);
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(navigation), store, CLOCK, closed);
        PageSnapshot page = text("wiki", "101", "1", "Text");

        assertThat(navigation.background(page.key())).isFalse();
        assertThat(navigation.background(new PageKey("approved", "101"))).isTrue();
        assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 1, 0, 0));
        assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 0, 1, 0));
        assertThat(store.find(page.key())).singleElement()
                .satisfies(result -> assertThat(result.payloadJson()).contains("\"reason\":\"source-not-approved\""));
    }

    @Test
    void processorsSkipPagesTheyDoNotApplyTo() {
        Remote remote = new Remote(key -> key.sourceId().equals("approved"), new CountDownLatch(0));
        Enrichments enrichments = enrichments(remote);

        assertThat(enrichments.refresh(text("wiki", "101", "1", "Text")))
                .isEqualTo(new Enrichments.Run(true, 0, 0, 0));
        assertThat(enrichments.recompute(text("wiki", "101", "1", "Text"), null))
                .isEqualTo(new Enrichments.Run(true, 0, 0, 0));
        assertThat(enrichments.refresh(text("approved", "101", "1", "Text")))
                .isEqualTo(new Enrichments.Run(true, 1, 0, 0));
        assertThat(remote.calls.get()).isEqualTo(1);
    }

    @Test
    void aReleaseWithdrawnWhileSavingLeavesNoResult() {
        // Gilt bei der Auswahl und vor dem Speichern, danach nicht mehr: Der Entzug kam zwischen Prüfung und Speichern.
        AtomicInteger checks = new AtomicInteger();
        Remote remote = new Remote(key -> checks.incrementAndGet() <= 2, new CountDownLatch(0));
        PageSnapshot page = text("approved", "101", "1", "Text");

        assertThat(enrichments(remote).recompute(page, null)).isEqualTo(new Enrichments.Run(true, 0, 0, 0));
        assertThat(remote.calls.get()).isEqualTo(1);
        assertThat(store.find(page.key())).isEmpty();
    }

    @Test
    void aProcessorThatMayNotProcessThePageStoresNothing() {
        Enrichments enrichments = enrichments(new EnrichmentProcessor() {
            @Override
            public String id() {
                return "remote";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public EnrichmentOutput process(PageText text) {
                throw new ProcessingNotAllowedException("Quelle nicht für den KI-Dienst freigegeben");
            }
        });
        PageSnapshot page = text("wiki", "101", "1", "Text");

        assertThat(enrichments.refresh(page)).isEqualTo(new Enrichments.Run(true, 0, 0, 0));
        assertThat(store.find(page.key())).isEmpty();
    }

    private Enrichments enrichments(EnrichmentProcessor... processors) {
        return new Enrichments(PROJECTOR, List.of(processors), store, CLOCK);
    }

    /** Wie ein Prozessor, der über das Netz rechnet: im Hintergrund, nur für freigegebene Quellen. */
    private static final class Remote implements EnrichmentProcessor {

        private final Predicate<PageKey> applies;
        private final CountDownLatch release;
        private final AtomicInteger calls = new AtomicInteger();

        private Remote(Predicate<PageKey> applies, CountDownLatch release) {
            this.applies = applies;
            this.release = release;
        }

        @Override
        public String id() {
            return "remote";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public boolean appliesTo(PageKey key) {
            return applies.test(key);
        }

        @Override
        public boolean background() {
            return true;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            calls.incrementAndGet();
            return EnrichmentOutput.of("{}");
        }
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
