package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Anhänge ablegen“ als Auftrag (Fundstelle 24): Fortsetzen im selben Lauf nimmt die frische Anhangsliste. */
class PageAssetHarvestsTest {

    private static final String OWNER = "app";

    private final Queue<Runnable> held = new ConcurrentLinkedQueue<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.systemUTC());
    private final List<List<String>> harvested = Collections.synchronizedList(new ArrayList<>());
    private final Assets assets = assets();
    private final PageAssetHarvests harvests = new PageAssetHarvests(jobs, assets);

    @AfterEach
    void close() {
        jobs.close();
    }

    @SuppressWarnings("unchecked")
    private Assets assets() {
        Assets mocked = mock(Assets.class);
        when(mocked.harvest(any(PageAttachmentList.class), any(Consumer.class))).thenAnswer(invocation -> {
            PageAttachmentList list = invocation.getArgument(0);
            Consumer<AssetHarvest.Entry> each = invocation.getArgument(1);
            List<String> ids = list.attachments().attachments().stream().map(PageAttachment::attachmentId).toList();
            harvested.add(ids);
            List<AssetHarvest.Entry> entries = new ArrayList<>();
            for (String id : ids) {
                AssetHarvest.Entry entry = new AssetHarvest.Entry(id, AssetHarvest.Status.STORED, null, null);
                entries.add(entry);
                each.accept(entry);
            }
            return new AssetHarvest(list.sourceId(), list.attachments().pageId(), list.spaceKey(), true, entries,
                    Instant.now());
        });
        return mocked;
    }

    private static PageAttachmentList list(String... ids) {
        List<PageAttachment> attachments = Arrays.stream(ids).map(id -> new PageAttachment(id, id, id + ".txt",
                "text/plain", 10L, "1", Instant.EPOCH, null)).toList();
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("102", attachments, List.of(), "application/json",
                "rest", List.of(), Instant.EPOCH), Instant.EPOCH);
    }

    private void runAll() {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    @Test
    void resumingInTheSameRunTakesTheFreshList() {
        JobStatus started = harvests.start(list("att1"), OWNER);
        jobs.pause(started.id(), OWNER);
        runAll();
        assertThat(jobs.status(started.id(), OWNER).state()).isEqualTo(JobState.PAUSED);

        // Inzwischen kam ein Anhang dazu: Fortsetzen im selben Lauf legt ihn mit ab.
        harvests.resume(started.id(), OWNER, () -> list("att1", "att2"));
        runAll();

        JobStatus done = jobs.status(started.id(), OWNER);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(harvested).last().isEqualTo(List.of("att1", "att2"));
        assertThat(done.progress().failed()).isZero();
    }

    @Test
    void resumingWithoutTheRightAnyMoreFails() {
        JobStatus started = harvests.start(list("att1"), OWNER);
        jobs.pause(started.id(), OWNER);
        runAll();

        // Die frische Liste prüft die Rechte; ohne Recht geht es nicht mit der alten weiter.
        harvests.resume(started.id(), OWNER, () -> {
            throw new IllegalStateException("kein Recht mehr");
        });
        runAll();

        assertThat(jobs.status(started.id(), OWNER).state()).isEqualTo(JobState.FAILED);
        assertThat(harvested).isEmpty();
    }
}
