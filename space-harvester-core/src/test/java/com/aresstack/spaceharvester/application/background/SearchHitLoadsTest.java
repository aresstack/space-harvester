package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Suchtreffer laden“ auf eigener Spur (Fundstelle 13, Abnahme #1085); nur erfundene Quellen und Seiten. */
class SearchHitLoadsTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final CountDownLatch gate = new CountDownLatch(1);
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean refuse = new AtomicBoolean();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failing.contains(key.pageId())) {
            throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "erfunden", null);
        }
    }, new AcquisitionScheduler.Limits(4, 0, 4, 50));
    private final Jobs jobs = new Jobs(new InMemoryJobStore() {
        @Override
        public void save(JobStatus status) {
            if (refuse.get() && find(status.id()).isEmpty()) {
                throw new IllegalStateException("Speicher voll");
            }
            super.save(status);
        }
    }, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final SearchHitLoads loads = new SearchHitLoads(background, scheduler, APP, key -> Optional.of("DEV"),
            (caller, key) -> {
                requests.incrementAndGet();
                return scheduler.request(caller, key, AcquisitionScheduler.Priority.FOREGROUND);
            });

    @AfterEach
    void close() throws Exception {
        refuse.set(false);
        gate.countDown();
        loads.close();
        scheduler.close();
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

    private Map<PageKey, AcquisitionScheduler.Ticket> tickets(String... ids) {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = new LinkedHashMap<>();
        for (String id : ids) {
            PageKey key = new PageKey("wiki", id);
            tickets.put(key, scheduler.request(APP, key, AcquisitionScheduler.Priority.FOREGROUND));
        }
        return tickets;
    }

    private Optional<BackgroundGroup> onlyGroup() {
        List<BackgroundGroup> groups = background.groups();
        return groups.size() == 1 ? Optional.of(groups.getFirst()) : Optional.empty();
    }

    @Test
    void adoptedTicketsAreCountedWithoutASingleNewRequest() throws Exception {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2");

        loads.offer("stapel-1", APP, tickets);
        eventually(() -> onlyGroup().isPresent());
        gate.countDown();
        eventually(() -> onlyGroup().orElseThrow().completed());

        BackgroundGroup done = onlyGroup().orElseThrow();
        assertThat(done.title()).isEqualTo(SearchHitLoads.TITLE);
        assertThat(done.written()).isEqualTo(2);
        // Abnahme #1085, Punkt 6: Die Übernahme fordert nichts neu an.
        assertThat(requests).hasValue(0);
    }

    @Test
    void aFailedPageCountsRedWithAReasonWhileTheOthersKeepLoading() throws Exception {
        failing.add("2");
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2", "3");

        loads.offer("stapel-1", APP, tickets);
        eventually(() -> onlyGroup().isPresent());
        gate.countDown();
        eventually(() -> onlyGroup().orElseThrow().finished());

        JobStatus part = onlyGroup().orElseThrow().parts().getFirst();
        assertThat(part.state()).isEqualTo(JobState.FAILED);
        assertThat(part.error().code()).isEqualTo(SearchHitLoads.PAGES_MISSING);
        assertThat(part.progress().written()).isEqualTo(2);
        assertThat(part.progress().failed()).isEqualTo(1);
        assertThat(tickets.get(new PageKey("wiki", "3")).done().getNow(false)).isTrue();
    }

    @Test
    void retryLoadsOnlyTheFailedPagesInTheSameEntry() throws Exception {
        failing.add("2");
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2", "3");
        loads.offer("stapel-1", APP, tickets);
        eventually(() -> onlyGroup().isPresent());
        gate.countDown();
        eventually(() -> onlyGroup().orElseThrow().finished());
        JobStatus part = onlyGroup().orElseThrow().parts().getFirst();
        // Angelo 26.11.2026: verständlich auf Deutsch, ↻ angeboten, der Grund je Seite in Worten in der Diagnose.
        assertThat(part.error().message()).contains("↻ lädt nur ihn erneut").doesNotContain("nächste Suche");
        assertThat(jobs.events(part.id())).anySatisfy(event -> assertThat(event.message())
                .contains("Quelle nicht erreichbar", "(UNAVAILABLE)"));
        assertThat(com.aresstack.spaceharvester.application.jobs.JobLifecycleState.of(part).actions())
                .contains(com.aresstack.spaceharvester.application.jobs.JobAction.RETRY_FAILED);

        failing.clear();
        background.retryFailed(part.id(), null);
        eventually(() -> jobs.status(part.id(), "app").state() == JobState.COMPLETED);

        JobStatus again = jobs.status(part.id(), "app");
        // Nur Seite 2 neu angefordert, im selben Eintrag; 1 und 3 bleiben, wie sie sind.
        assertThat(requests).hasValue(1);
        assertThat(again.progress().discovered()).isEqualTo(1);
        assertThat(again.progress().written()).isEqualTo(1);
        assertThat(again.progress().failed()).isZero();
    }

    @Test
    void aFailedStartLeavesTheTicketsOfTheSearchAlone() throws Exception {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2");
        refuse.set(true);

        loads.offer("stapel-1", APP, tickets);
        TimeUnit.MILLISECONDS.sleep(600);

        assertThat(background.groups()).isEmpty();
        assertThat(tickets.values()).noneMatch(ticket -> ticket.done().isCancelled());
        gate.countDown();
        eventually(() -> tickets.values().stream().allMatch(ticket -> ticket.done().isDone()));
        assertThat(tickets.values()).allMatch(ticket -> ticket.done().getNow(false));
    }

    // Wiederholt: Das Abbrechen trifft den Start des Teils in wechselnder Reihenfolge; ohne den Fix scheiterte etwa
    // jeder 200. Lauf (roter Core-Build 23.11.2026).
    @org.junit.jupiter.api.RepeatedTest(50)
    void cancellingFromTheHitListCancelsTheGroupAndWithdrawsItsTickets() throws Exception {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2");
        loads.offer("stapel-1", APP, tickets);
        eventually(() -> onlyGroup().isPresent());

        loads.cancel("stapel-1");

        eventually(() -> onlyGroup().orElseThrow().parts().stream().allMatch(part -> part.state()
                == JobState.CANCELLED));
        // Die Abrufe zieht die Auftragsmeldung zurück, und die kommt auf dem Zustell-Thread erst nach dem Zustand an.
        eventually(() -> tickets.values().stream().allMatch(ticket -> ticket.done().isCancelled()));
    }

    @Test
    void pausingWithdrawsTheAdoptedTicketsAndResumingRequestsThemAgain() throws Exception {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = tickets("1", "2");
        loads.offer("stapel-1", APP, tickets);
        eventually(() -> onlyGroup().isPresent());
        String groupId = onlyGroup().orElseThrow().groupId();

        background.pause(groupId);

        eventually(() -> tickets.values().stream().allMatch(ticket -> ticket.done().isCancelled()));
        assertThat(requests).hasValue(0);
        gate.countDown();
        background.resumeGroupWithoutCaller(groupId);
        eventually(() -> background.group(groupId).orElseThrow().completed());
        assertThat(requests).hasValue(2);
        assertThat(background.group(groupId).orElseThrow().written()).isEqualTo(2);
    }

    @Test
    void aResumedPartRequestsEachMissingPageOnce() throws Exception {
        // Nach einem Neustart fehlen die Abrufe: Der Teil fordert an, was in seinen Angaben steht.
        gate.countDown();
        BackgroundGroup group = background.start(SearchHitLoads.KIND, SearchHitLoads.TITLE, List.of(
                new BackgroundPart.Draft("wiki", "DEV", Map.of(SearchHitLoads.PAGES, "7\n8\n9"))));

        eventually(() -> background.group(group.groupId()).orElseThrow().completed());

        assertThat(requests).hasValue(3);
        assertThat(background.group(group.groupId()).orElseThrow().written()).isEqualTo(3);
    }

    @Test
    void aPartOnItsOwnLaneDoesNotWaitBehindOtherBackgroundWork() throws Exception {
        // Zwei Plätze, einer für Hintergrund; den belegt ein anderer Teil, der nicht endet.
        CountDownLatch other = new CountDownLatch(1);
        background.register(new BackgroundKind("other", part -> JobTask.blocking(context -> other.await(10,
                TimeUnit.SECONDS))));
        background.start("other", "Anderes", List.of(new BackgroundPart.Draft("wiki", "A", Map.of())));
        try {
            loads.offer("stapel-1", APP, tickets("1"));
            eventually(() -> background.groups().stream().anyMatch(g -> SearchHitLoads.TITLE.equals(g.title())));
            gate.countDown();

            eventually(() -> background.groups().stream().filter(g -> SearchHitLoads.TITLE.equals(g.title()))
                    .allMatch(BackgroundGroup::completed));
        } finally {
            other.countDown();
        }
    }

    @Test
    void onlyNamedKindsMayUseTheirOwnLane() {
        assertThatThrownBy(() -> new BackgroundKind("other", part -> JobTask.blocking(context -> { }), () -> true,
                false, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
