package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.marks.VideoMarks;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Angelo 21.11.2026: „alles, was die App von sich aus tut, steht in der Auftragsliste“. Die Folienwechsel-Erkennung beim
 * Öffnen eines Videos läuft als Hintergrundart, je Video und Fassung höchstens einmal, mit den Rechten dessen, der das
 * Video geöffnet hat (Architekt 21.11.2026).
 */
class VideoSlideDetectionTest {

    private static final String OWNER = "app";
    private static final AccessContext APP = AccessContext.technicalClient("space-harvester-app");
    private static final AccessContext ANNA = AccessContext.endUser("anna", "space-harvester-app",
            new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "geheim"));

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Map<String, List<Long>> marks = new ConcurrentHashMap<>();
    private final List<AccessContext> readers = new CopyOnWriteArrayList<>();
    private final VideoMarks slides = new VideoMarks() {
        @Override
        public List<Long> find(PageKey page, String version) {
            return marks.getOrDefault(page.pageId() + "@" + version, List.of());
        }

        @Override
        public void save(PageKey page, String version, List<Long> found) {
            marks.put(page.pageId() + "@" + version, List.copyOf(found));
        }
    };

    private Jobs jobs(Executor on) {
        return new Jobs(store, on, new JobLimits(4, 10, Duration.ofHours(1)), Clock.systemUTC());
    }

    private VideoPresentations presentations(Jobs jobs) {
        PageSnapshot snapshot = Mockito.mock(PageSnapshot.class);
        Mockito.when(snapshot.key()).thenReturn(new PageKey("wiki", "42"));
        Mockito.when(snapshot.revision()).thenReturn("r1");
        Mockito.when(snapshot.metadata()).thenReturn(new com.aresstack.spaceharvester.application.sources
                .PageMetadata("42", "42", "Vortrag.mp4", "CLUK", null, null, null, null, new java.util.TreeMap<>()));
        VideoPresentations presentations = new VideoPresentations(jobs, (who, sourceId, pageId) -> {
            readers.add(who);
            return snapshot;
        }, Mockito.mock(com.aresstack.spaceharvester.application.assets.Assets.class),
                Mockito.mock(com.aresstack.spaceharvester.application.images.PageImageAnalysis.class), slides,
                Mockito.mock(com.aresstack.spaceharvester.application.stepmodel.StepModel.class),
                new PresentationStore(new InMemoryEnrichmentStore(), Clock.systemUTC()), key -> true,
                java.nio.file.Path.of("out"));
        presentations.resumer(Runnable::run);
        return presentations;
    }

    private static VideoMedia media(List<Long> changes) {
        return new VideoMedia() {
            @Override
            public String transcribe(String existing, Consumer<String> partial, Progress progress,
                                     BooleanSupplier cancelled) {
                return existing;
            }

            @Override
            public void frames(List<Long> millis, BiConsumer<Long, byte[]> found, BooleanSupplier cancelled) {
            }

            @Override
            public List<Long> slideChanges() {
                if (changes == null) {
                    throw new IllegalStateException("Index nicht lesbar");
                }
                return changes;
            }
        };
    }

    @Test
    void openingAVideoWithoutMarksStartsOneBackgroundPartThatStoresTheMarksWithTheRightsOfWhoOpenedIt() {
        Steps executor = new Steps();
        Jobs jobs = jobs(executor);
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        VideoPresentations presentations = presentations(jobs);
        presentations.background(background);
        presentations.mediaSource((sourceId, pageId) -> media(List.of(4_000L, 9_000L)));

        BackgroundGroup group = presentations.detectSlides(ANNA, "wiki", "42").orElseThrow();
        Optional<BackgroundGroup> again = presentations.detectSlides(ANNA, "wiki", "42");

        assertThat(group.parts()).singleElement().satisfies(part -> {
            assertThat(part.partitionKey()).isEqualTo("CLUK");
            assertThat(part.parameter(VideoPresentations.PAGE_PARAMETER)).isEqualTo("42");
            assertThat(part.parameter(VideoPresentations.PRINCIPAL_PARAMETER)).isEqualTo("anna");
            assertThat(part.parameter(BackgroundJobs.KIND)).isEqualTo(VideoPresentations.SLIDES_KIND);
        });
        assertThat(again).as("je Video und Fassung höchstens ein offener Teil")
                .hasValueSatisfying(same -> assertThat(same.jobIds()).isEqualTo(group.jobIds()));

        executor.runAll();

        assertThat(marks).containsEntry("42@r1", List.of(4_000L, 9_000L));
        assertThat(background.group(group.groupId()).orElseThrow().completed()).isTrue();
        assertThat(readers).containsOnly(ANNA);
        assertThat(presentations.detectSlides(ANNA, "wiki", "42")).as("Marken da: nichts mehr zu erkennen").isEmpty();
    }

    @Test
    void aFailedRunDoesNotStartAgainSilentlyWhenTheVideoIsOpenedAgain() {
        Steps executor = new Steps();
        Jobs jobs = jobs(executor);
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        VideoPresentations presentations = presentations(jobs);
        presentations.background(background);
        presentations.mediaSource((sourceId, pageId) -> media(null));

        BackgroundGroup group = presentations.detectSlides(ANNA, "wiki", "42").orElseThrow();
        executor.runAll();
        BackgroundGroup reopened = presentations.detectSlides(ANNA, "wiki", "42").orElseThrow();

        assertThat(reopened.jobIds()).isEqualTo(group.jobIds());
        assertThat(reopened.finished()).isTrue();
        assertThat(reopened.parts()).singleElement()
                .satisfies(part -> assertThat(part.state()).isEqualTo(JobState.FAILED));
        assertThat(marks).isEmpty();
    }

    @Test
    void afterARestartTheDetectionWaitsForTheVideosOfTheAppAndThenRunsWithTheSameOwner() {
        // Erster Dienst: der Teil wird angenommen, läuft aber nicht mehr vor dem Beenden.
        Jobs first = jobs(new Steps());
        VideoPresentations before = presentations(first);
        before.background(new BackgroundJobs(first, OWNER));
        before.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        String jobId = before.detectSlides(APP, "wiki", "42").orElseThrow().jobIds().get(0);

        Steps executor = new Steps();
        Jobs restarted = jobs(executor);
        BackgroundJobs background = new BackgroundJobs(restarted, OWNER);
        VideoPresentations after = presentations(restarted);
        after.background(background);
        readers.clear();

        assertThat(background.resumeInterrupted()).as("noch keine Videos der App").isEmpty();

        after.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        executor.runAll();

        assertThat(restarted.status(jobId).state()).isEqualTo(JobState.COMPLETED);
        assertThat(marks).containsEntry("42@r1", List.of(1_000L));
        assertThat(readers).containsOnly(APP);
    }

    @Test
    void theAppsVideosResumeTheDetectionOnceAndNotOnTheCallingThreadEvenWhenTheSettingsAreReloaded() {
        // Architekt 21.11.2026: nur beim ersten Setzen und abseits des EDT; ein Neuladen setzt nichts fort.
        Jobs first = jobs(new Steps());
        VideoPresentations before = presentations(first);
        before.background(new BackgroundJobs(first, OWNER));
        before.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        String jobId = before.detectSlides(APP, "wiki", "42").orElseThrow().jobIds().get(0);

        Steps executor = new Steps();
        Jobs restarted = jobs(executor);
        VideoPresentations after = presentations(restarted);
        after.background(new BackgroundJobs(restarted, OWNER));
        List<Runnable> resumptions = new CopyOnWriteArrayList<>();
        after.resumer(resumptions::add);

        after.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        after.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));

        assertThat(resumptions).as("einmal, nicht im Aufrufer").hasSize(1);
        executor.runAll();
        assertThat(restarted.status(jobId).state()).isNotEqualTo(JobState.COMPLETED);

        resumptions.get(0).run();
        executor.runAll();
        assertThat(restarted.status(jobId).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void anEndUsersPartPausesAfterARestartBecauseItsSignInIsGone() {
        // Nie mit technischen Rechten weiter, wenn ein Benutzer ihn gestartet hat (Architekt 21.11.2026).
        Jobs first = jobs(new Steps());
        VideoPresentations before = presentations(first);
        before.background(new BackgroundJobs(first, OWNER));
        before.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        String jobId = before.detectSlides(ANNA, "wiki", "42").orElseThrow().jobIds().get(0);

        Steps executor = new Steps();
        Jobs restarted = jobs(executor);
        VideoPresentations after = presentations(restarted);
        BackgroundJobs background = new BackgroundJobs(restarted, OWNER);
        after.background(background);
        readers.clear();
        after.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));
        executor.runAll();

        assertThat(restarted.status(jobId).state()).isEqualTo(JobState.PAUSED);
        assertThat(readers).isEmpty();
        assertThat(marks).isEmpty();

        // Ein anderer Benutzer öffnet das Video: er übernimmt den Teil nicht, ▶ hält wieder an.
        AccessContext bert = AccessContext.endUser("bert", "space-harvester-app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "anders"));
        after.detectSlides(bert, "wiki", "42");
        background.resumeJob(jobId);
        executor.runAll();
        assertThat(restarted.status(jobId).state()).isEqualTo(JobState.PAUSED);
        assertThat(marks).isEmpty();

        // Anna öffnet das Video wieder: mit ihrer frischen Anmeldung setzt ▶ im Reiter „Hintergrund“ fort.
        readers.clear();
        after.detectSlides(ANNA, "wiki", "42");
        background.resumeJob(jobId);
        executor.runAll();
        assertThat(restarted.status(jobId).state()).isEqualTo(JobState.COMPLETED);
        assertThat(marks).containsEntry("42@r1", List.of(1_000L));
        assertThat(readers).as("nur Anna liest").allMatch(ANNA::equals);
    }

    @Test
    void aPartWithoutItsOwnerPausesInsteadOfRunningWithOtherRights() {
        Steps executor = new Steps();
        Jobs jobs = jobs(executor);
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        VideoPresentations presentations = presentations(jobs);
        presentations.background(background);
        presentations.mediaSource((sourceId, pageId) -> media(List.of(1_000L)));

        BackgroundGroup group = background.start(VideoPresentations.slidesGroup("wiki", "42", "r1"),
                VideoPresentations.SLIDES_KIND, "Folienwechsel erkennen",
                List.of(new BackgroundPart.Draft("wiki", "CLUK", Map.of(VideoPresentations.PAGE_PARAMETER, "42"))));
        executor.runAll();

        assertThat(jobs.status(group.jobIds().get(0)).state()).isEqualTo(JobState.PAUSED);
        assertThat(readers).isEmpty();
        assertThat(marks).isEmpty();

        // Er hielte bei jedem Fortsetzen erneut an: öffnet Anna das Video, wird er verworfen und für sie neu angelegt.
        BackgroundGroup fresh = presentations.detectSlides(ANNA, "wiki", "42").orElseThrow();
        assertThat(fresh.jobIds()).doesNotContainAnyElementsOf(group.jobIds());
        assertThat(fresh.parts()).singleElement()
                .satisfies(part -> assertThat(part.parameter(VideoPresentations.PRINCIPAL_PARAMETER)).isEqualTo("anna"));
        assertThat(background.group(group.groupId()).orElseThrow().jobIds()).isEqualTo(fresh.jobIds());
        executor.runAll();
        assertThat(marks).containsEntry("42@r1", List.of(1_000L));
        assertThat(readers).containsOnly(ANNA);
    }

    /** Führt Arbeit erst auf Anforderung aus. */
    static final class Steps implements Executor {
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
}
