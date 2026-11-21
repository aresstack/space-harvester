package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Beenden mit laufendem Hintergrundauftrag am echten {@link Harvester} (Abnahme #1030): scheitert der Teil beim
 * Beenden, weil seine Bausteine schließen, gilt er als unterbrochen und setzt nach dem Neustart fort.
 */
class HarvesterBackgroundCloseTest {

    @TempDir
    Path data;

    @Test
    void aBackgroundPartFailingDuringCloseIsInterruptedAndResumesAfterTheRestart() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        String partId;
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            // Wie „Seiten laden“, wenn der Planer beim Beenden die Abrufe abwirft: der Teil scheitert statt abzubrechen.
            harvester.backgroundJobs().register(new BackgroundKind("probe", part -> JobTask.blocking(context -> {
                running.countDown();
                try {
                    while (true) {
                        context.checkpoint();
                        Thread.sleep(10);
                    }
                } catch (JobCancelledException e) {
                    throw new JobFailedException("PAGES_MISSING", "erfundene abgeworfene Abrufe");
                }
            })));
            BackgroundGroup group = harvester.backgroundJobs().start("probe", "Probe", List.of(
                    new BackgroundPart.Draft("wiki", "ALPHA", Map.of())));
            partId = group.parts().getFirst().id();
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        }

        try (Harvester restarted = Harvester.builder().dataDirectory(data).build()) {
            JobStatus after = restarted.backgroundJobs().groups().getFirst().parts().getFirst();
            assertThat(after.id()).isEqualTo(partId);
            assertThat(after.state()).isEqualTo(JobState.FAILED);
            assertThat(after.error().code()).isEqualTo(JobLifecycleState.INTERRUPTED);

            restarted.backgroundJobs().register(new BackgroundKind("probe",
                    part -> JobTask.blocking(context -> context.processed(1))));
            assertThat(restarted.backgroundJobs().resumeInterrupted()).extracting(JobStatus::id)
                    .containsExactly(partId);
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            JobStatus done = after;
            while (!done.state().terminal() || done.state() == JobState.FAILED) {
                if (Instant.now().isAfter(deadline)) {
                    break;
                }
                Thread.sleep(20);
                done = restarted.backgroundJobs().groups().getFirst().parts().getFirst();
            }
            assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        }
    }
}
