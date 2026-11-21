package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Seiten nachladen“ als Hintergrundauftrag, je Space ein Unterauftrag; nur erfundene Quellen und Seiten. */
class PageLoadsTest {

    private final Set<PageKey> cache = ConcurrentHashMap.newKeySet();
    private final Map<String, String> spaces = Map.of("1", "ALPHA", "2", "ALPHA", "3", "BETA", "4", "BETA");
    private final BackgroundJobsTest.Steps executor = new BackgroundJobsTest.Steps();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(4, 10, Duration.ofHours(1)),
            new BackgroundJobsTest.Ticking());
    private final AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
        if (key.pageId().equals("4")) {
            throw new IllegalStateException("erfundener Fehler");
        }
        cache.add(key);
    }, new AcquisitionScheduler.Limits(2, 0, 2, 50));
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final PageLoads loads = new PageLoads(background, scheduler, AccessContext.technicalClient("app"),
            cache::contains, key -> Optional.ofNullable(spaces.get(key.pageId())));

    @AfterEach
    void close() throws Exception {
        scheduler.close();
        jobs.close();
    }

    private static List<PageKey> pages(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new PageKey("wiki", id)).toList();
    }

    @Test
    void missingPagesLoadInOneBackgroundJobPerSpace() {
        cache.add(new PageKey("wiki", "2"));

        BackgroundGroup group = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "2", "3", "4")).orElseThrow();
        executor.runAll();

        assertThat(group.parts()).extracting(JobStatus::partitionKey).containsExactlyInAnyOrder("ALPHA", "BETA");
        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.finished()).isTrue();
        assertThat(done.title()).isEqualTo("Nachbarseiten laden");
        assertThat(done.written()).isEqualTo(2);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(cache).contains(new PageKey("wiki", "1"), new PageKey("wiki", "3"));
        // Die gescheiterte Seite lässt ihren Unterauftrag scheitern, damit „Fortsetzen“ sie erneut versucht.
        JobStatus beta = done.parts().stream().filter(part -> "BETA".equals(part.partitionKey())).findFirst()
                .orElseThrow();
        assertThat(beta.state()).isEqualTo(JobState.FAILED);
        assertThat(beta.error().code()).isEqualTo(PageLoads.PAGES_MISSING);
        assertThat(beta.lifecycle().actions()).contains(com.aresstack.spaceharvester.application.jobs.JobAction.RESUME);
        assertThat(done.parts().stream().filter(part -> "ALPHA".equals(part.partitionKey())))
                .extracting(JobStatus::state).containsOnly(JobState.COMPLETED);
    }

    @Test
    void moreMissingPagesThanTheSchedulerHoldsStillLoadInWindows() throws Exception {
        java.util.List<PageKey> many = new java.util.ArrayList<>();
        java.util.Map<String, String> all = new java.util.HashMap<>();
        for (int i = 0; i < 120; i++) {
            many.add(new PageKey("wiki", "p" + i));
        }
        AcquisitionScheduler small = new AcquisitionScheduler((caller, key) -> cache.add(key),
                new AcquisitionScheduler.Limits(2, 0, 2, 10));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-small");
            PageLoads windowed = new PageLoads(own, small, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("DELTA"));
            BackgroundGroup group = windowed.load(AccessContext.technicalClient("app"), "Viele Seiten", many)
                    .orElseThrow();
            executor.runAll();

            BackgroundGroup done = own.group(group.groupId()).orElseThrow();
            assertThat(done.completed()).isTrue();
            assertThat(done.written()).isEqualTo(120);
        } finally {
            small.close();
        }
    }

    @Test
    void nothingStartsWhenEveryPageIsCached() {
        cache.addAll(pages("1", "3"));

        assertThat(loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden", pages("1", "3")))
                .isEmpty();
        assertThat(background.groups()).isEmpty();
    }

    @Test
    void aResumedJobLoadsOnlyWhatIsStillMissing() {
        BackgroundGroup group = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "2")).orElseThrow();
        background.pause(group.groupId());
        executor.runAll();
        cache.add(new PageKey("wiki", "1"));

        background.resume(group.groupId());
        executor.runAll();

        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.completed()).isTrue();
        assertThat(done.written()).isEqualTo(1);
        assertThat(cache).contains(new PageKey("wiki", "2"));
    }

    @Test
    void theBackgroundNeverLoadsWithAUsersSignIn() {
        AccessContext delegated = AccessContext.endUser("erfunden", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));

        assertThatThrownBy(() -> loads.load(delegated, "x", pages("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
