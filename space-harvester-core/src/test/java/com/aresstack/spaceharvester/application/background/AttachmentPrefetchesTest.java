package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Anhänge vorladen“ als Hintergrundteil je Quelle und Space (Fundstelle 18); nur erfundene Quellen und Anhänge. */
class AttachmentPrefetchesTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.systemUTC());
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final AttachmentPrefetches prefetches = new AttachmentPrefetches(background);
    private final List<String> loaded = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Set<String> entered = ConcurrentHashMap.newKeySet();
    /** Anhänge, deren Abruf am Netz scheitert (keine Verbindung). */
    private final Set<String> unreachable = ConcurrentHashMap.newKeySet();
    private final AttachmentPrefetches.Loader loader = (sourceId, pageId, attachmentId) -> {
        entered.add(attachmentId);
        CountDownLatch gate = gates.get(attachmentId);
        if (gate != null) {
            gate.await(10, TimeUnit.SECONDS);
        }
        if (unreachable.contains(attachmentId)) {
            throw new java.io.UncheckedIOException("Abruf scheiterte", new java.net.ConnectException("Connection refused"));
        }
        if (failing.contains(attachmentId)) {
            throw new IllegalStateException("erfunden");
        }
        loaded.add(pageId + "/" + attachmentId);
    };

    @AfterEach
    void close() {
        prefetches.close();
        gates.values().forEach(CountDownLatch::countDown);
        jobs.close();
        executor.shutdownNow();
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private List<JobStatus> parts() {
        return background.parts(AttachmentPrefetches.KIND);
    }

    private Optional<JobStatus> part(JobState state) {
        return parts().stream().filter(part -> part.state() == state).findFirst();
    }

    @Test
    void thePagesOfASpaceAreCollectedIntoOnePart() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        JobStatus first = prefetches.request("wiki", "DEV", "100", List.of("a1", "a2"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));

        JobStatus second = prefetches.request("wiki", "DEV", "200", List.of("b1"), loader).orElseThrow();
        gates.get("a1").countDown();

        eventually(() -> loaded.size() == 3);
        eventually(() -> parts().stream().allMatch(part -> part.state().terminal()));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(parts()).hasSize(1);
        JobStatus done = parts().getFirst();
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.parameter(BackgroundJobs.TITLE)).isEqualTo(AttachmentPrefetches.TITLE);
        assertThat(done.partitionKey()).isEqualTo("DEV");
        assertThat(done.progress().written()).isEqualTo(3);
        assertThat(done.progress().discovered()).isEqualTo(3);
    }

    @Test
    void aFailedAttachmentCountsRedWithAReasonAndThePartFails() throws Exception {
        failing.add("a2");
        prefetches.request("wiki", "DEV", "100", List.of("a1", "a2", "a3"), loader).orElseThrow();

        eventually(() -> part(JobState.FAILED).isPresent());

        JobStatus failed = part(JobState.FAILED).orElseThrow();
        assertThat(failed.error().code()).isEqualTo(AttachmentPrefetches.ATTACHMENTS_MISSING);
        assertThat(failed.progress().written()).isEqualTo(2);
        assertThat(failed.progress().failed()).isEqualTo(1);
        assertThat(jobs.events(failed.id())).anySatisfy(event -> assertThat(event.message()).contains("100"));
    }

    /**
     * Angelo 22.11.2026: Ohne Netz zählt kein Anhang als gescheitert; der Teil wartet sichtbar und lädt denselben Anhang
     * im selben Teil, sobald das Netz zurück ist. Fristen von Hand, gewartet wird nur auf Meldungen des Teils.
     */
    @Test
    void withoutNetworkThePartWaitsAndLoadsTheSameAttachmentWhenItIsBack() throws Exception {
        List<Runnable> checks = Collections.synchronizedList(new ArrayList<>());
        com.aresstack.spaceharvester.application.network.NetworkState network =
                new com.aresstack.spaceharvester.application.network.NetworkState(Clock.systemUTC(),
                        (delay, task) -> checks.add(task));
        prefetches.network(network);
        unreachable.add("a2");
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (var watch = background.subscribe(AttachmentPrefetches.KIND, status -> {
            if (status.state() == JobState.RUNNING
                    && status.stage() == com.aresstack.spaceharvester.application.jobs.JobStage.OFFLINE) {
                waiting.countDown();
            } else if (status.state() == JobState.COMPLETED) {
                completed.countDown();
            }
        }, removed -> {
        })) {
            JobStatus started = prefetches.request("wiki", "DEV", "100", List.of("a1", "a2", "a3"), loader)
                    .orElseThrow();

            assertThat(waiting.await(30, TimeUnit.SECONDS)).as("Teil wartet auf das Netz").isTrue();
            JobStatus offline = background.part(started.id()).orElseThrow();
            assertThat(offline.progress().failed()).isZero();
            assertThat(loaded).containsExactly("100/a1");
            assertThat(jobs.events(started.id())).anySatisfy(event -> {
                assertThat(event.code())
                        .isEqualTo(com.aresstack.spaceharvester.application.network.NetworkWait.OFFLINE);
                assertThat(event.message()).contains("ConnectException");
            });

            unreachable.clear();
            List<Runnable> due;
            synchronized (checks) {
                due = new ArrayList<>(checks);
                checks.clear();
            }
            due.forEach(Runnable::run);

            assertThat(completed.await(30, TimeUnit.SECONDS)).as("Teil fertig").isTrue();
            assertThat(network.online()).isTrue();
            assertThat(loaded).containsExactly("100/a1", "100/a2", "100/a3");
            assertThat(parts()).singleElement().satisfies(part -> {
                assertThat(part.id()).isEqualTo(started.id());
                assertThat(part.progress().written()).isEqualTo(3);
                assertThat(part.progress().failed()).isZero();
            });
            assertThat(jobs.events(started.id())).anySatisfy(event -> assertThat(event.code())
                    .isEqualTo(com.aresstack.spaceharvester.application.network.NetworkWait.ONLINE));
        } finally {
            network.close();
        }
    }

    @Test
    void pausingKeepsTheRestAndResumingLoadsIt() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        prefetches.request("wiki", "DEV", "100", List.of("a1", "a2", "a3"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));
        String groupId = part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP);

        background.pause(groupId);
        gates.get("a1").countDown();
        eventually(() -> part(JobState.PAUSED).isPresent());
        assertThat(loaded).containsExactly("100/a1");
        assertThat(prefetches.waitingPages("wiki", "DEV")).isEqualTo(1);

        background.resumeGroupWithoutCaller(groupId);

        eventually(() -> part(JobState.COMPLETED).isPresent());
        assertThat(loaded).containsExactly("100/a1", "100/a2", "100/a3");
        assertThat(parts()).hasSize(1);
    }

    @Test
    void cancellingDiscardsTheWaitingPagesOfTheSpace() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        prefetches.request("wiki", "DEV", "100", List.of("a1", "a2"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));

        background.cancel(part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP));
        gates.get("a1").countDown();

        eventually(() -> part(JobState.CANCELLED).isPresent());
        // Die Auftragsmeldungen kommen gebündelt auf eigenem Thread (ADR 0027).
        eventually(() -> prefetches.waitingPages("wiki", "DEV") == 0);
        assertThat(loaded).doesNotContain("100/a2");
    }

    @Test
    void cancellingAWaitingPartDiscardsItsPagesBeforeItRuns() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        prefetches.request("wiki", "DEV", "100", List.of("a1"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));
        // Der einzige Hintergrundplatz ist belegt: Der Teil des zweiten Space wartet.
        JobStatus waiting = prefetches.request("wiki", "OPS", "300", List.of("c1"), loader).orElseThrow();
        assertThat(waiting.state()).isEqualTo(JobState.QUEUED);

        background.cancel(waiting.parameter(BackgroundJobs.GROUP));

        eventually(() -> prefetches.waitingPages("wiki", "OPS") == 0);
        gates.get("a1").countDown();
        eventually(() -> parts().stream().allMatch(part -> part.state().terminal()));
        assertThat(loaded).containsExactly("100/a1");
    }

    @Test
    void aWaitingPageTakesTheAttachmentsOfALaterRequestToo() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        prefetches.request("wiki", "DEV", "100", List.of("a1"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));
        prefetches.request("wiki", "DEV", "200", List.of("b1", "b2"), loader).orElseThrow();
        // Eine andere Art derselben Seite: kommt hinzu, statt die erste zu ersetzen.
        prefetches.request("wiki", "DEV", "200", List.of("b2", "v1"), loader).orElseThrow();

        gates.get("a1").countDown();

        eventually(() -> part(JobState.COMPLETED).isPresent());
        assertThat(loaded).containsExactly("100/a1", "200/b1", "200/b2", "200/v1");
    }

    @Test
    void aPageRequestedWhileTheRunningPartIsBeingCancelledGetsANewPart() throws Exception {
        gates.put("a1", new CountDownLatch(1));
        JobStatus first = prefetches.request("wiki", "DEV", "100", List.of("a1", "a2"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));
        background.cancel(first.parameter(BackgroundJobs.GROUP));

        // Der Abbruch ist angefordert, der Download läuft noch: Die neue Seite gehört nicht mehr zu diesem Teil.
        JobStatus second = prefetches.request("wiki", "DEV", "200", List.of("b1"), loader).orElseThrow();
        gates.get("a1").countDown();

        assertThat(second.id()).isNotEqualTo(first.id());
        eventually(() -> loaded.contains("200/b1"));
        assertThat(loaded).doesNotContain("100/a2");
        assertThat(background.part(first.id()).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void withoutAPartNothingOfThePageWaits() {
        Jobs closed = new Jobs(new InMemoryJobStore(), executor, new JobLimits(2, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        try (AttachmentPrefetches broken = new AttachmentPrefetches(new BackgroundJobs(closed, "app"))) {
            // Ein geschlossener Dienst nimmt keinen Teil mehr an.
            closed.close();

            assertThat(broken.request("wiki", "DEV", "100", List.of("a1"), loader)).isEmpty();
            // Die App lädt dann selbst: Nichts davon darf noch im Hintergrund warten.
            assertThat(broken.waitingPages("wiki", "DEV")).isZero();
        }
    }

    @Test
    void aFailureBeforePausingStillFailsThePartAfterResuming() throws Exception {
        failing.add("a1");
        gates.put("a2", new CountDownLatch(1));
        prefetches.request("wiki", "DEV", "100", List.of("a1", "a2", "a3"), loader).orElseThrow();
        eventually(() -> entered.contains("a2"));
        String groupId = part(JobState.RUNNING).orElseThrow().parameter(BackgroundJobs.GROUP);

        background.pause(groupId);
        gates.get("a2").countDown();
        eventually(() -> part(JobState.PAUSED).isPresent());
        background.resumeGroupWithoutCaller(groupId);

        eventually(() -> part(JobState.FAILED).isPresent());
        assertThat(loaded).containsExactly("100/a2", "100/a3");
        assertThat(part(JobState.FAILED).orElseThrow().error().code())
                .isEqualTo(AttachmentPrefetches.ATTACHMENTS_MISSING);
    }

    @Test
    void aboveTheLimitOfASpaceTheCallerLoadsItself() throws Exception {
        try (AttachmentPrefetches limited = new AttachmentPrefetches(background, 3)) {
            gates.put("a1", new CountDownLatch(1));
            limited.request("wiki", "DEV", "100", List.of("a1"), loader).orElseThrow();
            eventually(() -> entered.contains("a1"));
            assertThat(limited.request("wiki", "DEV", "200", List.of("b1", "b2"), loader)).isPresent();

            assertThat(limited.request("wiki", "DEV", "300", List.of("c1", "c2"), loader)).isEmpty();
            assertThat(limited.waitingPages("wiki", "DEV")).isEqualTo(1);
            gates.get("a1").countDown();
            eventually(() -> loaded.size() == 3);
            assertThat(loaded).doesNotContain("300/c1", "300/c2");
        }
    }

    @Test
    void aPartFromBeforeARestartEndsAtOnce() throws Exception {
        BackgroundGroup group = background.start(AttachmentPrefetches.KIND, AttachmentPrefetches.TITLE,
                List.of(new BackgroundPart.Draft("wiki", "DEV", Map.of(AttachmentPrefetches.PAGE, "100"))));

        eventually(() -> background.group(group.groupId()).orElseThrow().completed());
        assertThat(loaded).isEmpty();
    }

    @Test
    void anInterruptedPartFromBeforeARestartIsClosedAtStart() throws Exception {
        InMemoryJobStore kept = new InMemoryJobStore();
        Jobs before = new Jobs(kept, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
        BackgroundJobs first = new BackgroundJobs(before, "app");
        AttachmentPrefetches earlier = new AttachmentPrefetches(first);
        gates.put("a1", new CountDownLatch(1));
        earlier.request("wiki", "DEV", "100", List.of("a1", "a2"), loader).orElseThrow();
        eventually(() -> entered.contains("a1"));
        before.close();
        gates.get("a1").countDown();
        eventually(() -> kept.all().stream().allMatch(status -> status.state().terminal()));
        earlier.close();

        Jobs after = new Jobs(kept, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
        try {
            BackgroundJobs second = new BackgroundJobs(after, "app");
            try (AttachmentPrefetches restarted = new AttachmentPrefetches(second)) {
                // Die Seiten lagen nur im Speicher: Der Teil wird abgeschlossen, nicht als unterbrochen stehen gelassen.
                assertThat(second.parts(AttachmentPrefetches.KIND)).extracting(JobStatus::state)
                        .containsExactly(JobState.CANCELLED);
            }
        } finally {
            after.close();
        }
    }

    @Test
    void withOnlyOnePlaceTheCallerLoadsAsBefore() {
        Jobs single = new Jobs(new InMemoryJobStore(), executor, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        try {
            AttachmentPrefetches one = new AttachmentPrefetches(new BackgroundJobs(single, "app"));
            assertThat(one.request("wiki", "DEV", "100", List.of("a1"), loader)).isEmpty();
        } finally {
            single.close();
        }
    }
}
