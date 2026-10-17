package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** Diagnoseereignisse der Aufträge (docs/DIAGNOSTICS.md). */
class JobsDiagnosticsTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final InMemoryJobEventStore events = new InMemoryJobEventStore();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), events,
            sourceId -> Optional.of("PROXY proxy.intern:8080 (pac:resolved)"), executor,
            new JobLimits(2, 5, Duration.ofHours(1), Duration.ofHours(1)), clock);

    @Test
    void aSuccessfulJobRecordsItsLifeAndPhases() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            context.phase("Exports", "Einlesen");
            context.phase("Exports", "Einlesen");
            context.record(JobEvent.Draft.info("Exports", "Kein Bestand im Cache"));
            context.phase("Exports", "Konvertieren (docx)");
            context.processed(2);
            context.written(2);
        }));
        executor.runNext();

        assertThat(jobs.events(job.id(), "client-a")).extracting(JobEvent::message).containsExactly(
                "Auftrag angenommen", "Auftrag gestartet", "Phase: Einlesen", "Kein Bestand im Cache",
                "Phase: Konvertieren (docx)", "Auftrag abgeschlossen: 2 Seiten verarbeitet, 2 geschrieben, "
                        + "0 gescheitert");
        assertThat(jobs.events(job.id())).extracting(JobEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(jobs.events(job.id()).get(3).phase()).isEqualTo("Einlesen");
        assertThat(jobs.events(job.id())).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(JobType.EXPORT);
            assertThat(event.sourceId()).isEqualTo("wiki");
            assertThat(event.partitionKey()).isEqualTo("DEV");
        });
    }

    @Test
    void aFailureCarriesComponentPhaseCodeHttpStatusRouteTlsAndMaskedDetail() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            context.phase("Exports", "Einlesen");
            SSLHandshakeException tls = new SSLHandshakeException("PKIX path building failed");
            throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE,
                    "GET https://bob:pw@wiki.intern/rest/api/content/4711?token=abc (HTTP 502)",
                    new IllegalStateException("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.s1gn4tur3x", tls));
        }));
        executor.runNext();

        JobEvent failure = jobs.events(job.id()).getLast();
        assertThat(failure.severity()).isEqualTo(JobEvent.Severity.ERROR);
        assertThat(failure.category()).isEqualTo(JobEvent.Category.NETWORK);
        assertThat(failure.code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(failure.phase()).isEqualTo("Einlesen");
        assertThat(failure.component()).isEqualTo("JobsDiagnosticsTest");
        assertThat(failure.httpStatus()).isEqualTo(502);
        assertThat(failure.proxyRoute()).isEqualTo("PROXY proxy.intern:8080 (pac:resolved)");
        assertThat(failure.tlsFinding()).isEqualTo("SSLHandshakeException: PKIX path building failed");
        assertThat(failure.detail()).contains("SourceAccessException", "https://wiki.intern/…", "Authorization: ***")
                .doesNotContain("bob:pw", "4711", "token=abc", "eyJ");
    }

    @Test
    void intendedFailuresHaveNoStackTrace() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> {
            throw new JobFailedException("SYNC_INCOMPLETE", "Space ließ sich nicht vollständig einlesen");
        }));
        executor.runNext();

        JobEvent failure = jobs.events(job.id()).getLast();
        assertThat(failure.code()).isEqualTo("SYNC_INCOMPLETE");
        assertThat(failure.detail()).isNull();
        assertThat(failure.proxyRoute()).isNull();
    }

    @Test
    void cancellingAQueuedJobIsRecorded() {
        JobStatus first = jobs.submit(JobType.SYNC, "wiki", "A", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.SYNC, "wiki", "B", "client-a", JobTask.blocking(context -> { }));
        JobStatus third = jobs.submit(JobType.SYNC, "wiki", "C", "client-a", JobTask.blocking(context -> { }));

        jobs.cancel(third.id());

        assertThat(jobs.events(third.id())).extracting(JobEvent::code).containsExactly(null, "CANCELLED");
        assertThat(jobs.events(first.id())).extracting(JobEvent::message)
                .containsExactly("Auftrag angenommen", "Auftrag gestartet");
    }

    @Test
    void onlyTheOwnerSeesTheEvents() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-b", JobTask.blocking(context -> { }));
        executor.runNext();
        executor.runNext();

        assertThatThrownBy(() -> jobs.events(job.id(), "client-b")).isInstanceOf(UnknownJobException.class);
        assertThat(jobs.recentEvents("client-a", 50)).isNotEmpty()
                .allSatisfy(event -> assertThat(event.jobId()).isEqualTo(job.id()));
        assertThat(jobs.recentEvents("client-a", 2)).hasSize(2);
    }

    @Test
    void eventsExpireWithTheirJob() {
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context -> { }));
        executor.runNext();

        clock.advance(Duration.ofHours(2));
        jobs.purgeExpired();

        assertThat(events.events(job.id())).isEmpty();
    }

    @Test
    void aFailingEventStoreNeverFailsTheJob() {
        JobEventStore broken = new InMemoryJobEventStore() {
            @Override
            public void append(JobEvent event) {
                throw new IllegalStateException("Ablage voll");
            }
        };
        Jobs fragile = new Jobs(new InMemoryJobStore(), broken, RouteLookup.NONE, executor,
                new JobLimits(1, 5, Duration.ofHours(1)), clock);

        JobStatus job = fragile.submit(JobType.EXPORT, "wiki", "DEV", "client-a", JobTask.blocking(context ->
                context.phase("Exports", "Einlesen")));
        executor.runNext();

        assertThat(fragile.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
    }
}
