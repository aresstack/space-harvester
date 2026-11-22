package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.services.AnalysisService;
import com.aresstack.spaceharvester.application.background.services.MissingPages;
import com.aresstack.spaceharvester.application.background.services.ServicePlan;
import com.aresstack.spaceharvester.application.background.services.ServicePlanner;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.background.services.ServiceStart;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.Partition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * „Wissensgraph erzeugen“ (W5): der Abschlussteil je Space hinter Aussagenanalyse und Bildbeschreibung, seine Zählung,
 * die Meldung an den Graph und die Eigentümer-Regel nach einem Neustart. Alle Daten sind erfunden: Quelle „wiki“,
 * Spaces „DEV“ und „OPS“, Seiten „plan“, „ziel“, „start“ und „nachtlauf“.
 */
class KnowledgeGraphJobsTest {

    private static final String OWNER = "desktop";
    private static final AccessContext APP = AccessContext.technicalClient(OWNER);
    private static final AccessContext FOREIGN = AccessContext.technicalClient("andere-anwendung");

    private final InMemoryJobStore jobStore = new InMemoryJobStore();
    private final Clock clock = new Ticking();
    private final InMemoryKnowledgeGraphStore parserStore = new InMemoryKnowledgeGraphStore();
    private final InMemoryKnowledgeGraphStore modelStore = new InMemoryKnowledgeGraphStore();
    private final AtomicReference<AnalyzerChoice> setting = new AtomicReference<>(AnalyzerChoice.PARSER);
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Set<PageKey> released = new HashSet<>();
    private final StatementAnalyzer model = new StatementAnalyzer() {
        @Override
        public String id() {
            return ModelStatementAnalyzer.ID;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled) {
            return parser.analyze(documentId, text, cancelled);
        }
    };
    private final Set<String> withoutText = new HashSet<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> withoutText.contains(snapshot.key().pageId()) ? Optional.empty()
                    : Optional.of(StatementAnalysisTest.text(snapshot)),
            new AnalyzerStores(parserStore, modelStore, setting::get), access, () -> parser, clock, null, null,
            () -> model, released::contains);
    private final Map<String, String> spaceOf = new LinkedHashMap<>(Map.of("plan", "DEV", "ziel", "DEV",
            "start", "DEV", "nachtlauf", "OPS"));
    private final Map<String, HoldingState> holding = new HashMap<>();
    /** Was lief, in Reihenfolge: „statements:Seite“, „images:Space“, „graph“. */
    private final List<String> ran = new CopyOnWriteArrayList<>();
    /** Seiten ohne Bildbeschreibung, je Space. */
    private final Map<String, Set<String>> imagesMissing = new HashMap<>();

    private record Service(Jobs jobs, BackgroundJobs background, StatementAnalysisJobs analyses,
                           KnowledgeGraphJobs graph, ServicePlanner planner) {
    }

    private Service service(Executor executor) {
        Jobs jobs = new Jobs(jobStore, executor, new JobLimits(4, 10, Duration.ofHours(1)), clock);
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        StatementAnalysisJobs analyses = new StatementAnalysisJobs(jobs, new Discovery(), (caller, sourceId, pageId) -> {
            ran.add("statements:" + pageId);
            return page(pageId);
        }, analysis, access, OWNER);
        analyses.background(background, APP);
        analyses.local(new StatementAnalysisJobs.LocalPages() {
            @Override
            public Map<PageKey, Holdings.Located> locate(AccessContext caller, PageSet pages) {
                Map<PageKey, Holdings.Located> located = new LinkedHashMap<>();
                pages.asMap().forEach((sourceId, ids) -> ids.forEach(id -> located.put(new PageKey(sourceId, id),
                        new Holdings.Located(spaceOf.get(id), holding.getOrDefault(id, HoldingState.LOCAL)))));
                return located;
            }

            @Override
            public Optional<PageSnapshot> stored(PageKey key) {
                return Optional.of(page(key.pageId()));
            }
        });
        background.register(new BackgroundKind("images", part -> JobTask.blocking(context ->
                ran.add("images:" + part.partitionKey()))));
        KnowledgeGraphJobs graph = new KnowledgeGraphJobs(analyses, () -> ran.add("graph"));
        graph.background(background, APP);
        ServicePlanner planner = new ServicePlanner(background, (caller, set) -> perSpace(set.asMap(), 0));
        planner.register(new AnalysisService("statements", "Aussagenanalyse", () -> ServiceStart.MANUAL, List.of(),
                StatementAnalysisJobs.KIND, (caller, set) -> {
                    StatementAnalysisJobs.Missing missing = analyses.missing(caller, set);
                    return perSpace(missing.pages().asMap(), missing.toLoad());
                }, analyses::plannerParameters));
        planner.register(new AnalysisService("images", "Bilder beschreiben", () -> ServiceStart.MANUAL, List.of(),
                "images", (caller, set) -> {
                    Map<MissingPages.Scope, Set<String>> pages = new LinkedHashMap<>();
                    imagesMissing.forEach((space, ids) -> pages.put(new MissingPages.Scope("wiki", space), ids));
                    return new MissingPages(pages, 0);
                }));
        planner.register(new AnalysisService(KnowledgeGraphJobs.KIND, "Wissensgraph", () -> ServiceStart.MANUAL,
                List.of("statements", "images"), KnowledgeGraphJobs.KIND, AnalysisService.Missing.NOTHING,
                graph::plannerParameters));
        return new Service(jobs, background, analyses, graph, planner);
    }

    private MissingPages perSpace(Map<String, Set<String>> pages, int toLoad) {
        Map<MissingPages.Scope, Set<String>> grouped = new LinkedHashMap<>();
        pages.forEach((sourceId, ids) -> ids.forEach(id -> grouped.computeIfAbsent(new MissingPages.Scope(sourceId,
                spaceOf.get(id)), ignored -> new TreeSet<>()).add(id)));
        return new MissingPages(grouped, 0, toLoad);
    }

    private static PageSet pages(String... ids) {
        return PageSet.of(Map.of("wiki", Set.of(ids)));
    }

    private PageSnapshot page(String pageId) {
        return EnrichmentTestPages.text("wiki", pageId, "1",
                "Der Sachbearbeiter " + pageId + " übermittelt dem Vorgesetzten den Beleg.");
    }

    private static JobStatus part(Jobs jobs, BackgroundGroup group, String kind, String space) {
        return group.parts().stream().filter(status -> kind.equals(status.parameter(BackgroundJobs.KIND))
                && space.equals(status.partitionKey())).map(status -> jobs.status(status.id())).findFirst()
                .orElseThrow();
    }

    private static List<JobEvent> coverage(Jobs jobs, JobStatus part) {
        return jobs.events(part.id()).stream().filter(event -> KnowledgeGraphJobs.COVERAGE.equals(event.code()))
                .toList();
    }

    @Test
    void statementsAndImagesRunBeforeTheClosingPartOfTheSameSpace() {
        imagesMissing.put("DEV", Set.of("plan"));
        Steps steps = new Steps();
        Service service = service(steps);

        BackgroundGroup group = service.planner().start(APP, KnowledgeGraphJobs.KIND,
                pages("plan", "ziel", "nachtlauf")).group().orElseThrow();
        steps.runAll();

        assertThat(group.kind()).isEqualTo(KnowledgeGraphJobs.KIND);
        assertThat(group.title()).isEqualTo("Wissensgraph");
        JobStatus dev = part(service.jobs(), group, KnowledgeGraphJobs.KIND, "DEV");
        JobStatus ops = part(service.jobs(), group, KnowledgeGraphJobs.KIND, "OPS");
        assertThat(dev.parameter(Jobs.AFTER).split(",")).containsExactlyInAnyOrder(
                part(service.jobs(), group, StatementAnalysisJobs.KIND, "DEV").id(),
                part(service.jobs(), group, "images", "DEV").id());
        assertThat(ops.parameter(Jobs.AFTER)).isEqualTo(
                part(service.jobs(), group, StatementAnalysisJobs.KIND, "OPS").id());
        assertThat(ran.indexOf("graph")).as("der erste Abschlussteil nach allen Vorstufen seines Space")
                .isGreaterThan(ran.indexOf("images:DEV"));
        assertThat(ran.lastIndexOf("graph")).isGreaterThan(ran.indexOf("statements:nachtlauf"));
        assertThat(ran).filteredOn("graph"::equals).hasSize(2);
        assertThat(List.of(dev, ops)).allSatisfy(status -> {
            JobStatus done = service.jobs().status(status.id());
            assertThat(done.state()).isEqualTo(JobState.COMPLETED);
            assertThat(coverage(service.jobs(), done)).singleElement().satisfies(event ->
                    assertThat(event.severity()).as("alles aktuell nach der Aussagenanalyse")
                            .isEqualTo(JobEvent.Severity.INFO));
        });
        assertThat(coverage(service.jobs(), dev).get(0).message())
                .isEqualTo("2 von 2 Seiten mit aktueller Analyse");
    }

    @Test
    void theClosingPartCountsCoverageYellowAndTellsTheGraph() {
        analysis.analyze(APP, page("plan"), () -> false);
        holding.put("start", HoldingState.OUTDATED);
        withoutText.add("ziel");
        Steps steps = new Steps();
        Service service = service(steps);

        ServicePreview preview = service.planner().preview(APP, KnowledgeGraphJobs.KIND,
                pages("plan", "ziel", "start"));
        ServicePlan plan = service.planner().start(APP, KnowledgeGraphJobs.KIND, pages("plan", "ziel", "start"));
        steps.runAll();

        assertThat(preview.nothingMissing()).isTrue();
        assertThat(preview.notes()).isEqualTo(plan.notes())
                .containsExactly("„Aussagenanalyse“: 1 Seite erst laden – bleibt ausgelassen");
        BackgroundGroup group = plan.group().orElseThrow();
        assertThat(group.parts()).as("nur der Abschlussteil").hasSize(1);
        JobStatus closing = part(service.jobs(), group, KnowledgeGraphJobs.KIND, "DEV");
        assertThat(closing.state()).isEqualTo(JobState.COMPLETED);
        assertThat(closing.progress().processed()).isEqualTo(3);
        assertThat(coverage(service.jobs(), closing)).singleElement().satisfies(event -> {
            assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING);
            assertThat(event.message()).isEqualTo("1 von 3 Seiten mit aktueller Analyse, 1 erst laden, "
                    + "1 ohne Lesetext ausgelassen");
        });
        assertThat(ran).containsExactly("graph");
    }

    @Test
    void withTheModelAnalyzerPagesWithoutReleaseAreSkippedAndNeverSentToTheService() {
        setting.set(AnalyzerChoice.MODEL);
        released.add(new PageKey("wiki", "plan"));
        Service service = service(new Steps());

        ServicePreview preview = service.planner().preview(APP, KnowledgeGraphJobs.KIND, pages("plan", "ziel"));
        StatementAnalysisJobs.Coverage coverage = service.analyses().coverage(APP, pages("plan", "ziel"),
                AnalyzerChoice.MODEL);

        assertThat(preview.stages()).filteredOn(stage -> stage.id().equals("statements")).singleElement()
                .satisfies(stage -> assertThat(stage.pages()).as("nur Seiten mit KI-Freigabe").isEqualTo(1));
        assertThat(coverage).isEqualTo(new StatementAnalysisJobs.Coverage(0, 1, 0, 0, 1));
        assertThat(KnowledgeGraphJobs.message(coverage))
                .isEqualTo("0 von 2 Seiten mit aktueller Analyse, 1 ohne aktuelle Analyse, "
                        + "1 ohne KI-Freigabe ausgelassen");
        assertThat(service.analyses().missing(APP, pages("plan", "ziel")).pages().pagesOf("wiki"))
                .containsExactly("plan");
    }

    @Test
    void afterARestartTheOwnClosingPartRunsAndAForeignOnePausesUntilItsOwnerResumes() {
        analysis.analyze(APP, page("plan"), () -> false);
        analysis.analyze(APP, page("nachtlauf"), () -> false);
        Service before = service(new Steps());
        BackgroundGroup own = before.planner().start(APP, KnowledgeGraphJobs.KIND, pages("plan")).group()
                .orElseThrow();
        BackgroundGroup foreign = before.planner().start(FOREIGN, KnowledgeGraphJobs.KIND, pages("nachtlauf"))
                .group().orElseThrow();
        assertThat(own.parts()).hasSize(1);
        assertThat(foreign.parts()).hasSize(1);
        assertThat(before.jobs().status(foreign.parts().get(0).id()).parameter(KnowledgeGraphJobs.OWNER))
                .isEqualTo(FOREIGN.jobOwner());

        Service after = service(Runnable::run);
        after.background().resumeInterrupted();

        assertThat(after.jobs().status(own.parts().get(0).id()).state()).isEqualTo(JobState.COMPLETED);
        JobStatus paused = after.jobs().status(foreign.parts().get(0).id());
        assertThat(paused.state()).isEqualTo(JobState.PAUSED);
        assertThat(paused.pauseReason()).isEqualTo(PauseReason.RESUME_BY_HAND);
        assertThat(after.jobs().events(paused.id())).filteredOn(event ->
                KnowledgeGraphJobs.RESUME_BY_HAND.equals(event.code())
                        && KnowledgeGraphJobs.COMPONENT.equals(event.component())).singleElement()
                .satisfies(event -> assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING));
        assertThat(ran).as("nur der eigene Teil meldet dem Graph").containsExactly("graph");

        after.background().resume(foreign.groupId());

        JobStatus again = after.jobs().status(foreign.parts().get(0).id());
        assertThat(again.state()).as("▶ setzt nie mit fremden Rechten fort").isEqualTo(JobState.PAUSED);
        assertThat(again.pauseReason()).isEqualTo(PauseReason.RESUME_BY_HAND);
        assertThat(coverage(after.jobs(), again)).isEmpty();
        assertThat(ran).containsExactly("graph");
    }

    /** Spaces und Kinder für die Aussagenanalyse; die Teile des Planers nennen ihre Seiten selbst. */
    private final class Discovery implements StatementAnalysisJobs.Discovery {

        @Override
        public PageNavigation.Children children(AccessContext caller, String sourceId, String spaceKey,
                                                String parentPageId, String cursor) {
            return new PageNavigation.Children(sourceId, spaceKey, parentPageId, List.of(), null);
        }

        @Override
        public List<Partition> spaces(AccessContext caller, String sourceId) {
            return new TreeSet<>(spaceOf.values()).stream().map(key -> new Partition(key, key, null)).toList();
        }

        @Override
        public String spaceKey(AccessContext caller, String sourceId, String pageId) {
            return spaceOf.get(pageId);
        }
    }

    /** Führt Arbeit erst auf Anforderung aus; ohne Aufruf nie, dann endet der Dienst vorher. */
    private static final class Steps implements Executor {
        private final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            pending.addLast(command);
        }

        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = pending.pollFirst();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    /** Jede Abfrage eine Millisekunde später. */
    private static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-11-22T06:00:00Z");

        @Override
        public synchronized Instant instant() {
            now = now.plusMillis(1);
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
