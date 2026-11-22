package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.services.AnalysisService;
import com.aresstack.spaceharvester.application.background.services.MissingPages;
import com.aresstack.spaceharvester.application.background.services.ServicePlan;
import com.aresstack.spaceharvester.application.background.services.ServicePlanner;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.background.services.ServiceStart;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Der Dienst-Planer (D1, Nachtrag ADR 0025), nur mit erfundenen Quellen und Spaces. */
class ServicePlannerTest {

    private static final String OWNER = "app";
    private static final AccessContext CALLER = AccessContext.technicalClient(OWNER);
    private static final MissingPages.Scope ALPHA = new MissingPages.Scope("wiki", "ALPHA");
    private static final MissingPages.Scope BETA = new MissingPages.Scope("wiki", "BETA");
    /** Seiten a* liegen in ALPHA, b* in BETA, x* sieht der Aufrufer nicht. */
    private static final PageSet PAGES = PageSet.of(Map.of("wiki", Set.of("a1", "a2", "b1", "x1")));

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final BackgroundJobsTest.Steps executor = new BackgroundJobsTest.Steps();
    private final BackgroundJobsTest.Ticking clock = new BackgroundJobsTest.Ticking();
    /** Was lief, als „Art:Space“, in Reihenfolge. */
    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final Set<String> failing = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final Map<String, MissingPages> missing = new HashMap<>();
    private final Map<String, ServiceStart> starts = new HashMap<>();

    private Jobs jobs() {
        return jobs(4);
    }

    private Jobs jobs(int slots) {
        return new Jobs(store, executor, new JobLimits(slots, 10, Duration.ofHours(1)), clock);
    }

    private BackgroundJobs background(Jobs jobs, String... notResumable) {
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        Set<String> stays = Set.of(notResumable);
        for (String kind : List.of("pre", "images", "statements", "graph", "goal", "demo")) {
            background.register(new BackgroundKind(kind, part -> JobTask.blocking(context -> {
                context.checkpoint();
                if (failing.contains(kind + ":" + part.partitionKey())) {
                    throw new IllegalStateException("erfundener Fehler");
                }
                ran.add(kind + ":" + part.partitionKey());
            }), () -> !stays.contains(kind)));
        }
        return background;
    }

    private Supplier<ServiceStart> start(String id) {
        return () -> starts.getOrDefault(id, ServiceStart.MANUAL);
    }

    private AnalysisService service(String id, String title, String... prerequisites) {
        return new AnalysisService(id, title, start(id), List.of(prerequisites), id,
                (caller, pages) -> missing.getOrDefault(id, MissingPages.NONE));
    }

    private ServicePlanner planner(BackgroundJobs background) {
        ServicePlanner planner = new ServicePlanner(background, (caller, pages) -> {
            Map<MissingPages.Scope, Set<String>> visible = new HashMap<>();
            int hidden = 0;
            for (String id : pages.asMap().getOrDefault("wiki", Set.of())) {
                if (id.startsWith("x")) {
                    hidden++;
                } else {
                    visible.computeIfAbsent(id.startsWith("a") ? ALPHA : BETA, k -> new java.util.TreeSet<>()).add(id);
                }
            }
            return new MissingPages(visible, hidden);
        });
        planner.register(service("pre", "Vorstufe"));
        planner.register(service("goal", "Ziel", "pre"));
        return planner;
    }

    private static MissingPages missingIn(MissingPages.Scope... scopes) {
        Map<MissingPages.Scope, Set<String>> pages = new HashMap<>();
        for (MissingPages.Scope scope : scopes) {
            pages.put(scope, Set.of(scope == ALPHA ? "a1" : "b1"));
        }
        return new MissingPages(pages, 0);
    }

    private static JobStatus part(BackgroundGroup group, String kind, String space) {
        return group.parts().stream().filter(status -> kind.equals(status.parameter(BackgroundJobs.KIND))
                && space.equals(status.partitionKey())).findFirst().orElseThrow();
    }

    @Test
    void aCycleIsRejectedWhenRegisteringAndNothingChanges() {
        ServicePlanner planner = planner(background(jobs()));

        assertThatThrownBy(() -> planner.register(service("pre", "Vorstufe", "goal")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Zyklus");
        assertThat(planner.service("pre").orElseThrow().prerequisites()).isEmpty();
    }

    @Test
    void eachStageWaitsPerSpaceForItsPrerequisiteAndTheGoalComesLast() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA, BETA));

