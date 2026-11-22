package com.aresstack.spaceharvester.application.reading;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Lese-Ansicht als Core-Anwendungsfall (#176): Rechte, Version, Ablage, eingebundene Inhalte. */
class ReadingViewsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("leser");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC);
    private static final String INCLUDE = "{\"body\":{\"storage\":{\"value\":\"<ac:structured-macro "
            + "ac:name=\\\"include\\\"/><ac:structured-macro ac:name=\\\"jira\\\"/>\"}}}";

    private final Source source = new Source();
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
    };
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();

    ReadingViewsTest() {
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(page("wiki", "DEV", "100", null),
                page("wiki", "DEV", "101", null, INCLUDE.getBytes(StandardCharsets.UTF_8))));
    }

    private ReadingViews views(AccessPolicy policy) {
        PageFreshness cacheOnly = new PageFreshness(new SourceQueries(catalog, CLOCK), null, CLOCK);
        return new ReadingViews(catalog, new PageQueries(catalog, cache, new AccessGuard(policy), cacheOnly, 1000),
                new AccessGuard(policy), new ReadingViewStore(enrichments), CLOCK);
    }

    @Test
    void theViewIsFetchedOnceThenServedFromBesideTheSnapshot() {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);

        ReadingView first = views.view(CALLER, "wiki", "100", false);
        ReadingView second = views.view(CALLER, "wiki", "100", false);

        assertThat(first.status()).isEqualTo(ReadingView.Status.AVAILABLE);
        assertThat(first.stored()).isFalse();
        assertThat(first.html()).contains("Kinderseite");
        assertThat(first.text()).contains("Kinderseite");
        assertThat(first.version()).isEqualTo("3");
        assertThat(first.images()).singleElement().satisfies(image -> assertThat(image.attachmentId())
                .isEqualTo("att1"));
        assertThat(second.stored()).isTrue();
        assertThat(second.html()).isEqualTo(first.html());
        assertThat(source.reads).isEqualTo(1);
        // Kein Eintrag in der Liste der Anreicherungen; der Raw-Snapshot bleibt unverändert.
        Enrichments listed = new Enrichments(snapshot -> Optional.empty(), List.of(), enrichments, CLOCK);
        assertThat(listed.view(cache.page(new PageKey("wiki", "100")).orElseThrow().snapshot())).isEmpty();
    }

    @Test
    void theCachedViewNeverAsksTheSourceAndIsEmptyUntilAViewWasFetched() {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);

        assertThat(views.cached(CALLER, "wiki", "100")).isEmpty();
        views.view(CALLER, "wiki", "100", false);
        source.unreachable = true;

        ReadingView cached = views.cached(CALLER, "wiki", "100").orElseThrow();
        assertThat(cached.stored()).isTrue();
        assertThat(cached.version()).isEqualTo("3");
        assertThat(cached.html()).contains("Kinderseite");
        assertThat(source.reads).isEqualTo(1);
        // Auch aus der Ablage allein, ohne Speicher: ein neuer Dienst über derselben Ablage.
        assertThat(views((context, scope, kind) -> AccessDecision.ALLOW).cached(CALLER, "wiki", "100")).isPresent();
        assertThatThrownBy(() -> views((context, scope, kind) -> AccessDecision.DENY).cached(CALLER, "wiki", "100"))
                .isInstanceOf(com.aresstack.spaceharvester.application.access.AccessDeniedException.class);
    }

    @Test
    void prefetchFetchesTheViewsInTheBackgroundSoThatTheCachedViewIsThere() throws InterruptedException {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);

        views.prefetch(CALLER, "wiki", List.of("100", "100"));

        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (views.cached(CALLER, "wiki", "100").isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(views.cached(CALLER, "wiki", "100")).isPresent();
        assertThat(source.reads).isEqualTo(1);
    }

    @Test
    void prefetchGivesUpSilentlyWhenTheSourceRefusesOrIsUnreachable() throws InterruptedException {
        List<com.aresstack.spaceharvester.application.snapshot.PageSnapshot> pages = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 200; i < 220; i++) {
            pages.add(page("wiki", "DEV", String.valueOf(i), null));
            ids.add(String.valueOf(i));
        }
        cache.publish("wiki", "DEV", confirmed("g2"), pages);
        source.unreachable = true;
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);

        views.prefetch(CALLER, "wiki", ids);

        // Höchstens die schon laufenden Abrufe erreichen die Quelle, die übrigen fragen sie nicht mehr.
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(source.attempts.get()).isBetween(1, 2);
        assertThat(views.cached(CALLER, "wiki", "200")).isEmpty();
    }

    /** Aufträge mit gehaltenem Ausführer und zwei Plätzen, beide belegt: Ein neuer Auftrag wartet. */
    private static com.aresstack.spaceharvester.application.jobs.Jobs busyJobs(java.util.Queue<Runnable> held,
                                                                             int slots) {
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), held::add,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(slots, 10, java.time.Duration.ofHours(1)),
                CLOCK);
        for (int i = 0; i < slots; i++) {
            jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "andere", null, "app",
                    com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
                    }));
        }
        return jobs;
    }

    private static void runAll(java.util.Queue<Runnable> held) {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    private static List<com.aresstack.spaceharvester.application.jobs.JobStatus> prefetchJobs(
            com.aresstack.spaceharvester.application.jobs.Jobs jobs) {
        return jobs.listAll(com.aresstack.spaceharvester.application.jobs.JobType.READING_PREFETCH);
    }

    @Test
    void asAJobPrefetchIsVisibleAndANewCallReplacesTheWaitingJob() {
        // Fundstelle 15, Abnahme PR 4 und #1080: ein Klick-Auftrag, höchstens ein wartender je Quelle, der neue nimmt
        // die Seiten mit.
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 2);
        views.jobs(jobs);

        views.prefetch(CALLER, "wiki", List.of("100"));
        views.prefetch(CALLER, "wiki", List.of("101"));

        List<com.aresstack.spaceharvester.application.jobs.JobStatus> prefetches = prefetchJobs(jobs);
        assertThat(prefetches).hasSize(2);
        assertThat(prefetches).allSatisfy(job -> {
            assertThat(job.parameter(com.aresstack.spaceharvester.application.jobs.Jobs.INTERACTIVE))
                    .as("ein Klick, nie hinter Hintergrund").isEqualTo("true");
            assertThat(job.owner()).isEqualTo(CALLER.jobOwner());
        });
        assertThat(prefetches).filteredOn(job -> job.state()
                == com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED).hasSize(1);
        com.aresstack.spaceharvester.application.jobs.JobStatus waiting = prefetches.stream().filter(job -> job.state()
                == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED).findFirst().orElseThrow();
        assertThat(waiting.parameter(ReadingViews.PAGES)).isEqualTo("101\n100");

        runAll(held);

        assertThat(views.cached(CALLER, "wiki", "100")).isPresent();
        assertThat(views.cached(CALLER, "wiki", "101")).isPresent();
        assertThat(jobs.status(waiting.id()).state())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
    }

    @Test
    void theReplacedJobSaysTrulyHowManyPagesFallAwayAtTheLimit() {
        // Abnahme #1080: Die Grenze von 25 je Aufruf darf keine Seiten still wegwerfen.
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 2);
        views.jobs(jobs);
        List<String> earlier = new java.util.ArrayList<>();
        for (int i = 0; i < ReadingViews.MAX_PREFETCH; i++) {
            earlier.add("e" + i);
        }
        views.prefetch(CALLER, "wiki", earlier);
        String first = prefetchJobs(jobs).getFirst().id();

        views.prefetch(CALLER, "wiki", List.of("n1", "n2", "n3", "n4", "n5"));

        assertThat(jobs.events(first)).extracting(com.aresstack.spaceharvester.application.jobs.JobEvent::message)
                .anySatisfy(message -> assertThat(message).contains("20 dieser Seiten mit, 5 entfallen"));
        com.aresstack.spaceharvester.application.jobs.JobStatus waiting = prefetchJobs(jobs).stream()
                .filter(job -> job.state() == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED)
                .findFirst().orElseThrow();
        assertThat(waiting.parameter(ReadingViews.PAGES).split("\n")).hasSize(ReadingViews.MAX_PREFETCH)
                .startsWith("n1", "n2", "n3", "n4", "n5", "e0");
    }

    @Test
    void withASingleSlotPrefetchKeepsTheFormerWayInsteadOfWaitingBehindEverything() throws Exception {
        // Abnahme #1080: Bei nur einem Platz liefe der Auftrag hinter jedem anderen; dann der bisherige Weg.
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 1);
        views.jobs(jobs);

        views.prefetch(CALLER, "wiki", List.of("100"));

        assertThat(prefetchJobs(jobs)).isEmpty();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (views.cached(CALLER, "wiki", "100").isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(views.cached(CALLER, "wiki", "100")).isPresent();
    }

    @Test
    void asAJobAnUnreachableSourceFailsThePartWithItsReason() {
        // Abnahme PR 4: kein bewusstes Auslassen, sondern rot mit Grund.
        List<com.aresstack.spaceharvester.application.snapshot.PageSnapshot> pages = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 300; i < 310; i++) {
            pages.add(page("wiki", "DEV", String.valueOf(i), null));
            ids.add(String.valueOf(i));
        }
        cache.publish("wiki", "DEV", confirmed("g3"), pages);
        source.unreachable = true;
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), held::add,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)), CLOCK);
        views.jobs(jobs);

        views.prefetch(CALLER, "wiki", ids);
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }

        com.aresstack.spaceharvester.application.jobs.JobStatus part = prefetchJobs(jobs).getFirst();
        assertThat(part.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.FAILED);
        assertThat(part.error().code()).isEqualTo(ReadingViews.SOURCE_UNAVAILABLE);
        assertThat(source.attempts.get()).as("danach keine weitere Seite").isLessThanOrEqualTo(2);
    }

    @Test
    void includedContentNeedsTheWholeSourceAndIsFetchedEveryTime() {
        ReadingView restricted = views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "101",
                false);

        assertThat(restricted.status()).isEqualTo(ReadingView.Status.RESTRICTED);
        assertThat(restricted.html()).isEmpty();
        assertThat(restricted.foreign().macros()).containsExactly("include", "jira");
        assertThat(source.reads).isZero();

        ReadingViews sourceWide = views(new AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
                return AccessDecision.ALLOW;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.ALLOW;
            }
        });
        assertThat(sourceWide.view(CALLER, "wiki", "101", false).status()).isEqualTo(ReadingView.Status.AVAILABLE);
        assertThat(sourceWide.view(CALLER, "wiki", "101", false).stored()).isFalse();
        assertThat(source.reads).isEqualTo(2);
    }

    @Test
    void anUnreachableSourceServesTheStoredViewAndAnotherVersionIsNeverShown() {
        ReadingViews views = views((context, scope, kind) -> AccessDecision.ALLOW);
        views.view(CALLER, "wiki", "100", false);
        source.unreachable = true;
        // Die abgelegte Ansicht gehört zur geprüften Payload; ohne erneuten Abruf.
        assertThat(views.view(CALLER, "wiki", "100", false).stored()).isTrue();

        enrichments.removeAll(new PageKey("wiki", "100"));
        // Im Speicher liegt sie noch zur selben Payload; ohne Speicher und Ablage scheitert es.
        assertThat(views.view(CALLER, "wiki", "100", false).stored()).isTrue();
        assertThatThrownBy(() -> views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "100",
                false)).isInstanceOf(SourceAccessException.class);

        source.unreachable = false;
        source.version = "4";
        // Nur zur geprüften Version 3; die Neuprüfung braucht die Quelle, ohne Anbindung scheitert sie. Der Speicher
        // hält nur die Ansicht zur Payload von Version 3 und zeigt nie Version 4.
        assertThat(views.view(CALLER, "wiki", "100", false).version()).isEqualTo("3");
        assertThatThrownBy(() -> views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "100",
                false)).isInstanceOf(RuntimeException.class);
        assertThat(enrichments.find(new PageKey("wiki", "100"))).isEmpty();
    }

    @Test
    void aSourceWithoutViewSaysSo() {
        source.supported = false;

        assertThat(views((context, scope, kind) -> AccessDecision.ALLOW).view(CALLER, "wiki", "100", false).status())
                .isEqualTo(ReadingView.Status.UNSUPPORTED);
    }

    private static final class Source implements HarvestSource {

        int reads;
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        boolean unreachable;
        boolean supported = true;
        String version = "3";

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "confluence";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("DEV", "Entwicklung", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, version,
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            if (!supported) {
                return HarvestSource.super.readingPage(pageId);
            }
            attempts.incrementAndGet();
            if (unreachable) {
                throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            reads++;
            return new ReadingPage(version, "<p>Seite " + pageId + "</p><ul><li>Kinderseite</li></ul>",
                    "https://wiki.example.org/", "# Seite " + pageId + "\n\n- Kinderseite",
                    List.of(new ViewImage("https://wiki.example.org/download/attachments/" + pageId + "/a.png",
                            "a.png", "att1", "image/png", "1")));
        }
    }
}
