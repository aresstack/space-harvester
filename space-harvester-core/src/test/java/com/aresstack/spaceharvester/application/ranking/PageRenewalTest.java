package com.aresstack.spaceharvester.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.PageLoads;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Veraltete Treffer als Hintergrundauftrag (Fundstelle 10); nur erfundene Quellen und Seiten. */
class PageRenewalTest {

    @Test
    void staleHitsOfACallerGoAsOneVisibleGroupThatAlsoLoadsCachedPages() throws Exception {
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(2, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
        }, new AcquisitionScheduler.Limits(2, 0, 2, 50));
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        // Alles liegt im Cache: ohne refresh lüde der Teil nichts.
        PageLoads loads = new PageLoads(background, scheduler, AccessContext.technicalClient("app"), key -> true,
                key -> Optional.of("ALPHA"));
        PageQueries pages = mock(PageQueries.class);
        try (PageRenewal renewal = new PageRenewal(pages, Runnable::run, loads)) {
            AccessContext caller = AccessContext.technicalClient("app");

            renewal.renew(caller, new PageKey("wiki", "1"));
            renewal.renew(caller, new PageKey("wiki", "2"));

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (background.groups().isEmpty() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(background.groups()).singleElement().satisfies(group -> {
                assertThat(group.title()).isEqualTo(PageRenewal.TITLE);
                assertThat(group.parts()).singleElement().satisfies(part -> {
                    assertThat(part.parameter(PageLoads.REFRESH)).isEqualTo("true");
                    assertThat(part.parameter(PageLoads.CHANGE_TRIGGER)).as("ADR 0030: Anlass bleibt die Suche")
                            .isEqualTo("SEARCH");
                    assertThat(List.of(part.parameter(PageLoads.PAGES).split("\n")))
                            .containsExactlyInAnyOrder("1", "2");
                });
            });
            verifyNoInteractions(pages);
        } finally {
            scheduler.close();
            jobs.close();
        }
    }

    @Test
    void withASingleSlotTheFormerWayRenewsInsteadOfAPartThatNeverRuns() throws Exception {
        // Abnahme #1080: Bei nur einem Platz liefe kein Hintergrundteil; dann lädt der bisherige Weg nach.
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.systemUTC());
        AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
        }, new AcquisitionScheduler.Limits(2, 0, 2, 50));
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        PageLoads loads = new PageLoads(background, scheduler, AccessContext.technicalClient("app"), key -> true,
                key -> Optional.of("ALPHA"));
        PageQueries pages = mock(PageQueries.class);
        try (PageRenewal renewal = new PageRenewal(pages, Runnable::run, loads)) {
            assertThat(loads.canRun()).isFalse();
            AccessContext caller = AccessContext.technicalClient("app");

            renewal.renew(caller, new PageKey("wiki", "3"));

            verify(pages).page(eq(caller), eq("wiki"), eq("3"), anyBoolean());
            assertThat(background.groups()).as("kein Teil, der nie liefe").isEmpty();
        } finally {
            scheduler.close();
            jobs.close();
        }
    }
}
