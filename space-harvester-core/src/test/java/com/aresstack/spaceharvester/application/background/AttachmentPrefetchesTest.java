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
    private final AttachmentPrefetches.Loader loader = (sourceId, pageId, attachmentId) -> {
        entered.add(attachmentId);
        CountDownLatch gate = gates.get(attachmentId);
        if (gate != null) {
            gate.await(10, TimeUnit.SECONDS);
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

        background.resume(groupId);

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
