package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.application.simplification.SimplificationStore;
import com.aresstack.spaceharvester.application.simplification.Simplifications;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Analyseauftrag für Seite, Unterzweig, Space und Quelle (#499 Abschnitt E). */
class StatementAnalysisJobsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final int BATCH = 100;

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final AtomicReference<StatementAnalyzer> configured = new AtomicReference<>(parser);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run,
            new JobLimits(2, 10, Duration.ofHours(1)), StatementAnalysisTest.clock());
    private final Tree tree = new Tree();
    private final Set<String> contentDenied = new HashSet<>();
    private final Set<String> spacesDenied = new HashSet<>();
    private final List<String> fetched = new ArrayList<>();
    private final Map<String, RuntimeException> fetchFailures = new HashMap<>();
    private Runnable afterFetch = () -> { };
    private boolean grantedPerSpaceOnly;
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> {
        if (grantedPerSpaceOnly && kind == AccessKind.CONTENT && scope.pageId() != null
                && scope.spaceKey() == null) {
            return AccessDecision.DENY; // Freigabe nur für einen bestimmten Space: ohne Space nicht entscheidbar
        }
        if (scope.spaceKey() != null && spacesDenied.contains(scope.spaceKey())) {
            return AccessDecision.DENY;
        }
        if (kind == AccessKind.CONTENT && scope.pageId() != null && contentDenied.contains(scope.pageId())) {
            return AccessDecision.DENY;
        }
        return AccessDecision.ALLOW;
    });
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> snapshot.key().pageId().equals("leer") ? Optional.empty()
                    : Optional.of(StatementAnalysisTest.text(snapshot)), store, access, configured::get,
            StatementAnalysisTest.clock());
    private final StatementAnalysisJobs batch = new StatementAnalysisJobs(jobs, tree, (caller, sourceId, pageId) -> {
        RuntimeException failure = fetchFailures.get(pageId);
        if (failure != null) {
            throw failure;
        }
        fetched.add(pageId);
        afterFetch.run();
        return page(sourceId, pageId);
    }, analysis, access, "desktop");

    @Test
    void aParentAnalysesItselfAndAllDescendantsAcrossCursorBatchesNotOnlyExpandedOnes() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", range(2, 251)); // 250 Kinder in drei Stapeln
        tree.children("17", List.of("400", "401"));

        JobStatus done = run(new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV", "1"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.type()).isEqualTo(JobType.STATEMENT_ANALYSIS);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.rootPageId()).isEqualTo("1");
        assertThat(done.progress()).isEqualTo(new JobProgress(253, 253, 253, 0));
        assertThat(store.all()).hasSize(253);
        assertThat(store.find(new PageKey("wiki", "401"))).isPresent();
        assertThat(tree.cursorsServed).as("alle Cursor-Stapel gelesen").isGreaterThanOrEqualTo(2);
    }

    @Test
    void onlyThisPageAnalysesTheParentWithoutChildren() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", List.of("2", "3"));

        JobStatus done = run(new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV", "1"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.progress()).isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(fetched).containsExactly("1");
        assertThat(tree.calls).isZero();
    }

    @Test
    void aLockedRootWithoutKnownSpaceIsNeitherCountedNorFetchedNorNamed() {
        tree.space("wiki", "DEV", "42");
        tree.children("42", List.of("43"));
        contentDenied.add("42");

        JobStatus single = run(new StatementAnalysisJobs.Scope.SinglePage("wiki", null, "42"),
                StatementAnalysisJobs.Options.DEFAULT);
        JobStatus subtree = run(new StatementAnalysisJobs.Scope.Subtree("wiki", null, "42"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(single.progress()).isEqualTo(JobProgress.NONE);
        assertThat(subtree.progress()).isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(fetched).containsExactly("43");
        assertThat(jobs.events(single.id())).noneMatch(event -> "PAGE_FAILED".equals(event.code()));
    }

    @Test
    void aRootWithoutKnownSpaceIsResolvedSoAGrantForThatSpaceApplies() {
        grantedPerSpaceOnly = true;
        tree.space("wiki", "DEV", "42");
        tree.children("42", List.of("43"));

        JobStatus single = run(new StatementAnalysisJobs.Scope.SinglePage("wiki", null, "42"),
                StatementAnalysisJobs.Options.DEFAULT);
        JobStatus subtree = run(new StatementAnalysisJobs.Scope.Subtree("wiki", null, "42"),
                new StatementAnalysisJobs.Options(true, 0));

        assertThat(single.state()).isEqualTo(JobState.COMPLETED);
        assertThat(single.progress()).isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(subtree.progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(fetched).containsExactly("42", "42", "43");
    }

    @Test
    void aFailedSpaceLookupThatExcludesTheRootMarksTheJobIncomplete() {
        grantedPerSpaceOnly = true;
        tree.space("wiki", "DEV", "42");
        tree.lookupFails = true;

        JobStatus single = run(new StatementAnalysisJobs.Scope.SinglePage("wiki", null, "42"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(single.state()).isEqualTo(JobState.FAILED);
        assertThat(single.error().code()).isEqualTo(StatementAnalysisJobs.INCOMPLETE);
        assertThat(fetched).isEmpty();
    }

    @Test
    void theLargestPossibleLimitMeansNoLimitAndNeverTruncatesSilently() {
        tree.space("wiki", "DEV", "1", "2");
        tree.children("1", List.of("3"));
        StatementAnalysisJobs.Scope space = new StatementAnalysisJobs.Scope.Space("wiki", "DEV");

        assertThat(batch.estimate(CALLER, space, Integer.MAX_VALUE)).satisfies(estimate -> {
            assertThat(estimate.pages()).isEqualTo(3);
            assertThat(estimate.exact()).isTrue();
        });
        JobStatus done = run(space, new StatementAnalysisJobs.Options(false, Integer.MAX_VALUE));
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 3, 0));
    }

    @Test
    void pagesWithoutReaderTextAreSkippedAndNamedSeparately() {
        tree.space("wiki", "DEV", "1", "leer");

        JobStatus done = run(new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.progress()).isEqualTo(new JobProgress(2, 2, 1, 0));
        assertThat(jobs.events(done.id())).anySatisfy(event ->
                assertThat(event.message()).contains("0 aktuell übersprungen, 1 ohne Lesetext"));
    }

    @Test
    void aRepeatedRunSkipsCurrentPagesAndReanalyseForcesThem() {
        tree.space("wiki", "DEV", "1", "2");
        StatementAnalysisJobs.Scope space = new StatementAnalysisJobs.Scope.Space("wiki", "DEV");
        run(space, StatementAnalysisJobs.Options.DEFAULT);
        int parsed = parser.calls.get();

        JobStatus again = run(space, StatementAnalysisJobs.Options.DEFAULT);
        assertThat(again.progress()).isEqualTo(new JobProgress(2, 2, 0, 0));
        assertThat(parser.calls).hasValue(parsed);

        JobStatus forced = run(space, new StatementAnalysisJobs.Options(true, 0));
        assertThat(forced.progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(parser.calls).hasValue(parsed + 2);
        assertThat(store.all()).as("keine Doppelten").hasSize(2);
    }

    @Test
    void cyclesAndDuplicatePageIdsCountOnce() {
        tree.space("wiki", "DEV", "1", "1");
        tree.children("1", List.of("2", "3"));
        tree.children("3", List.of("1", "2", "4"));

        JobStatus done = run(new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.progress().discovered()).isEqualTo(4);
        assertThat(fetched).containsExactly("1", "2", "3", "4");
    }

    @Test
    void lockedPagesAreNeitherLoadedNorCountedButTheirReadableChildrenAre() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", List.of("2", "secret"));
        tree.children("secret", List.of("5"));
        contentDenied.add("secret");

        JobStatus done = run(new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV", "1"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 3, 0));
        assertThat(fetched).containsExactly("1", "2", "5");
        assertThat(jobs.events(done.id())).noneMatch(event -> String.valueOf(event.message()).contains("secret"));
    }

    @Test
    void aSourceCoversAllReadableSpacesAndSkipsLockedOnesSilently() {
        tree.space("wiki", "DEV", "1");
        tree.space("wiki", "OPS", "2");
        tree.space("wiki", "HR", "3");
        spacesDenied.add("HR");

        JobStatus done = run(new StatementAnalysisJobs.Scope.Source("wiki"), StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(fetched).containsExactly("1", "2");
    }

    @Test
    void theEstimateStopsAtTheLimitAndALargerScopeFailsBeforeAnyAnalysis() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", range(2, 30));
        StatementAnalysisJobs.Scope scope = new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV", "1");

        StatementAnalysisJobs.Estimate estimate = batch.estimate(CALLER, scope, 10);
        assertThat(estimate.pages()).isEqualTo(10);
        assertThat(estimate.exceeded()).isTrue();
        assertThat(estimate.exact()).isFalse();
        assertThat(batch.estimate(CALLER, scope, 100)).satisfies(exact -> {
            assertThat(exact.pages()).isEqualTo(30);
            assertThat(exact.exact()).isTrue();
        });
        assertThat(fetched).as("die Schätzung lädt nichts").isEmpty();

        JobStatus refused = run(scope, new StatementAnalysisJobs.Options(false, 10));

        assertThat(refused.state()).isEqualTo(JobState.FAILED);
        assertThat(refused.error().code()).isEqualTo(StatementAnalysisJobs.SCOPE_TOO_LARGE);
        assertThat(fetched).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void aCancelKeepsFinishedPagesAndWritesNoPartialPage() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", range(2, 10));
        afterFetch = () -> {
            if (fetched.size() == 3) {
                jobs.list("desktop", JobType.STATEMENT_ANALYSIS).forEach(job -> jobs.cancel(job.id()));
            }
        };

        JobStatus done = run(new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV", "1"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.state()).isEqualTo(JobState.CANCELLED);
        assertThat(store.all()).as("zwei fertige Seiten, die dritte wurde abgebrochen").hasSize(2);
    }

    @Test
    void aFailingPageIsCountedAndTheRestContinues() {
        tree.space("wiki", "DEV", "1", "2", "3");
        fetchFailures.put("2", new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null));

        JobStatus done = run(new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
        assertThat(jobs.events(done.id())).anySatisfy(event ->
                assertThat(event.message()).contains("SourceAccessException"));
    }

    @Test
    void anUnreadableBranchIsReportedAsIncompleteNotSilentlyCut() {
        tree.space("wiki", "DEV", "1", "2");
        tree.failing.add("2");
        tree.children("2", List.of("3"));

        JobStatus done = run(new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo(StatementAnalysisJobs.INCOMPLETE);
        assertThat(store.all()).as("gefundene Seiten sind trotzdem analysiert").hasSize(2);
    }

    @Test
    void withoutAnalyzerNothingStartsAndAMissingModelFailsTheJob() {
        configured.set(null);
        assertThat(batch.available()).isFalse();
        assertThatThrownBy(() -> batch.start(CALLER, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT)).isInstanceOf(AnalyzerUnavailableException.class);

        configured.set(parser);
        parser.failure = new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_MISSING,
                "Kein Modell", null);
        tree.space("wiki", "DEV", "1", "2");
        JobStatus done = run(new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                StatementAnalysisJobs.Options.DEFAULT);

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo(StatementAnalysisJobs.ANALYZER_UNAVAILABLE);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void theStageSendsOnlyReleasedPagesToTheModelAndReportsItsCountsAsNumbersOnly() {
        List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        StepModel model = new StepModel() {
            @Override
            public String version() {
                return "fake:m1";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                inputs.add(input);
                String[] parts = input.strip().split(" ", 2);
                return parts[0] + "|T|" + parts[1] + "\n9|T|Fremd.\n";
            }
        };
        Simplifications stage = new Simplifications(model, new SimplificationStore(new InMemoryEnrichmentStore(),
                StatementAnalysisTest.clock()), page -> page.pageId().equals("1"));
        StatementAnalysis withStage = new StatementAnalysis(snapshot -> Optional.of(StatementAnalysisTest
                .text(snapshot)), store, access, configured::get, StatementAnalysisTest.clock(), stage);
        StatementAnalysisJobs jobsWithStage = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> page(sourceId, pageId), withStage, access, "desktop");
        tree.space("wiki", "DEV", "1", "2");
        StatementAnalysisJobs.Options options = new StatementAnalysisJobs.Options(false, 0,
                new StatementAnalysis.SimplificationRequest(SimplificationSettings.Mode.WHERE_NEEDED, 2));

        JobStatus done = jobs.status(jobsWithStage.start(CALLER, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                options).id());

        assertThat(done.progress()).isEqualTo(new JobProgress(2, 2, 2, 0));
        assertThat(inputs).hasSize(1).allSatisfy(input -> assertThat(input).contains("Sachbearbeiter 1")
                .doesNotContain("Sachbearbeiter 2"));
        assertThat(parser.stages).hasSize(1);
        assertThat(jobs.events(done.id())).anySatisfy(event -> assertThat(event.message()).isEqualTo(
                "Vorstufe „nur wo nötig“: 1 Seiten, 1 Sätze gewählt, 1 Abschnitte mit Modellaufruf, 0 Sätze aus der "
                        + "Ablage, 1 Antwortzeilen verworfen, 0 Sätze mit ergänzten Wörtern verworfen, 0 unvollständig, "
                        + "0 wegen verlorener Verneinung oder Modalverb verworfen; "
                        + "1 Seiten ohne KI-Freigabe und 0 Seiten ohne Modell ohne Vorstufe"));
        assertThat(jobs.events(done.id())).filteredOn(event -> event.code() != null
                        && event.code().startsWith("STAGE_"))
                .as("ohne KI-Freigabe einzeln und mit Code, getrennt von „kein Modell“").singleElement()
                .satisfies(event -> {
                    assertThat(event.code()).isEqualTo(StatementAnalysisJobs.STAGE_NOT_RELEASED);
                    assertThat(event.message()).isEqualTo("1 Seiten ohne KI-Freigabe, ohne Vorstufe analysiert");
                });

        // Fortsetzen oder zweiter Lauf: gleicher Text, Analysator und Stand der Vorstufe, also übersprungen
        int parsed = parser.calls.get();
        JobStatus again = jobs.status(jobsWithStage.start(CALLER, new StatementAnalysisJobs.Scope.Space("wiki",
                "DEV"), options).id());
        assertThat(inputs).hasSize(1);
        assertThat(parser.calls).hasValue(parsed);
        assertThat(jobs.events(again.id())).anySatisfy(event -> assertThat(event.message())
                .contains("2 aktuell übersprungen"));

        // „Neu erzwingen“ rechnet neu; die Sätze kommen aus der Ablage
        JobStatus forced = jobs.status(jobsWithStage.start(CALLER, new StatementAnalysisJobs.Scope.Space("wiki",
                "DEV"), new StatementAnalysisJobs.Options(true, 0, options.simplification())).id());
        assertThat(parser.calls).hasValue(parsed + 2);
        assertThat(inputs).hasSize(1);
        assertThat(jobs.events(forced.id())).anySatisfy(event -> assertThat(event.message())
                .contains("0 Abschnitte mit Modellaufruf, 1 Sätze aus der Ablage"));
    }

    @Test
    void theCheckJobSendsOnlyReleasedCurrentPagesToTheModelAndReportsNumbersOnly() {
        List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        StepModel model = new StepModel() {
            @Override
            public String version() {
                return "fake:m1";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                inputs.add(input);
                return "1|FALSCH\n";
            }
        };
        com.aresstack.spaceharvester.application.check.StatementChecks checks =
                new com.aresstack.spaceharvester.application.check.StatementChecks(model,
                        new com.aresstack.spaceharvester.application.check.StatementCheckStore(
                                new InMemoryEnrichmentStore(), StatementAnalysisTest.clock()),
                        page -> !page.pageId().equals("2"));
        StatementAnalysis withCheck = new StatementAnalysis(snapshot -> Optional.of(StatementAnalysisTest
                .text(snapshot)), store, access, configured::get, StatementAnalysisTest.clock(), null, checks);
        StatementAnalysisJobs checkJobs = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> page(sourceId, pageId), withCheck, access, "desktop");
        tree.space("wiki", "DEV", "1", "2", "3");
        // Seiten 1 und 2 analysiert, Seite 3 nicht; Seite 2 ohne KI-Freigabe
        run(new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV", "1"), StatementAnalysisJobs.Options.DEFAULT);
        run(new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV", "2"), StatementAnalysisJobs.Options.DEFAULT);
        assertThat(checkJobs.checkAvailable()).isTrue();

        JobStatus done = jobs.status(checkJobs.startCheck(CALLER, new StatementAnalysisJobs.Scope.Space("wiki", "DEV"),
                new StatementAnalysisJobs.CheckOptions(0,
                        com.aresstack.spaceharvester.application.check.CheckScope.ALL, 2)).id());

        assertThat(done.type()).isEqualTo(JobType.STATEMENT_CHECK);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).as("ermittelt, erledigt, geprüft, gescheitert")
                .isEqualTo(new JobProgress(3, 3, 1, 0));
        assertThat(inputs).isNotEmpty().allSatisfy(input -> assertThat(input).contains("Sachbearbeiter 1")
                .doesNotContain("Sachbearbeiter 2"));
        assertThat(store.find(new PageKey("wiki", "1")).orElseThrow().result().statements())
                .allSatisfy(statement -> assertThat(statement.check().verdict())
                        .isEqualTo(ParsedStatements.Verdict.FALSCH));
        assertThat(store.find(new PageKey("wiki", "2")).orElseThrow().result().statements())
                .allSatisfy(statement -> assertThat(statement.check()).isNull());
        assertThat(store.find(new PageKey("wiki", "3"))).as("nicht nebenbei analysiert").isEmpty();
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.CHECK_COSTS);
            assertThat(event.message()).startsWith("Prüfschritt „alle Aussagen“: 3 Seiten ermittelt, 1 ohne aktuelle "
                    + "Analyse; ").contains(" FALSCH, 0 ohne gültiges Urteil").doesNotContain("Sachbearbeiter");
        });
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.CHECK_NOT_RELEASED);
            assertThat(event.message()).isEqualTo("1 Seiten ohne KI-Freigabe nicht geprüft");
        });
    }

    /** AT zu #1059: „Prüfen“ einer Seite im Reiter ist ein Klick-Auftrag mit der gezeigten Fassung. */
    @Test
    void checkingTheShownPageIsAnInteractiveJobWithoutFetchingAndCountsWhatItLeftOut() {
        StepModel model = new StepModel() {
            @Override
            public String version() {
                return "fake:m1";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                return "1|STIMMT\n";
            }
        };
        com.aresstack.spaceharvester.application.check.StatementChecks checks =
                new com.aresstack.spaceharvester.application.check.StatementChecks(model,
                        new com.aresstack.spaceharvester.application.check.StatementCheckStore(
                                new InMemoryEnrichmentStore(), StatementAnalysisTest.clock()),
                        page -> !page.pageId().equals("2"));
        StatementAnalysis withCheck = new StatementAnalysis(snapshot -> Optional.of(StatementAnalysisTest
                .text(snapshot)), store, access, configured::get, StatementAnalysisTest.clock(), null, checks);
        List<String> checkFetched = new ArrayList<>();
        StatementAnalysisJobs checkJobs = new StatementAnalysisJobs(jobs, tree, (caller, sourceId, pageId) -> {
            checkFetched.add(pageId);
            return page(sourceId, pageId);
        }, withCheck, access, "desktop");
        tree.space("wiki", "DEV", "1", "2", "3");
        run(new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV", "1"), StatementAnalysisJobs.Options.DEFAULT);
        run(new StatementAnalysisJobs.Scope.SinglePage("wiki", "DEV", "2"), StatementAnalysisJobs.Options.DEFAULT);
        StatementAnalysisJobs.CheckOptions options = new StatementAnalysisJobs.CheckOptions(0,
                com.aresstack.spaceharvester.application.check.CheckScope.ALL, 2);

        JobStatus checked = jobs.status(checkJobs.startCheckShown(CALLER, page("wiki", "1"), options).id());
        JobStatus notReleased = jobs.status(checkJobs.startCheckShown(CALLER, page("wiki", "2"), options).id());
        JobStatus notCurrent = jobs.status(checkJobs.startCheckShown(CALLER, page("wiki", "3"), options).id());

        assertThat(checked.type()).isEqualTo(JobType.STATEMENT_CHECK);
        assertThat(checked.parameters()).as("ein Klick wartet nie hinter Hintergrund").containsEntry(Jobs.INTERACTIVE,
                "true");
        assertThat(checked.progress()).isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(checkFetched).as("die gezeigte Fassung, kein erneuter Abruf").isEmpty();
        assertThat(jobs.events(notReleased.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.CHECK_NOT_RELEASED);
            assertThat(event.message()).startsWith("1 ");
        });
        assertThat(notCurrent.progress().written()).isZero();
        assertThat(jobs.events(notCurrent.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.CHECK_NOT_CURRENT);
            assertThat(event.message()).isEqualTo("1 Seiten ohne aktuelle Analyse nicht geprüft");
        });
    }

    @Test
    void withoutAModelTheCheckJobDoesNotStart() {
        assertThat(batch.checkAvailable()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> batch.startCheck(CALLER,
                new StatementAnalysisJobs.Scope.Space("wiki", "DEV"), StatementAnalysisJobs.CheckOptions.of(0,
                        com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT)))
                .isInstanceOf(com.aresstack.spaceharvester.application.check.CheckUnavailableException.class);
    }

    @Test
    void theModelRunSkipsPagesWithoutReleaseYellowAndCountsServiceErrorsRed() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", List.of("2", "3"));
        Set<PageKey> released = new HashSet<>(Set.of(new PageKey("wiki", "1"), new PageKey("wiki", "3")));
        InMemoryKnowledgeGraphStore modelStore = new InMemoryKnowledgeGraphStore();
        FakeStepModel chat = new FakeStepModel(input -> {
            if (input.contains(" 3 ")) {
                throw new IllegalStateException("Dienst antwortet nicht");
            }
            return "{\"satz\": 1, \"aussagen\": [{\"verb\": \"übermitteln\", \"handelnder\": "
                    + "\"Der Sachbearbeiter\", \"betroffenes\": \"den Beleg\"}]}\n"
                    + "Hinweis außerhalb des Formats";
        });
        StatementAnalysis withModel = new StatementAnalysis(
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)),
                new AnalyzerStores(store, modelStore, () -> AnalyzerChoice.PARSER), access, configured::get,
                StatementAnalysisTest.clock(), null, null,
                () -> new ModelStatementAnalyzer(chat, ModelStatementAnalyzerTest.SENTENCES), released::contains);
        StatementAnalysisJobs modelJobs = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> page(sourceId, pageId), withModel, access, "desktop");

        JobStatus done = jobs.status(modelJobs.start(CALLER, new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV",
                "1"), new StatementAnalysisJobs.Options(false, 0, null, AnalyzerChoice.MODEL)).id());

        assertThat(done.progress()).isEqualTo(new JobProgress(3, 3, 1, 1));
        assertThat(modelStore.all()).extracting(analysis -> analysis.key().pageId()).containsExactly("1");
        assertThat(store.all()).as("die Ablage des MaltParsers bleibt unberührt").isEmpty();
        assertThat(parser.calls).hasValue(0);
        assertThat(chat.inputs).hasSize(2).noneMatch(input -> input.contains(" 2 "));
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.MODEL_NOT_RELEASED);
            assertThat(event.message()).isEqualTo("1 Seiten ohne KI-Freigabe bewusst ausgelassen");
        });
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.MODEL_COSTS);
            assertThat(event.message()).contains("1 Zeilen verworfen").doesNotContain("Sachbearbeiter");
        });
        assertThat(jobs.events(done.id())).anySatisfy(event -> assertThat(event.code()).isEqualTo("PAGE_FAILED"));
    }

    @Test
    void theModelRunWithoutChatModelSkipsEveryPageWithoutFailing() {
        tree.space("wiki", "DEV", "1");
        FakeStepModel chat = new FakeStepModel(input -> "");
        chat.available = false;
        StatementAnalysis withModel = new StatementAnalysis(
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)),
                new AnalyzerStores(store, new InMemoryKnowledgeGraphStore(), () -> AnalyzerChoice.PARSER), access,
                configured::get, StatementAnalysisTest.clock(), null, null,
                () -> new ModelStatementAnalyzer(chat, ModelStatementAnalyzerTest.SENTENCES), page -> true);
        StatementAnalysisJobs modelJobs = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> page(sourceId, pageId), withModel, access, "desktop");

        JobStatus done = jobs.status(modelJobs.start(CALLER, new StatementAnalysisJobs.Scope.SinglePage("wiki",
                "DEV", "1"), new StatementAnalysisJobs.Options(false, 0, null, AnalyzerChoice.MODEL)).id());

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(1, 1, 0, 0));
        assertThat(jobs.events(done.id())).anySatisfy(event ->
                assertThat(event.code()).isEqualTo(StatementAnalysisJobs.MODEL_NO_MODEL));
    }

    /**
     * W5 (Auflage 1): Die KI-Freigabe gilt unabhängig vom Modell. Ohne Chat-Modell zählen Seiten ohne Freigabe in
     * Vorschau ({@code coverage}, {@code notReleased}) und Planer ({@code missing}) als „ohne KI-Freigabe“, die übrigen
     * als offen; der Lauf nennt dieselben Zahlen (ohne Freigabe, ohne Modell).
     */
    @Test
    void withoutChatModelPagesWithoutReleaseCountAsNotReleasedInPreviewAndRunAlike() {
        tree.space("wiki", "DEV", "1");
        tree.children("1", List.of("2", "3"));
        FakeStepModel chat = new FakeStepModel(input -> "");
        chat.available = false;
        Set<PageKey> released = Set.of(new PageKey("wiki", "1"));
        StatementAnalysis withModel = new StatementAnalysis(
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)),
                new AnalyzerStores(store, new InMemoryKnowledgeGraphStore(), () -> AnalyzerChoice.MODEL), access,
                configured::get, StatementAnalysisTest.clock(), null, null,
                () -> new ModelStatementAnalyzer(chat, ModelStatementAnalyzerTest.SENTENCES), released::contains);
        StatementAnalysisJobs modelJobs = new StatementAnalysisJobs(jobs, tree,
                (caller, sourceId, pageId) -> page(sourceId, pageId), withModel, access, "desktop");
        modelJobs.local(new StatementAnalysisJobs.LocalPages() {
            @Override
            public Map<PageKey, com.aresstack.spaceharvester.application.holdings.Holdings.Located> locate(
                    AccessContext caller, PageSet pages) {
                Map<PageKey, com.aresstack.spaceharvester.application.holdings.Holdings.Located> located =
                        new java.util.LinkedHashMap<>();
                for (String id : List.of("1", "2", "3")) {
                    if (pages.contains("wiki", id)) {
                        located.put(new PageKey("wiki", id), new com.aresstack.spaceharvester.application.holdings
                                .Holdings.Located("DEV", com.aresstack.spaceharvester.application.holdings
                                .HoldingState.LOCAL));
                    }
                }
                return located;
            }

            @Override
            public Optional<PageSnapshot> stored(PageKey key) {
                return Optional.of(page(key.sourceId(), key.pageId()));
            }
        });
        PageSet set = PageSet.of(Map.of("wiki", List.of("1", "2", "3")));

        assertThat(withModel.modelReady()).isFalse();
        assertThat(modelJobs.coverage(CALLER, set, AnalyzerChoice.MODEL))
                .isEqualTo(new StatementAnalysisJobs.Coverage(0, 1, 0, 0, 2));
        assertThat(modelJobs.notReleased(CALLER, set).pagesOf("wiki")).containsExactlyInAnyOrder("2", "3");
        assertThat(modelJobs.missing(CALLER, set).pages().pagesOf("wiki")).containsExactly("1");

        JobStatus done = jobs.status(modelJobs.start(CALLER, new StatementAnalysisJobs.Scope.Subtree("wiki", "DEV",
                "1"), new StatementAnalysisJobs.Options(false, 0, null, AnalyzerChoice.MODEL)).id());

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.MODEL_NOT_RELEASED);
            assertThat(event.message()).startsWith("2 Seiten ohne KI-Freigabe");
        });
        assertThat(jobs.events(done.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(StatementAnalysisJobs.MODEL_NO_MODEL);
            assertThat(event.message()).startsWith("1 Seiten bewusst ausgelassen");
        });
        assertThat(chat.inputs).isEmpty();
    }

    private JobStatus run(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
        return jobs.status(batch.start(CALLER, scope, options).id());
    }

    private static List<String> range(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add(String.valueOf(i));
        }
        return ids;
    }

    private static PageSnapshot page(String sourceId, String pageId) {
        return EnrichmentTestPages.text(sourceId, pageId, "1", "Der Sachbearbeiter " + pageId
                + " übermittelt dem Vorgesetzten den Beleg.");
    }

    /** Quelle mit Spaces und Kindseiten in Stapeln zu {@value #BATCH}. */
    private final class Tree implements StatementAnalysisJobs.Discovery {

        private final Map<String, List<String>> roots = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();
        private final Map<String, String> spaceOf = new HashMap<>();
        private final Set<String> failing = new HashSet<>();
        int calls;
        int cursorsServed;
        boolean lookupFails;

        void space(String sourceId, String spaceKey, String... rootIds) {
            roots.put(spaceKey, List.of(rootIds));
            for (String id : rootIds) {
                spaceOf.put(id, spaceKey);
            }
        }

        void children(String parent, List<String> ids) {
            children.put(parent, ids);
            ids.forEach(id -> spaceOf.putIfAbsent(id, spaceOf.getOrDefault(parent, "DEV")));
        }

        @Override
        public PageNavigation.Children children(AccessContext caller, String sourceId, String spaceKey,
                                                String parentPageId, String cursor) {
            calls++;
            String space = parentPageId == null ? spaceKey : spaceOf.getOrDefault(parentPageId, "DEV");
            access.require(caller, ResourceScope.space(sourceId, space), AccessKind.METADATA);
            if (parentPageId != null && failing.contains(parentPageId)) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            List<String> all = parentPageId == null ? roots.getOrDefault(spaceKey, List.of())
                    : children.getOrDefault(parentPageId, List.of());
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            if (cursor != null) {
                cursorsServed++;
            }
            int to = Math.min(all.size(), from + BATCH);
            List<PageNode> nodes = all.subList(from, to).stream()
                    .map(id -> new PageNode(id, "Seite " + id, space, id.hashCode() % 2 == 0 ? null
                            : children.containsKey(id)))
                    .toList();
            return new PageNavigation.Children(sourceId, space, parentPageId, nodes,
                    to < all.size() ? String.valueOf(to) : null);
        }

        @Override
        public String spaceKey(AccessContext caller, String sourceId, String pageId) {
            if (lookupFails) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null);
            }
            String space = spaceOf.get(pageId);
            access.require(caller, ResourceScope.page(sourceId, space, pageId), AccessKind.METADATA);
            return space;
        }

        @Override
        public List<Partition> spaces(AccessContext caller, String sourceId) {
            return roots.keySet().stream().filter(key -> !spacesDenied.contains(key))
                    .map(key -> new Partition(key, key, null)).toList();
        }
    }
}
