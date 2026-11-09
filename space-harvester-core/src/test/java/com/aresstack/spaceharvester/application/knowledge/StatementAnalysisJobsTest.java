package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> {
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
        public List<Partition> spaces(AccessContext caller, String sourceId) {
            return roots.keySet().stream().filter(key -> !spacesDenied.contains(key))
                    .map(key -> new Partition(key, key, null)).toList();
        }
    }
}
