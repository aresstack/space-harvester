package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Aussagenanalyse als Hintergrundart {@value StatementAnalysisJobs#KIND} (Fahrplan D3): Gruppe je Space, Fortsetzen
 * nach einem Neustart, Eigentümer-Regel, festgelegter Analysator und {@link StatementAnalysisJobs#missing}. Alle Daten
 * sind erfunden.
 */
class StatementAnalysisBackgroundTest {

    private static final String OWNER = "desktop";
    private static final AccessContext APP = AccessContext.technicalClient(OWNER);

    private final InMemoryJobStore jobStore = new InMemoryJobStore();
    private final Clock clock = new Ticking();
    private final InMemoryKnowledgeGraphStore parserStore = new InMemoryKnowledgeGraphStore();
    private final InMemoryKnowledgeGraphStore modelStore = new InMemoryKnowledgeGraphStore();
    private final AtomicReference<AnalyzerChoice> setting = new AtomicReference<>(AnalyzerChoice.PARSER);
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Set<String> contentDenied = new HashSet<>();
    /** Seiten ohne Leserecht nur für einen Aufrufer ({@link AccessContext#jobOwner()}). */
    private final Map<String, Set<String>> deniedFor = new HashMap<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            kind == AccessKind.CONTENT && scope.pageId() != null && (contentDenied.contains(scope.pageId())
                    || deniedFor.getOrDefault(context.jobOwner(), Set.of()).contains(scope.pageId()))
                    ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> snapshot.key().pageId().startsWith("leer") ? Optional.empty()
                    : Optional.of(StatementAnalysisTest.text(snapshot)),
            new AnalyzerStores(parserStore, modelStore, setting::get), access, () -> parser, clock);
    private final Tree tree = new Tree();
    private final Map<String, String> texts = new HashMap<>();
    /** Läuft bei jedem Abruf einer Seite, etwa um einen Teil mitten im Lauf anzuhalten. */
    private java.util.function.Consumer<String> onFetch = pageId -> { };
    /** Wie oft der Auftrag eine Seite abgerufen hat. */
    private final java.util.concurrent.atomic.AtomicInteger fetched = new java.util.concurrent.atomic.AtomicInteger();

    /** Ein Dienst: Aufträge, Hintergrund und die Aussagenanalyse darauf. */
    private record Service(Jobs jobs, BackgroundJobs background, StatementAnalysisJobs analyses) {
    }

    private Service service(Executor executor) {
        Jobs jobs = new Jobs(jobStore, executor, new JobLimits(4, 10, Duration.ofHours(1)), clock);
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        StatementAnalysisJobs analyses = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> {
                    fetched.incrementAndGet();
                    onFetch.accept(pageId);
                    return page(sourceId, pageId);
                }, analysis, access, OWNER);
        analyses.background(background, APP);
        return new Service(jobs, background, analyses);
    }

    @Test
    void aSinglePageStaysANormalJobAndASourceRunIsAGroupWithOnePartPerSpace() {
        tree.space("DEV", "1", "2");
        tree.space("OPS", "3");
        Service service = service(Runnable::run);

        JobStatus single = service.analyses().start(APP, new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV",
                "1"), StatementAnalysisJobs.Options.DEFAULT);
        JobStatus first = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Source("wiki"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(single.type()).isEqualTo(JobType.STATEMENT_ANALYSIS);
        assertThat(StatementAnalysisJobs.groupOf(single)).isEmpty();
        assertThat(StatementAnalysisJobs.isAnalysis(single)).isTrue();
        assertThat(single.parameters()).as("ein Klick wartet nie hinter Hintergrund (#1055)")
                .containsEntry(Jobs.INTERACTIVE, "true");
        assertThat(service.jobs().status(first.id()).parameters()).doesNotContainKey(Jobs.INTERACTIVE);
        String groupId = StatementAnalysisJobs.groupOf(first).orElseThrow();
        List<JobStatus> parts = service.background().group(groupId).orElseThrow().parts();
        assertThat(parts).extracting(JobStatus::partitionKey).containsExactly("DEV", "OPS");
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.type()).isEqualTo(JobType.BACKGROUND);
            assertThat(StatementAnalysisJobs.isAnalysis(part)).isTrue();
            assertThat(part.parameter(BackgroundJobs.KIND)).isEqualTo(StatementAnalysisJobs.KIND);
            assertThat(part.parameter(StatementAnalysisJobs.ANALYZER)).isEqualTo("parser");
            assertThat(part.parameter(StatementAnalysisJobs.OWNER)).isEqualTo(APP.jobOwner());
            assertThat(service.jobs().status(part.id()).state()).isEqualTo(JobState.COMPLETED);
        });
        assertThat(parserStore.all()).hasSize(3);
    }

    @Test
    void aSubtreeRunIsAGroupOverThePageAndAllItsDescendants() {
        tree.space("DEV", "1", "9");
        tree.children("1", List.of("2", "3"));
        tree.children("3", List.of("4"));
        Service service = service(Runnable::run);

        JobStatus part = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV", "1"),
                StatementAnalysisJobs.Options.DEFAULT);

        JobStatus done = service.jobs().status(part.id());
        assertThat(done.type()).isEqualTo(JobType.BACKGROUND);
        assertThat(done.parameter(StatementAnalysisJobs.SCOPE)).isEqualTo("subtree");
        assertThat(done.parameter(StatementAnalysisJobs.ROOT)).isEqualTo("1");
        assertThat(done.progress()).isEqualTo(new JobProgress(4, 4, 4, 0));
        assertThat(parserStore.find(new PageKey("wiki", "9"))).isEmpty();
    }

    @Test
    void afterARestartTheGroupContinuesAndSkipsPagesAlreadyAnalysedSinceTheStart() {
        tree.space("DEV", "1", "2", "3");
        // Vor dem Start schon analysiert: „erneut analysieren“ muss diese Seiten trotzdem rechnen.
        for (String id : List.of("1", "2", "3")) {
            analysis.analyze(APP, page("wiki", id), () -> false);
        }
        Steps never = new Steps();
        Service before = service(never);
        JobStatus part = before.analyses().start(APP, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                new StatementAnalysisJobs.Options(true, 0));
        // Während des ersten Laufs fertig geworden, dann endet der Dienst.
        analysis.analyze(APP, page("wiki", "1"), () -> false);
        int calls = parser.calls.get();

        Service after = service(Runnable::run);
        List<JobStatus> resumed = after.background().resumeInterrupted();

        assertThat(resumed).extracting(JobStatus::id).containsExactly(part.id());
        JobStatus done = after.jobs().status(part.id());
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 2, 0));
        assertThat(parser.calls.get() - calls).as("nur die beiden offenen Seiten").isEqualTo(2);
    }

    @Test
    void aPartPausedByTheUserStaysPausedAfterTheRestart() {
        tree.space("DEV", "1");
        Service before = service(new Steps());
        JobStatus part = before.analyses().start(APP, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);
        before.jobs().pause(part.id(), OWNER);

        Service after = service(Runnable::run);

        assertThat(after.background().resumeInterrupted()).isEmpty();
        assertThat(after.jobs().status(part.id()).state()).isEqualTo(JobState.PAUSED);
        assertThat(parser.calls).hasValue(0);
    }

    @Test
    void afterARestartAPartOfAnotherOwnerStaysPausedYellowUntilSomeoneResumesItByHand() {
        tree.space("DEV", "1", "2");
        AccessContext other = AccessContext.technicalClient("andere-anwendung");
        Service before = service(new Steps());
        JobStatus part = before.analyses().start(other, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        Service after = service(Runnable::run);
        after.background().resumeInterrupted();

        JobStatus paused = after.jobs().status(part.id());
        assertThat(paused.state()).isEqualTo(JobState.PAUSED);
        assertThat(paused.pauseReason()).as("gelber Grund, nicht „Angehalten“").isEqualTo(PauseReason.RESUME_BY_HAND);
        assertThat(after.jobs().events(part.id())).anySatisfy(event ->
                assertThat(event.code()).isEqualTo(StatementAnalysisJobs.RESUME_BY_HAND));
        assertThat(parser.calls).hasValue(0);
        // Ein zweiter Neustart setzt ihn ebenfalls nicht von selbst fort.
        assertThat(service(Runnable::run).background().resumeInterrupted()).isEmpty();

        // Die allgemeine Auftragsaktion hat keinen Aufrufer: nie mit den Rechten des wiederhergestellten.
        after.background().resumeJob(part.id());
        assertThat(after.jobs().status(part.id()).pauseReason()).isEqualTo(PauseReason.RESUME_BY_HAND);
        assertThat(parser.calls).hasValue(0);

        // Von Hand fortgesetzt läuft er mit den Rechten des Fortsetzenden: Seite 2 darf nur er nicht lesen.
        AccessContext resumer = AccessContext.technicalClient("pruefer");
        deniedFor.put(resumer.jobOwner(), Set.of("2"));
        after.analyses().resume(resumer, StatementAnalysisJobs.groupOf(paused).orElseThrow());

        JobStatus done = after.jobs().status(part.id());
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).as("nur Seite 1; mit den Rechten des Wiederhergestellten wären es beide")
                .isEqualTo(new JobProgress(1, 1, 1, 0));
    }

    @Test
    void theAnalyzerChosenAtTheStartStaysAfterTheRestartEvenIfTheSettingChanged() {
        tree.space("DEV", "1", "2");
        Service before = service(new Steps());
        JobStatus part = before.analyses().start(APP, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                new StatementAnalysisJobs.Options(false, 0));
        assertThat(part.parameter(StatementAnalysisJobs.ANALYZER)).isEqualTo("parser");

        setting.set(AnalyzerChoice.MODEL);
        Service after = service(Runnable::run);
        after.background().resumeInterrupted();

        assertThat(after.jobs().status(part.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(parserStore.all()).hasSize(2);
        assertThat(modelStore.all()).as("nie in die andere Ablage").isEmpty();
    }

    @Test
    void anOwnChoiceOfTheAnalyzerWinsOverTheSetting() {
        tree.space("DEV", "1");
        setting.set(AnalyzerChoice.MODEL);
        Service service = service(new Steps());

        JobStatus part = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                new StatementAnalysisJobs.Options(false, 0, null, AnalyzerChoice.PARSER));

        assertThat(part.parameter(StatementAnalysisJobs.ANALYZER)).as("eigene Wahl vor der Einstellung")
                .isEqualTo("parser");
    }

    /** Der Dienst-Planer (D1) mit der Aussagenanalyse als Ziel; Space der Seiten aus dem Baum, ohne Rechte. */
    private com.aresstack.spaceharvester.application.background.services.ServicePlanner planner(Service service) {
        var planner = new com.aresstack.spaceharvester.application.background.services.ServicePlanner(
                service.background(), (caller, set) -> {
                    Map<com.aresstack.spaceharvester.application.background.services.MissingPages.Scope, Set<String>>
                            grouped = new LinkedHashMap<>();
                    set.asMap().forEach((sourceId, ids) -> ids.forEach(id -> grouped.computeIfAbsent(
                            new com.aresstack.spaceharvester.application.background.services.MissingPages.Scope(
                                    sourceId, tree.spaceOf.get(id)), key -> new java.util.TreeSet<>()).add(id)));
                    return new com.aresstack.spaceharvester.application.background.services.MissingPages(grouped, 0);
                });
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "statements", "Aussagenanalyse",
                () -> com.aresstack.spaceharvester.application.background.services.ServiceStart.MANUAL, List.of(),
                StatementAnalysisJobs.KIND,
                com.aresstack.spaceharvester.application.background.services.AnalysisService.Missing.NOTHING,
                service.analyses()::plannerParameters));
        return planner;
    }

    @Test
    void aPlannerPartAnalysesExactlyItsPagesWithTheRightsOfTheStarter() {
        tree.space("DEV", "1", "2", "3");
        AccessContext starter = AccessContext.technicalClient("pruefer");
        deniedFor.put(starter.jobOwner(), Set.of("2"));
        Service service = service(Runnable::run);

        BackgroundGroup group = planner(service).start(starter, "statements",
                PageSet.of(Map.of("wiki", Set.of("1", "2")))).group().orElseThrow();

        JobStatus part = service.jobs().status(group.parts().get(0).id());
        assertThat(group.kind()).isEqualTo(StatementAnalysisJobs.KIND);
        assertThat(part.state()).as("kein „von Hand fortsetzen“ beim ersten Lauf").isEqualTo(JobState.COMPLETED);
        assertThat(part.parameter(StatementAnalysisJobs.OWNER)).isEqualTo(starter.jobOwner());
        assertThat(part.parameter(StatementAnalysisJobs.ANALYZER)).isEqualTo("parser");
        assertThat(part.progress()).as("Seite 2 darf der Starter nicht lesen, Seite 3 war nicht bestellt")
                .isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(parserStore.find(new PageKey("wiki", "1"))).isPresent();
        assertThat(parserStore.find(new PageKey("wiki", "3"))).isEmpty();
        assertThat(fetched).hasValue(1);
    }

    @Test
    void afterARestartAPlannerPartKeepsItsAnalyzerAndTheOwnerRule() {
        tree.space("DEV", "1", "2");
        tree.space("OPS", "3");
        Service before = service(new Steps());
        BackgroundGroup own = planner(before).start(APP, "statements", PageSet.of(Map.of("wiki", Set.of("1", "2"))))
                .group().orElseThrow();
        BackgroundGroup foreign = planner(before).start(AccessContext.technicalClient("andere-anwendung"),
                "statements", PageSet.of(Map.of("wiki", Set.of("3")))).group().orElseThrow();

        setting.set(AnalyzerChoice.MODEL);
        Service after = service(Runnable::run);
        after.background().resumeInterrupted();

        assertThat(after.jobs().status(own.parts().get(0).id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(parserStore.all()).as("Analysator vom Start, nie die geänderte Einstellung").hasSize(2);
        assertThat(modelStore.all()).isEmpty();
        JobStatus paused = after.jobs().status(foreign.parts().get(0).id());
        assertThat(paused.state()).isEqualTo(JobState.PAUSED);
        assertThat(paused.pauseReason()).isEqualTo(PauseReason.RESUME_BY_HAND);
    }

    /** „Analysieren“ im Reiter rechnet die gezeigte Fassung als Klick, ohne die Seite erneut abzurufen. */
    @Test
    void theShownPageIsAnalysedAsItIsWithoutFetchingItAgain() {
        tree.space("DEV", "1");
        Service service = service(Runnable::run);
        texts.put("1", "Die Stelle prüft den gezeigten Antrag.");
        PageSnapshot shown = page("wiki", "1");
        texts.put("1", "Die Quelle hätte inzwischen einen anderen Text.");

        JobStatus job = service.analyses().startShown(APP, shown, new StatementAnalysisJobs.Options(true, 0));

        assertThat(service.jobs().status(job.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(job.parameters()).containsEntry(Jobs.INTERACTIVE, "true");
        assertThat(fetched).as("kein erneuter Abruf").hasValue(0);
        assertThat(analysis.read(APP, shown).state()).isEqualTo(PageStatements.State.CURRENT);
    }

    /** Die bestätigte Grenze gilt für die ganze Quelle, nicht je Space: jeder Teil scheitert vor der ersten Analyse. */
    @Test
    void theLimitOfASourceRunCountsAllItsSpacesWithoutWalkingForeignSpaces() {
        tree.space("DEV", "1", "2");
        tree.space("OPS", "3", "4");
        Service service = service(Runnable::run);

        JobStatus first = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Source("wiki"),
                new StatementAnalysisJobs.Options(false, 3));

        List<JobStatus> parts = service.background().group(StatementAnalysisJobs.groupOf(first).orElseThrow())
                .orElseThrow().parts();
        assertThat(service.jobs().status(parts.get(0).id()).state()).isEqualTo(JobState.COMPLETED);
        JobStatus refused = service.jobs().status(parts.get(1).id());
        assertThat(refused.state()).isEqualTo(JobState.FAILED);
        assertThat(refused.error().code()).as("2 + 2 Seiten über der Grenze 3")
                .isEqualTo(StatementAnalysisJobs.SCOPE_TOO_LARGE);
        assertThat(parserStore.all()).as("der zweite Teil analysiert nichts").hasSize(2);
        assertThat(tree.spaceListings).as("jeder Teil geht nur seinen eigenen Space ab (AT zu #1059)")
                .containsExactlyInAnyOrderEntriesOf(Map.of("DEV", 1, "OPS", 1));
    }

    /** Review zu #1059: Fortsetzen leert die Zähler eines Teils; seine ermittelte Zahl zählt für die Geschwister weiter. */
    @Test
    void aPartResumedAfterAPauseStillCountsForTheLimitOfItsSiblings() {
        tree.space("DEV", "1", "2");
        tree.space("OPS", "3", "4");
        Steps steps = new Steps();
        Service service = service(steps);
        JobStatus first = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Source("wiki"),
                new StatementAnalysisJobs.Options(false, 3));
        List<JobStatus> parts = service.background().group(StatementAnalysisJobs.groupOf(first).orElseThrow())
                .orElseThrow().parts();
        String dev = parts.get(0).id();
        String ops = parts.get(1).id();
        onFetch = pageId -> {
            if (pageId.equals("1")) {
                service.jobs().pause(dev, OWNER); // mitten im Lauf von DEV angehalten
            }
        };

        steps.runNext(); // DEV: 2 Seiten ≤ 3, analysiert Seite 1, hält an
        assertThat(service.jobs().status(dev).state()).isEqualTo(JobState.PAUSED);
        onFetch = pageId -> { };
        service.background().resumeJob(dev);
        assertThat(service.jobs().status(dev).progress().discovered()).as("Fortsetzen leert die Zähler").isZero();
        steps.runNext(); // OPS vor dem fortgesetzten DEV

        JobStatus refused = service.jobs().status(ops);
        assertThat(refused.state()).isEqualTo(JobState.FAILED);
        assertThat(refused.error().code()).as("2 + 2 Seiten über der Grenze 3, auch nach dem Fortsetzen von DEV")
                .isEqualTo(StatementAnalysisJobs.SCOPE_TOO_LARGE);
        assertThat(parserStore.find(new PageKey("wiki", "3"))).isEmpty();
    }

    @Test
    void underTheLimitEverySpaceIsAnalysedAndListedOnce() {
        tree.space("DEV", "1", "2");
        tree.space("OPS", "3", "4");
        Service service = service(Runnable::run);

        JobStatus first = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Source("wiki"),
                new StatementAnalysisJobs.Options(false, 4));

        assertThat(service.background().group(StatementAnalysisJobs.groupOf(first).orElseThrow()).orElseThrow()
                .completed()).isTrue();
        assertThat(parserStore.all()).hasSize(4);
        assertThat(tree.spaceListings).containsExactlyInAnyOrderEntriesOf(Map.of("DEV", 1, "OPS", 1));
    }

    /** Ist die Quelle beim Start nicht erreichbar, scheitert nicht der Start: ein normaler Auftrag endet unvollständig. */
    @Test
    void anUnreachableSourceAtTheStartBecomesANormalJobInsteadOfAnError() {
        tree.space("DEV", "1");
        tree.unreachable = new IllegalStateException("Quelle nicht erreichbar");
        Service service = service(Runnable::run);

        JobStatus job = service.analyses().start(APP, new StatementAnalysisJobs.Scope.Source("wiki"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(job.type()).isEqualTo(JobType.STATEMENT_ANALYSIS);
        assertThat(service.jobs().status(job.id()).state().terminal()).isTrue();
    }

    @Test
    void omittedSumsThePagesTheAiAnalyzerLeftOutOnPurpose() {
        List<com.aresstack.spaceharvester.application.jobs.JobEvent> events = List.of(
                event(StatementAnalysisJobs.MODEL_NOT_RELEASED, "3 Seiten ohne KI-Freigabe bewusst ausgelassen"),
                event(StatementAnalysisJobs.MODEL_NO_MODEL, "2 Seiten bewusst ausgelassen, weil kein Chat-Modell"),
                event(StatementAnalysisJobs.MODEL_COSTS, "Sprachmodell: 9 Seiten"),
                event(StatementAnalysisJobs.MODEL_NOT_RELEASED, "ohne Zahl"));

        assertThat(StatementAnalysisJobs.omitted(events)).isEqualTo(5);
    }

    private static com.aresstack.spaceharvester.application.jobs.JobEvent event(String code, String message) {
        return new com.aresstack.spaceharvester.application.jobs.JobEvent("job", 1, Instant.EPOCH,
                JobType.BACKGROUND, "wiki", "OPS", com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.INFO,
                com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, "statements", null, code, message,
                null, null, null, null);
    }

    @Test
    void missingNamesOnlyPagesTheServiceCanAnalyseNowAndCountsThoseToLoad() {
        // b: lokal ohne Analyse; c: Text geändert; h: nur vom anderen Analysator; a: aktuell; d/e: erst laden;
        // f: gesperrt; g, r, o: ohne Leserecht (r nur in der Quelle, o veraltet); leer: ohne Lesetext.
        for (String id : List.of("a", "c")) {
            analysis.analyze(APP, page("wiki", id), () -> false);
        }
        texts.put("c", "Die Stelle prüft den geänderten Antrag.");
        StatementAnalyzer other = new StatementAnalyzer() {
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
        // Eine Analyse des KI-Analysators in der Ablage des MaltParsers zählt für den MaltParser nicht.
        new StatementAnalysis(snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), parserStore, access,
                () -> other, clock).analyze(APP, page("wiki", "h"), () -> false);
        contentDenied.addAll(List.of("g", "r", "o"));
        Map<PageKey, Holdings.Located> located = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c", "h", "g", "leer")) {
            located.put(new PageKey("wiki", id), new Holdings.Located("DEV", HoldingState.LOCAL));
        }
        located.put(new PageKey("wiki", "d"), new Holdings.Located("DEV", HoldingState.OUTDATED));
        located.put(new PageKey("wiki", "e"), new Holdings.Located("DEV", HoldingState.REMOTE));
        located.put(new PageKey("wiki", "f"), new Holdings.Located("DEV", HoldingState.LOCKED));
        located.put(new PageKey("wiki", "r"), new Holdings.Located("DEV", HoldingState.REMOTE));
        located.put(new PageKey("wiki", "o"), new Holdings.Located("DEV", HoldingState.OUTDATED));
        Service service = service(Runnable::run);
        service.analyses().local(new StatementAnalysisJobs.LocalPages() {
            @Override
            public Map<PageKey, Holdings.Located> locate(AccessContext caller, PageSet pages) {
                Map<PageKey, Holdings.Located> inSet = new LinkedHashMap<>(located);
                inSet.keySet().removeIf(key -> !pages.contains(key));
                return inSet;
            }

            @Override
            public Optional<PageSnapshot> stored(PageKey key) {
                return Optional.of(page(key.sourceId(), key.pageId()));
            }
        });

        PageSet set = PageSet.of(Map.of("wiki", List.of("a", "b", "c", "d", "e", "f", "g", "h", "leer", "r", "o")));
        StatementAnalysisJobs.Missing missing = service.analyses().missing(APP, set);
        StatementAnalysisJobs.Coverage coverage = service.analyses().coverage(APP, set, AnalyzerChoice.PARSER);

        assertThat(missing.pages().pagesOf("wiki")).containsExactlyInAnyOrder("b", "c", "h");
        assertThat(missing.toLoad()).as("ohne Leserecht nicht unter „zu laden“").isEqualTo(2);
        assertThat(coverage).as("W5: dieselbe Regel wie missing()")
                .isEqualTo(new StatementAnalysisJobs.Coverage(1, 3, 2, 1, 0));
        assertThat(coverage.stale()).isEqualTo(missing.pages().size());
        assertThat(coverage.toLoad()).isEqualTo(missing.toLoad());
    }

    private PageSnapshot page(String sourceId, String pageId) {
        return EnrichmentTestPages.text(sourceId, pageId, "1", texts.getOrDefault(pageId,
                "Der Sachbearbeiter " + pageId + " übermittelt dem Vorgesetzten den Beleg."));
    }

    /** Quelle „wiki“ mit Spaces und Kindseiten, rechtegeprüft wie die Navigation. */
    private final class Tree implements StatementAnalysisJobs.Discovery {

        private final Map<String, List<String>> roots = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();
        private final Map<String, String> spaceOf = new HashMap<>();

        void space(String spaceKey, String... rootIds) {
            roots.put(spaceKey, List.of(rootIds));
            for (String id : rootIds) {
                spaceOf.put(id, spaceKey);
            }
        }

        void children(String parent, List<String> ids) {
            children.put(parent, ids);
            ids.forEach(id -> spaceOf.putIfAbsent(id, spaceOf.get(parent)));
        }

        /** Wie oft die Wurzelseiten eines Space abgefragt wurden. */
        final Map<String, Integer> spaceListings = new HashMap<>();

        @Override
        public PageNavigation.Children children(AccessContext caller, String sourceId, String spaceKey,
                                                String parentPageId, String cursor) {
            if (parentPageId == null) {
                spaceListings.merge(spaceKey, 1, Integer::sum);
            }
            String space = parentPageId == null ? spaceKey : spaceOf.get(parentPageId);
            access.require(caller, ResourceScope.space(sourceId, space), AccessKind.METADATA);
            List<String> all = parentPageId == null ? roots.getOrDefault(spaceKey, List.of())
                    : children.getOrDefault(parentPageId, List.of());
            return new PageNavigation.Children(sourceId, space, parentPageId, all.stream()
                    .map(id -> new PageNode(id, "Seite " + id, space, children.containsKey(id))).toList(), null);
        }

        RuntimeException unreachable;

        @Override
        public List<Partition> spaces(AccessContext caller, String sourceId) {
            if (unreachable != null) {
                throw unreachable;
            }
            return roots.keySet().stream().map(key -> new Partition(key, key, null)).toList();
        }

        @Override
        public String spaceKey(AccessContext caller, String sourceId, String pageId) {
            return spaceOf.get(pageId);
        }
    }

    /** Führt Arbeit erst auf Anforderung aus, hier nie: der Dienst endet vorher. */
    private static final class Steps implements Executor {
        private final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            pending.addLast(command);
        }

        /** Führt die älteste wartende Arbeit aus. */
        void runNext() {
            Runnable next;
            synchronized (this) {
                next = pending.pollFirst();
            }
            if (next != null) {
                next.run();
            }
        }
    }

    /** Jede Abfrage eine Millisekunde später. */
    private static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-11-21T18:00:00Z");

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