        ServicePlan plan = planner(background).start(CALLER, "goal", PAGES);
        BackgroundGroup group = plan.group().orElseThrow();
        executor.runAll();

        assertThat(group.kind()).isEqualTo("goal");
        assertThat(group.title()).isEqualTo("Ziel");
        assertThat(group.parts()).hasSize(4);
        assertThat(part(group, "goal", "ALPHA").parameter(Jobs.AFTER)).isEqualTo(part(group, "pre", "ALPHA").id());
        assertThat(ran.indexOf("pre:ALPHA")).isLessThan(ran.indexOf("goal:ALPHA"));
        assertThat(ran.indexOf("pre:BETA")).isLessThan(ran.indexOf("goal:BETA"));
        assertThat(plan.inaccessible()).isEqualTo(1);
        assertThat(plan.notes()).containsExactly("1 Seite ohne Zugriff ausgelassen");
        assertThat(background.group(group.groupId()).orElseThrow().completed()).isTrue();
    }

    @Test
    void aStageWithTwoPrerequisitesWaitsForBothInItsSpace() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        planner.register(service("images", "Bilder"));
        planner.register(service("statements", "Aussagen"));
        planner.register(service("graph", "Graph", "statements", "images"));
        missing.put("images", missingIn(ALPHA, BETA));
        missing.put("statements", missingIn(ALPHA));
        missing.put("graph", missingIn(ALPHA, BETA));

        BackgroundGroup group = planner.start(CALLER, "graph", PAGES).group().orElseThrow();

        assertThat(part(group, "graph", "ALPHA").parameter(Jobs.AFTER).split(",")).containsExactlyInAnyOrder(
                part(group, "statements", "ALPHA").id(), part(group, "images", "ALPHA").id());
        assertThat(part(group, "graph", "ALPHA").parameter(Jobs.AFTER_TITLES)).contains("„Aussagen“", "„Bilder“");
        assertThat(part(group, "graph", "BETA").parameter(Jobs.AFTER)).isEqualTo(part(group, "images", "BETA").id());
        executor.runAll();
        assertThat(ran.indexOf("statements:ALPHA")).isLessThan(ran.indexOf("graph:ALPHA"));
        assertThat(ran.indexOf("images:ALPHA")).isLessThan(ran.indexOf("graph:ALPHA"));
    }

    @Test
    void aDisabledPrerequisiteIsSkippedAndReportedYellow() {
        BackgroundJobs background = background(jobs());
        missing.put("pre", missingIn(ALPHA));
        starts.put("pre", ServiceStart.DISABLED);

        ServicePlan plan = planner(background).start(CALLER, "goal", PAGES);
        executor.runAll();

        assertThat(plan.notes()).contains("„Vorstufe“ ist abgeschaltet und wurde bewusst ausgelassen");
        assertThat(plan.group().orElseThrow().parts()).extracting(status -> status.parameter(BackgroundJobs.KIND))
                .containsOnly("goal");
        assertThat(ran).containsExactlyInAnyOrder("goal:ALPHA", "goal:BETA");
    }

    @Test
    void aDisabledGoalStartsNothing() {
        BackgroundJobs background = background(jobs());
        starts.put("goal", ServiceStart.DISABLED);

        ServicePlan plan = planner(background).start(CALLER, "goal", PAGES);

        assertThat(plan.group()).isEmpty();
        assertThat(plan.notes()).containsExactly("„Ziel“ ist abgeschaltet und wurde bewusst ausgelassen");
    }

    @Test
    void anEmptyPrerequisiteCreatesNothingButTheGoalAlwaysCreatesItsParts() {
        BackgroundJobs background = background(jobs());

        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();
        executor.runAll();

        assertThat(group.parts()).extracting(JobStatus::partitionKey).containsExactlyInAnyOrder("ALPHA", "BETA");
        assertThat(group.parts()).allSatisfy(status -> assertThat(status.parameter(Jobs.AFTER)).isNull());
        assertThat(ran).containsExactlyInAnyOrder("goal:ALPHA", "goal:BETA");
    }

    @Test
    void aRunningPartOfTheSameKindAndSpaceIsReusedAsPredecessor() {
        BackgroundJobs background = background(jobs());
        BackgroundGroup earlier = background.start("pre", "Früher", List.of(new BackgroundPart.Draft("wiki",
                "ALPHA", Map.of(AnalysisService.PAGES, "a1"))));
        missing.put("pre", missingIn(ALPHA));

        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();

        assertThat(group.parts()).extracting(status -> status.parameter(BackgroundJobs.KIND)).containsOnly("goal");
        assertThat(part(group, "goal", "ALPHA").parameter(Jobs.AFTER)).isEqualTo(earlier.parts().get(0).id());
        executor.runAll();
        assertThat(ran.indexOf("pre:ALPHA")).isLessThan(ran.indexOf("goal:ALPHA"));
    }

    @Test
    void aPausedPartOfAnotherGroupIsNotReusedAsPredecessor() {
        // Nachprüfung #1052: Sonst wartete das neue Ziel, bis jemand die fremde, angehaltene Gruppe fortsetzt.
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        BackgroundGroup earlier = background.start("pre", "Früher", List.of(new BackgroundPart.Draft("wiki",
                "ALPHA", Map.of(AnalysisService.PAGES, "a1"))));
        background.pause(earlier.groupId());
        executor.runAll();
        assertThat(jobs.status(earlier.parts().get(0).id()).state()).isEqualTo(JobState.PAUSED);
        missing.put("pre", missingIn(ALPHA));

        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();

        JobStatus pre = part(group, "pre", "ALPHA");
        assertThat(pre.id()).as("ein eigener Teil statt des angehaltenen").isNotEqualTo(earlier.parts().get(0).id());
        assertThat(part(group, "goal", "ALPHA").parameter(Jobs.AFTER)).isEqualTo(pre.id());
        executor.runAll();
        assertThat(ran).containsSubsequence("pre:ALPHA", "goal:ALPHA");
        assertThat(jobs.status(earlier.parts().get(0).id()).state()).isEqualTo(JobState.PAUSED);
    }

    @Test
    void aPausedPrerequisiteHoldsOnlyItsSpaceAndResumingKeepsTheOrder() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();
        String pre = part(group, "pre", "ALPHA").id();
        String goal = part(group, "goal", "ALPHA").id();

        jobs.pause(pre, OWNER);
        executor.runAll();

        assertThat(ran).containsExactly("goal:BETA");
        assertThat(jobs.status(goal).state()).isEqualTo(JobState.QUEUED);
        assertThat(jobs.events(goal)).filteredOn(event -> Jobs.WAITING_FOR.equals(event.code())).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo("wartet auf „Vorstufe“"));

        background.resumeJob(pre);
        executor.runAll();
        assertThat(ran).containsExactly("goal:BETA", "pre:ALPHA", "goal:ALPHA");
    }

    @Test
    void aHeldPartDoesNotBlockOtherBackgroundWork() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal",
                PageSet.of(Map.of("wiki", Set.of("a1")))).group().orElseThrow();
        jobs.pause(part(group, "pre", "ALPHA").id(), OWNER);

        background.start("demo", "Anderes", List.of(new BackgroundPart.Draft("wiki", "GAMMA")));
        executor.runAll();

        assertThat(ran).containsExactly("demo:GAMMA");
    }

    @Test
    void aFailedPrerequisiteDoesNotStopTheChainButIsReportedYellow() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        failing.add("pre:ALPHA");

        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();
        executor.runAll();

        String goal = part(group, "goal", "ALPHA").id();
        assertThat(jobs.status(part(group, "pre", "ALPHA").id()).state()).isEqualTo(JobState.FAILED);
        assertThat(jobs.status(goal).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.events(goal)).filteredOn(event -> BackgroundJobs.PREDECESSOR_MISSING.equals(event.code()))
                .singleElement().satisfies(event -> assertThat(event.message())
                        .isEqualTo("Vorstufe „Vorstufe“ gescheitert; läuft über das Vorhandene"));
    }

    @Test
    void aDeletedPrerequisiteReleasesWithTheSameWarning() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PAGES).group().orElseThrow();
        String pre = part(group, "pre", "ALPHA").id();
        String goal = part(group, "goal", "ALPHA").id();
        jobs.pause(pre, OWNER);
        executor.runAll();
        assertThat(jobs.status(goal).state()).isEqualTo(JobState.QUEUED);

        jobs.delete(pre, OWNER);
        executor.runAll();

        assertThat(jobs.status(goal).state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.events(goal)).filteredOn(event -> BackgroundJobs.PREDECESSOR_MISSING.equals(event.code()))
                .singleElement().satisfies(event -> assertThat(event.message()).contains("gelöscht"));
    }

    @Test
    void cancellingTheGroupAlsoCancelsHeldParts() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();
        jobs.pause(part(group, "pre", "ALPHA").id(), OWNER);

        background.cancel(group.groupId());
        executor.runAll();

        assertThat(jobs.status(part(group, "goal", "ALPHA").id()).state()).isEqualTo(JobState.CANCELLED);
        assertThat(ran).isEmpty();
    }

    @Test
    void aRestartInTheMiddleOfTheChainContinuesInOrder() {
        Jobs first = new Jobs(store, new BackgroundJobsTest.Steps(), new JobLimits(4, 10, Duration.ofHours(1)),
                clock);
        missing.put("pre", missingIn(ALPHA, BETA));
        BackgroundGroup group = planner(background(first)).start(CALLER, "goal", PAGES).group().orElseThrow();
        first.close();

        Jobs restarted = jobs();
        BackgroundJobs background = background(restarted);
        assertThat(background.resumeInterrupted()).hasSize(4);
        executor.runAll();

        assertThat(ran.indexOf("pre:ALPHA")).isLessThan(ran.indexOf("goal:ALPHA"));
        assertThat(ran.indexOf("pre:BETA")).isLessThan(ran.indexOf("goal:BETA"));
        assertThat(background.group(group.groupId()).orElseThrow().completed()).isTrue();
        restarted.close();
    }

    @Test
    void anInterruptedPrerequisiteThatCannotResumeReleasesItsFollowerWithAWarning() {
        Jobs first = new Jobs(store, new BackgroundJobsTest.Steps(), new JobLimits(4, 10, Duration.ofHours(1)),
                clock);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background(first)).start(CALLER, "goal",
                PageSet.of(Map.of("wiki", Set.of("a1")))).group().orElseThrow();
        first.close();

        Jobs restarted = jobs();
        BackgroundJobs background = background(restarted, "pre");
        background.resumeInterrupted();
        executor.runAll();

        String pre = part(group, "pre", "ALPHA").id();
        String goal = part(group, "goal", "ALPHA").id();
        assertThat(restarted.status(pre).error().code()).isEqualTo(JobLifecycleState.INTERRUPTED);
        assertThat(restarted.status(goal).state()).isEqualTo(JobState.COMPLETED);
        assertThat(restarted.events(goal)).filteredOn(event -> BackgroundJobs.PREDECESSOR_MISSING.equals(
                event.code())).hasSize(1);
        assertThat(ran).containsExactly("goal:ALPHA");
        restarted.close();
    }

    @Test
    void anUnknownTargetOrOneWithoutWorkIsRejected() {
        ServicePlanner planner = planner(background(jobs()));
        planner.register(new AnalysisService("described", "Nur beschrieben", () -> ServiceStart.MANUAL, List.of(),
                null, AnalysisService.Missing.NOTHING));

        assertThatThrownBy(() -> planner.start(CALLER, "unknown", PAGES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.start(CALLER, "described", PAGES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRunningPartCoveringOnlySomePagesIsReusedAndTheRestGetsItsOwnPart() {
        BackgroundJobs background = background(jobs());
        BackgroundGroup earlier = background.start("pre", "Früher", List.of(new BackgroundPart.Draft("wiki",
                "ALPHA", Map.of(AnalysisService.PAGES, "a1"))));
        missing.put("pre", new MissingPages(Map.of(ALPHA, Set.of("a1", "a2")), 0));

        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki",
                Set.of("a1", "a2")))).group().orElseThrow();

        JobStatus rest = part(group, "pre", "ALPHA");
        assertThat(rest.parameter(AnalysisService.PAGES)).isEqualTo("a2");
        assertThat(part(group, "goal", "ALPHA").parameter(Jobs.AFTER).split(","))
                .containsExactlyInAnyOrder(earlier.parts().get(0).id(), rest.id());
    }

    @Test
    void theGoalNeverReusesARunningPartAndAlwaysCreatesItsOwn() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        PageSet alpha = PageSet.of(Map.of("wiki", Set.of("a1")));
        BackgroundGroup first = planner.start(CALLER, "goal", alpha).group().orElseThrow();

        BackgroundGroup second = planner.start(CALLER, "goal", alpha).group().orElseThrow();

        assertThat(second.parts()).singleElement().satisfies(status -> {
            assertThat(status.parameter(BackgroundJobs.KIND)).isEqualTo("goal");
            assertThat(status.id()).isNotEqualTo(first.parts().get(0).id());
        });
    }

    @Test
    void cancellingAWaitingPredecessorWakesItsFollowerAtOnce() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        ServicePlanner planner = planner(background);
        planner.register(service("graph", "Graph", "goal"));
        missing.put("pre", missingIn(ALPHA));
        missing.put("goal", missingIn(ALPHA));
        BackgroundGroup group = planner.start(CALLER, "graph", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();
        jobs.pause(part(group, "pre", "ALPHA").id(), OWNER);
        executor.runAll();
        String middle = part(group, "goal", "ALPHA").id();
        assertThat(jobs.status(middle).state()).isEqualTo(JobState.QUEUED);

        jobs.cancel(middle, OWNER);
        executor.runAll();

        assertThat(ran).containsExactly("graph:ALPHA");
    }

    @Test
    void sweepingAPausedPredecessorAwayWakesItsFollower() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();
        jobs.pause(part(group, "pre", "ALPHA").id(), OWNER);
        executor.runAll();

        jobs.deleteFinished(OWNER, null);
        executor.runAll();

        assertThat(ran).containsExactly("goal:ALPHA");
    }

    @Test
    void anExpiredPausedPredecessorIsKeptWhileItsFollowerWaits() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();
        String pre = part(group, "pre", "ALPHA").id();
        jobs.pause(pre, OWNER);
        executor.runAll();

        Jobs sweeping = new Jobs(store, new BackgroundJobsTest.Steps(), new JobLimits(4, 10, Duration.ofMillis(1)),
                clock);
        for (int i = 0; i < 5; i++) {
            clock.instant();
        }
        sweeping.purgeExpired();

        assertThat(store.find(pre)).isPresent();
    }

    @Test
    void pausingAndResumingAPlannedGroupKeepsTheOrder() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();

        background.pause(group.groupId());
        executor.runAll();
        assertThat(ran).isEmpty();

        background.resume(group.groupId());
        executor.runAll();
        assertThat(ran).containsExactly("pre:ALPHA", "goal:ALPHA");
    }

    @Test
    void aUserJobStartsNextToAHeldPart() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        BackgroundGroup group = planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))))
                .group().orElseThrow();
        jobs.pause(part(group, "pre", "ALPHA").id(), OWNER);
        executor.runAll();

        JobStatus sync = jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "wiki", "ALPHA",
                null, OWNER, Map.of(), JobTask.blocking(context -> ran.add("sync")));
        executor.runAll();

        assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(ran).containsExactly("sync");
    }

    @Test
    void withASingleSlotNoPlannedPartRunsButTheUserJobDoes() {
        Jobs jobs = jobs(1);
        BackgroundJobs background = background(jobs);
        missing.put("pre", missingIn(ALPHA));
        planner(background).start(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))));

        jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "wiki", "ALPHA", null, OWNER,
                Map.of(), JobTask.blocking(context -> ran.add("sync")));
        executor.runAll();

        assertThat(ran).containsExactly("sync");
    }

    @Test
    void theServiceFixesItsParametersOnceForAllPartsAndThePagesStayThePlanners() {
        Jobs jobs = jobs();
        BackgroundJobs background = background(jobs);
        ServicePlanner planner = planner(background);
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        planner.register(new AnalysisService("goal", "Ziel", start("goal"), List.of("pre"), "goal",
                (caller, pages) -> MissingPages.NONE, caller -> Map.of("owner", caller.jobOwner(),
                "run", Integer.toString(asked.incrementAndGet()), AnalysisService.PAGES, "fremd")));

        BackgroundGroup group = planner.start(CALLER, "goal", PAGES).group().orElseThrow();

        assertThat(group.parts()).hasSize(2).allSatisfy(status -> {
            assertThat(status.parameter("owner")).isEqualTo(CALLER.jobOwner());
            assertThat(status.parameter("run")).as("einmal je Start, für alle Spaces gleich").isEqualTo("1");
        });
        assertThat(part(group, "goal", "ALPHA").parameter(AnalysisService.PAGES)).isEqualTo("a1\na2");
        assertThat(part(group, "goal", "BETA").parameter(AnalysisService.PAGES)).isEqualTo("b1");
    }

    /** Die Seiten der Teile einer Art in der Gruppe ({@link AnalysisService#PAGES}). */
    private static int pagesOf(BackgroundGroup group, String kind) {
        return group.parts().stream().filter(status -> kind.equals(status.parameter(BackgroundJobs.KIND)))
                .mapToInt(status -> status.parameter(AnalysisService.PAGES).split("\n").length).sum();
    }

    private static long partsOf(BackgroundGroup group, String kind) {
        return group.parts().stream().filter(status -> kind.equals(status.parameter(BackgroundJobs.KIND))).count();
    }

    private static ServicePreview.Stage stage(ServicePreview preview, String id) {
        return preview.stages().stream().filter(stage -> stage.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void thePreviewCountsWhatTheStartCreatesWithTheSameNotes() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        planner.register(service("images", "Bilder"));
        planner.register(service("statements", "Aussagen"));
        planner.register(service("graph", "Graph", "statements", "images"));
        missing.put("images", missingIn(ALPHA));
        missing.put("statements", new MissingPages(Map.of(ALPHA, Set.of("a1", "a2"), BETA, Set.of("b1")), 0, 2));
        starts.put("pre", ServiceStart.DISABLED);
        planner.register(service("statements", "Aussagen", "pre"));

        ServicePreview preview = planner.preview(CALLER, "graph", PAGES);
        ServicePlan plan = planner.start(CALLER, "graph", PAGES);

        BackgroundGroup group = plan.group().orElseThrow();
        assertThat(preview.stages()).extracting(ServicePreview.Stage::id)
                .as("Vorstufen vor ihren Nachfolgern, das Ziel zuletzt").endsWith("graph");
        for (String kind : List.of("images", "statements", "graph")) {
            assertThat(stage(preview, kind).pages()).as(kind).isEqualTo(pagesOf(group, kind));
            assertThat((long) stage(preview, kind).parts()).as(kind).isEqualTo(partsOf(group, kind));
            assertThat(stage(preview, kind).state()).isEqualTo(ServicePreview.State.PLANNED);
        }
        assertThat(stage(preview, "statements").toLoad()).isEqualTo(2);
        assertThat(stage(preview, "pre").state()).isEqualTo(ServicePreview.State.DISABLED);
        assertThat(preview.notes()).isEqualTo(plan.notes()).containsExactly(
                "„Vorstufe“ ist abgeschaltet und wurde bewusst ausgelassen",
                "„Aussagen“: 2 Seiten erst laden – bleiben ausgelassen",
                "1 Seite ohne Zugriff ausgelassen");
        assertThat(preview.inaccessible()).isEqualTo(plan.inaccessible()).isEqualTo(1);
        assertThat(preview.nothingMissing()).isFalse();
        assertThat(preview.goal().orElseThrow().id()).isEqualTo("graph");
    }

    @Test
    void thePreviewStartsNothingAndAsksNoServiceForItsParameters() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        planner.register(new AnalysisService("pre", "Vorstufe", start("pre"), List.of(), "pre",
                (caller, pages) -> missingIn(ALPHA), caller -> Map.of("run",
                Integer.toString(asked.incrementAndGet()))));
        planner.register(new AnalysisService("goal", "Ziel", start("goal"), List.of("pre"), "goal",
                (caller, pages) -> MissingPages.NONE, caller -> Map.of("run",
                Integer.toString(asked.incrementAndGet()))));

        ServicePreview preview = planner.preview(CALLER, "goal", PAGES);
        executor.runAll();

        assertThat(asked).as("die Angaben legen etwa den Aufrufer ab").hasValue(0);
        assertThat(background.groups()).isEmpty();
        assertThat(ran).isEmpty();
        assertThat(stage(preview, "pre").pages()).isEqualTo(1);
        assertThat(stage(preview, "goal").parts()).isEqualTo(2);
    }

    @Test
    void thePreviewCountsPagesOfARunningPrerequisiteAsAlreadyRunning() {
        BackgroundJobs background = background(jobs());
        background.start("pre", "Früher", List.of(new BackgroundPart.Draft("wiki", "ALPHA",
                Map.of(AnalysisService.PAGES, "a1"))));
        ServicePlanner planner = planner(background);
        PageSet alpha = PageSet.of(Map.of("wiki", Set.of("a1", "a2")));

        missing.put("pre", new MissingPages(Map.of(ALPHA, Set.of("a1", "a2")), 0));
        ServicePreview partly = planner.preview(CALLER, "goal", alpha);
        missing.put("pre", new MissingPages(Map.of(ALPHA, Set.of("a1")), 0));
        ServicePreview covered = planner.preview(CALLER, "goal", alpha);

        assertThat(stage(partly, "pre")).isEqualTo(new ServicePreview.Stage("pre", "Vorstufe",
                ServicePreview.State.PLANNED, 1, 0, 1, 1));
        assertThat(stage(covered, "pre")).isEqualTo(new ServicePreview.Stage("pre", "Vorstufe",
                ServicePreview.State.RUNNING, 0, 0, 1, 0));
        assertThat(covered.nothingMissing()).as("das Ziel wartet auf den laufenden Teil").isFalse();
    }

    @Test
    void pagesToLoadAreCarriedAndNotedAlikeInPreviewAndStart() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        missing.put("pre", new MissingPages(Map.of(), 0, 3));
        PageSet alpha = PageSet.of(Map.of("wiki", Set.of("a1")));

        ServicePreview preview = planner.preview(CALLER, "goal", alpha);
        ServicePlan plan = planner.start(CALLER, "goal", alpha);

        assertThat(stage(preview, "pre")).isEqualTo(new ServicePreview.Stage("pre", "Vorstufe",
                ServicePreview.State.NOTHING_MISSING, 0, 3, 0, 0));
        assertThat(preview.notes()).isEqualTo(plan.notes())
                .containsExactly("„Vorstufe“: 3 Seiten erst laden – bleiben ausgelassen");
        assertThat(preview.nothingMissing()).as("zu laden ist nichts, was der Planer rechnen könnte").isTrue();
    }

    @Test
    void aDisabledStageShowsDisabledInThePreviewWithTheYellowNote() {
        BackgroundJobs background = background(jobs());
        missing.put("pre", missingIn(ALPHA));
        starts.put("pre", ServiceStart.DISABLED);
        ServicePlanner planner = planner(background);

        ServicePreview preview = planner.preview(CALLER, "goal", PageSet.of(Map.of("wiki", Set.of("a1"))));

        assertThat(stage(preview, "pre")).isEqualTo(new ServicePreview.Stage("pre", "Vorstufe",
                ServicePreview.State.DISABLED, 0, 0, 0, 0));
        assertThat(preview.notes()).containsExactly("„Vorstufe“ ist abgeschaltet und wurde bewusst ausgelassen");
        assertThat(preview.nothingMissing()).isTrue();
    }

    @Test
    void aPrerequisiteWithoutWorkShowsNotWiredWithoutANote() {
        BackgroundJobs background = background(jobs());
        ServicePlanner planner = planner(background);
        planner.register(new AnalysisService("pre", "Vorstufe", start("pre"), List.of(), null,
                (caller, pages) -> missingIn(ALPHA)));
        PageSet alpha = PageSet.of(Map.of("wiki", Set.of("a1")));

        ServicePreview preview = planner.preview(CALLER, "goal", alpha);
        ServicePlan plan = planner.start(CALLER, "goal", alpha);

        assertThat(stage(preview, "pre").state()).isEqualTo(ServicePreview.State.NOT_WIRED);
        assertThat(preview.notes()).isEmpty();
        assertThat(plan.notes()).as("kein gelber Hinweis beim Start").isEmpty();
        assertThat(plan.group().orElseThrow().parts()).singleElement()
                .satisfies(status -> assertThat(status.parameter(BackgroundJobs.KIND)).isEqualTo("goal"));
    }

    @Test
    void aDisabledGoalShowsOnlyItselfAsDisabled() {
        BackgroundJobs background = background(jobs());
        starts.put("goal", ServiceStart.DISABLED);

        ServicePreview preview = planner(background).preview(CALLER, "goal", PAGES);

        assertThat(preview.stages()).containsExactly(new ServicePreview.Stage("goal", "Ziel",
                ServicePreview.State.DISABLED, 0, 0, 0, 0));
        assertThat(preview.notes()).containsExactly("„Ziel“ ist abgeschaltet und wurde bewusst ausgelassen");
    }
}
