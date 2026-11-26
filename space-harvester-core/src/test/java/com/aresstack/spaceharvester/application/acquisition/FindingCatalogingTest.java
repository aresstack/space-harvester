package com.aresstack.spaceharvester.application.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Katalogisieren der Funde (ADR 0016): nur technische Aufrufer, nur freigegebene Seiten, Kopfdaten live mit den
 * Zugangsdaten der Quelle, gesperrte und unveränderte Einträge ohne Anfrage.
 */
class FindingCatalogingTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** Space je Seite in der Quelle; fehlt eine, antwortet sie mit „nicht gefunden“. */
    private final Map<String, String> spaces = new ConcurrentHashMap<>();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    /** Spaces, die die Richtlinie ausschließt. */
    private final Set<String> excludedSpaces = ConcurrentHashMap.newKeySet();
    private final MemoryStore store = new MemoryStore();
    private final List<BackgroundFill.Gap> gaps = Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final FindingCataloging cataloging = new FindingCataloging(catalog(), new AccessGuard(
            (context, scope, kind) -> kind == AccessKind.METADATA && context.kind() != AccessContext.PrincipalKind.END_USER
                    && !excludedSpaces.contains(scope.spaceKey()) ? AccessDecision.ALLOW : AccessDecision.DENY),
            store, gaps::addAll, Clock.systemUTC(), id -> allowed.get());

    @AfterEach
    void close() {
        cataloging.close();
    }

    @Test
    void aDisabledSourceDoesNotCatalogueNewFindings() throws InterruptedException {
        page("1", "DEV", "3");
        allowed.set(false);
        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();
        assertThat(asked).isEmpty();
        assertThat(store.headers).isEmpty();
        assertThat(gaps).isEmpty();
    }

    @Test
    void anExcludedFindingIsNeitherCataloguedNorReportedToTheFill(@org.junit.jupiter.api.io.TempDir
                                                                  java.nio.file.Path dir) throws InterruptedException {
        // Ausschließen (Angelo 26.11.2026): kein Eintrag, keine Lücke; der Nachbar daneben kommt wie gewohnt.
        page("1", "DEV", "3");
        page("2", "DEV", "1");
        var exclusions = new com.aresstack.spaceharvester.application.sync.Exclusions(
                new com.aresstack.spaceharvester.persistence.files.FileExclusionMarks(dir), (sourceId, pageId) -> null,
                (sourceId, pageId) -> null);
        exclusions.exclude("wiki", "DEV", "1");
        cataloging.exclusions(exclusions);

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3"),
                new FindingCatalog.Finding("2", "1")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(store.headers).containsOnlyKeys("2");
        assertThat(gaps).extracting(gap -> gap.key().pageId()).containsExactly("2");
    }

    @Test
    void aNewFindingIsCataloguedOnceWithItsLiveHeaderAndReportedToTheFill() throws InterruptedException {
        page("1", "DEV", "3");

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();
        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).containsExactly("1");
        assertThat(store.headers.get("1").metadata().partitionKey()).isEqualTo("DEV");
        assertThat(gaps).extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("1:NOT_LOADED");
        assertThat(cataloging.counts().catalogued()).isEqualTo(1);
    }

    @Test
    void anEntryWithTheSameVersionCostsNoRequestAndANewerOneBecomesOutdated() throws InterruptedException {
        page("1", "DEV", "2");
        page("2", "DEV", "5");
        store.entries.put("1", new FindingCataloging.Entry(false, "2"));
        store.entries.put("2", new FindingCataloging.Entry(false, "4"));
        store.loaded.add("2");

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "2"),
                new FindingCatalog.Finding("2", "5")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).containsExactly("2");
        assertThat(gaps).extracting(gap -> gap.key().pageId() + ":" + gap.kind()).containsExactly("2:OUTDATED");
    }

    @Test
    void endUsersAndDelegatedCallersCatalogueNothing() throws InterruptedException {
        page("1", "DEV", "1");

        cataloging.offer(AccessContext.endUser("alice", "app", new UserCredential("wiki", "token")),
                "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        cataloging.offer(null, "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).isEmpty();
        assertThat(store.headers).isEmpty();
    }

    @Test
    void pagesThePolicyExcludesWithheldPagesAndVanishedPagesStayOut() throws InterruptedException {
        page("1", "SECRET", "1");
        page("2", "DEV", "1");
        excludedSpaces.add("SECRET");
        store.entries.put("2", new FindingCataloging.Entry(true, null));

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "1"), new FindingCatalog.Finding("2", "2"),
                new FindingCatalog.Finding("3", null)));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        // Gesperrt: keine Anfrage; ausgeschlossen und verschwunden: gefragt, aber nicht katalogisiert.
        assertThat(asked).containsExactlyInAnyOrder("1", "3");
        assertThat(store.headers).isEmpty();
        assertThat(gaps).isEmpty();
        assertThat(cataloging.counts().excluded()).isEqualTo(1);
        assertThat(cataloging.counts().failed()).isEqualTo(1);
    }

    @Test
    void afterClosingNothingIsAccepted() throws InterruptedException {
        page("1", "DEV", "1");
        cataloging.close();

        cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "1")));
        assertThat(cataloging.awaitIdle(WAIT)).isTrue();

        assertThat(asked).isEmpty();
    }

    /** Aufträge mit gehaltenem Ausführer, alle Plätze belegt: Ein Hintergrundteil wartet. */
    private static com.aresstack.spaceharvester.application.jobs.Jobs busyJobs(java.util.Queue<Runnable> held,
                                                                             int slots) {
        return busyJobs(new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), held, slots);
    }

    private static com.aresstack.spaceharvester.application.jobs.Jobs busyJobs(
            com.aresstack.spaceharvester.application.jobs.JobStore store, java.util.Queue<Runnable> held, int slots) {
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs.Jobs(
                store, held::add,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(slots, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        for (int i = 0; i < slots; i++) {
            jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "andere", null, "app",
                    com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
                    }));
        }
        return jobs;
    }

    /** Wartet, bis die Bedingung gilt; Teile legt der Arbeiter nach dem Bündelungsabstand an. */
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Bedingung nicht eingetreten");
            }
            Thread.sleep(20);
        }
    }

    /**
     * Ob genau ein anderer Teil als {@code first} wartet, aus einer einzigen Momentaufnahme: Zwei Abfragen nacheinander
     * sähen zwischen dem Abbrechen des alten und dem Anlegen des neuen Teils eine leere Liste (CI #1112).
     */
    private static boolean onlyOtherQueued(com.aresstack.spaceharvester.application.background.BackgroundJobs background,
                                           String first) {
        List<com.aresstack.spaceharvester.application.jobs.JobStatus> now = queued(background);
        return now.size() == 1 && !now.getFirst().id().equals(first);
    }

    private static List<com.aresstack.spaceharvester.application.jobs.JobStatus> queued(
            com.aresstack.spaceharvester.application.background.BackgroundJobs background) {
        return background.parts(FindingCataloging.KIND).stream().filter(part -> part.state()
                == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED).toList();
    }

    private static void runAll(java.util.Queue<Runnable> held) {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    private static List<FindingCatalog.Finding> findings(int from, int to) {
        List<FindingCatalog.Finding> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(new FindingCatalog.Finding("p" + i, "1"));
        }
        return list;
    }

    @Test
    void queuedFindingsAreSkippedWhenAcquisitionIsDisabledBeforeExecution() throws Exception {
        page("1", "DEV", "3");
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        var jobs = busyJobs(held, 2);
        var background = new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
            await(() -> queued(background).size() == 1);
            allowed.set(false);
            runAll(held);
            assertThat(asked).isEmpty();
            assertThat(store.headers).isEmpty();
            assertThat(gaps).isEmpty();
            assertThat(queued(background)).isEmpty();
        } finally {
            jobs.close();
        }
    }

    @Test
    void asAJobTheFindingsOfASourceAreOneVisiblePartAndANewOfferReplacesTheWaitingOne() throws Exception {
        // Fundstelle 14, Abnahme PR 4/#1082: sichtbar, höchstens ein wartender Teil je Quelle, ohne Neues kein Teil.
        page("1", "DEV", "3");
        page("2", "DEV", "7");
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 2);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            assertThat(cataloging.accept(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3"))))
                    .containsExactly("1");
            await(() -> queued(background).size() == 1);
            String first = queued(background).getFirst().id();

            cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("2", "7")));
            await(() -> background.parts(FindingCataloging.KIND).size() == 2 && queued(background).size() == 1
                    && !queued(background).getFirst().id().equals(first));
            // Erst der Ersatz, dann der alte abgebrochen; der Ersatz nimmt dessen Funde hinter den neuen mit.
            assertThat(background.part(first).orElseThrow().state())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED);
            com.aresstack.spaceharvester.application.jobs.JobStatus waiting = queued(background).getFirst();
            assertThat(waiting.parameter(FindingCataloging.PAGES)).isEqualTo("2\t7\n1\t3");
            assertThat(waiting.parameter(FindingCataloging.FINDER)).isEqualTo("app");
            assertThat(waiting.parameter(FindingCataloging.CHANGE_TRIGGER)).isEqualTo("SEARCH");

            // Dieselbe Suche neu sortiert: alles angenommen, weil der wartende Teil es schon hat; kein neuer Teil.
            assertThat(cataloging.accept(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3"),
                    new FindingCatalog.Finding("2", "7")))).containsExactly("1", "2");
            Thread.sleep(600);
            assertThat(background.parts(FindingCataloging.KIND)).hasSize(2);
            assertThat(asked).as("noch nichts gefragt").isEmpty();

            runAll(held);

            assertThat(asked).containsExactlyInAnyOrder("1", "2");
            assertThat(store.headers).containsOnlyKeys("1", "2");
            assertThat(gaps).extracting(gap -> gap.key().pageId()).containsExactlyInAnyOrder("1", "2");
            com.aresstack.spaceharvester.application.jobs.JobStatus done = background.part(waiting.id())
                    .orElseThrow();
            assertThat(done.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
            assertThat(done.progress().written()).isEqualTo(2);

            // Eben geprüft: Ein neues Angebot ist angenommen und legt vor RECHECK keinen Teil an.
            assertThat(cataloging.accept(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3"))))
                    .containsExactly("1");
            Thread.sleep(600);
            assertThat(background.parts(FindingCataloging.KIND)).hasSize(2);
        } finally {
            jobs.close();
        }
    }

    @Test
    void aFullPartAcceptsOnlyWhatFitsAndLosesNothingAccepted() throws Exception {
        // Abnahme #1082, Punkt 1: über 1000 fällt kein angenommener Fund weg; der Rest bleibt unangenommen.
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 2);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            int capacity = FindingCataloging.CAPACITY;
            assertThat(cataloging.accept(APP, "wiki", findings(0, capacity - 2))).hasSize(capacity - 2);
            await(() -> queued(background).size() == 1);
            String first = queued(background).getFirst().id();

            Set<String> taken = cataloging.accept(APP, "wiki", findings(capacity - 2, capacity + 3));
            assertThat(taken).containsExactly("p" + (capacity - 2), "p" + (capacity - 1));
            await(() -> onlyOtherQueued(background, first));

            String pages = queued(background).getFirst().parameter(FindingCataloging.PAGES);
            List<String> ids = pages.lines().map(line -> line.substring(0, line.indexOf('\t'))).toList();
            assertThat(ids).hasSize(capacity).containsAll(findings(0, capacity).stream()
                    .map(FindingCatalog.Finding::pageId).toList());

            // Voll: nichts Neues angenommen, schon Angenommenes weiter angenommen.
            assertThat(cataloging.accept(APP, "wiki", findings(capacity, capacity + 3))).isEmpty();
            assertThat(cataloging.accept(APP, "wiki", findings(0, 1))).containsExactly("p0");
        } finally {
            jobs.close();
        }
    }

    @Test
    void aFailedStartKeepsTheWaitingPartAndTheAcceptedFindings() throws Exception {
        // Abnahme #1082, Punkt 3: erst der Ersatz, dann der alte; scheitert der Start, bleibt der alte stehen.
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicBoolean refuse = new java.util.concurrent.atomic.AtomicBoolean();
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore refusing =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore() {
                    @Override
                    public void save(com.aresstack.spaceharvester.application.jobs.JobStatus status) {
                        if (refuse.get() && find(status.id()).isEmpty()) {
                            throw new IllegalStateException("Speicher voll");
                        }
                        super.save(status);
                    }
                };
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(refusing, held, 2);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            cataloging.offer(APP, "wiki", findings(0, 1));
            await(() -> queued(background).size() == 1);
            String first = queued(background).getFirst().id();
            refuse.set(true);

            assertThat(cataloging.accept(APP, "wiki", findings(1, 2))).containsExactly("p1");
            Thread.sleep(600);
            assertThat(background.part(first).orElseThrow().state())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.QUEUED);
            assertThat(background.parts(FindingCataloging.KIND)).hasSize(1);

            // Wieder möglich: Das nächste Angebot nimmt die angenommenen Funde mit.
            refuse.set(false);
            cataloging.offer(APP, "wiki", findings(2, 3));
            await(() -> onlyOtherQueued(background, first));
            assertThat(queued(background).getFirst().parameter(FindingCataloging.PAGES))
                    .isEqualTo("p1\t1\np2\t1\np0\t1");
            assertThat(background.part(first).orElseThrow().state())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED);
        } finally {
            jobs.close();
        }
    }

    @Test
    void aNewVersionOfACataloguedPageIsAcceptedAgain() throws Exception {
        // Abnahme #1082, Punkt 4: offene Teile zählen nach Seite und Fassung, erledigte Seiten nicht.
        page("1", "DEV", "4");
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 2);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
            await(() -> queued(background).size() == 1);
            String first = queued(background).getFirst().id();

            // Eine neue Fassung ersetzt die alte im wartenden Teil.
            assertThat(cataloging.accept(APP, "wiki", List.of(new FindingCatalog.Finding("1", "4"))))
                    .containsExactly("1");
            await(() -> onlyOtherQueued(background, first));
            String second = queued(background).getFirst().id();
            assertThat(queued(background).getFirst().parameter(FindingCataloging.PAGES)).isEqualTo("1\t4");

            runAll(held);
            await(() -> background.part(second).orElseThrow().state()
                    == com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);

            // Erledigt in Fassung 4: eine spätere Fassung 5 ist neu.
            page("1", "DEV", "5");
            assertThat(cataloging.accept(APP, "wiki", List.of(new FindingCatalog.Finding("1", "5"))))
                    .containsExactly("1");
            // Die Plätze sind jetzt frei: Der neue Teil startet gleich, statt zu warten.
            await(() -> background.parts(FindingCataloging.KIND).size() == 3);
            assertThat(background.parts(FindingCataloging.KIND).stream()
                    .filter(part -> !part.id().equals(first) && !part.id().equals(second)).findFirst().orElseThrow()
                    .parameter(FindingCataloging.PAGES)).isEqualTo("1\t5");
        } finally {
            jobs.close();
        }
    }

    @Test
    void withASingleSlotFindingsAreCataloguedTheFormerWay() throws Exception {
        // Abnahme #1077/#1080: Bei nur einem Platz liefe kein Hintergrundteil.
        page("1", "DEV", "3");
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = busyJobs(held, 1);
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        cataloging.background(background);
        try {
            cataloging.offer(APP, "wiki", List.of(new FindingCatalog.Finding("1", "3")));
            assertThat(cataloging.awaitIdle(WAIT)).isTrue();

            assertThat(background.parts(FindingCataloging.KIND)).isEmpty();
            assertThat(asked).containsExactly("1");
        } finally {
            jobs.close();
        }
    }

    private void page(String pageId, String spaceKey, String version) {
        spaces.put(pageId, spaceKey);
        versions.put(pageId, version);
    }

    private SourceCatalog catalog() {
        HarvestSource wiki = new HarvestSource() {
            @Override
            public String id() {
                return "wiki";
            }

            @Override
            public String type() {
                return "fake";
            }

            @Override
            public String title() {
                return "Wiki";
            }

            @Override
            public List<Partition> partitions() {
                return List.of();
            }

            @Override
            public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
                return new PageMetadataBatch(List.of(), null);
            }

            @Override
            public PageMetadata pageMetadata(String pageId) {
                asked.add(pageId);
                String spaceKey = spaces.get(pageId);
                if (spaceKey == null) {
                    throw new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "weg", null);
                }
                return new PageMetadata(pageId, "fake:" + pageId, "Seite " + pageId, spaceKey, null,
                        versions.get(pageId), null, null, new TreeMap<>());
            }
        };
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(wiki);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return wiki.id().equals(sourceId) ? Optional.of(wiki) : Optional.empty();
            }
        };
    }

    /** Ein Katalog im Speicher mit derselben Regel wie der Lucene-Cache. */
    private static final class MemoryStore implements FindingCataloging.Store {

        final Map<String, FindingCataloging.Entry> entries = new ConcurrentHashMap<>();
        final Map<String, PageHeader> headers = new ConcurrentHashMap<>();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<FindingCataloging.Entry> entry(PageKey key) {
            return Optional.ofNullable(entries.get(key.pageId()));
        }

        @Override
        public FindingCataloging.Outcome catalog(PageHeader header) {
            String pageId = header.key().pageId();
            FindingCataloging.Entry entry = entries.get(pageId);
            if (entry != null && entry.withheld()) {
                return FindingCataloging.Outcome.WITHHELD;
            }
            if (entry != null && header.revision() != null && header.revision().equals(entry.revision())) {
                return FindingCataloging.Outcome.UNCHANGED;
            }
            entries.put(pageId, new FindingCataloging.Entry(false, header.revision()));
            headers.put(pageId, header);
            return loaded.contains(pageId) ? FindingCataloging.Outcome.OUTDATED : FindingCataloging.Outcome.NOT_LOADED;
        }
    }
}
