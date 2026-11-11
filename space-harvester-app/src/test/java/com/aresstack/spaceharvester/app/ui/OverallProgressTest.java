package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aresstack.spaceharvester.app.event.JobProgressEvent;
import com.aresstack.spaceharvester.app.event.JobProgressEvent.Outcome;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Der Gesamtstand mehrerer Aufträge (Angelo 11.11.2026): jeder Auftrag zählt mit seiner geschätzten Dauer, der
 * Durchgang umfasst alle seit dem letzten Ende offenen Aufträge.
 */
class OverallProgressTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    /** Ein Bild dauert im Mittel eine Sekunde, ein Anhang drei (Durchschnitt des Core). */
    private static final Function<JobType, Duration> AVERAGES = type -> type == JobType.ATTACHMENTS
            ? Duration.ofSeconds(3) : Duration.ofSeconds(1);

    /**
     * Ein großer, langsamer Export bei 10 % und ein schneller Bilderauftrag bei 90 %: nach Dauer gewichtet steht der
     * Gesamtstand nahe beim Export, nicht in der Mitte. Die Bilder messen ihr Tempo erst ab dem Laden; die lange
     * Vorbereitung davor zählt nicht.
     */
    @Test
    void aBigSlowJobWeighsMoreThanASmallFastOne() {
        OverallProgress overall = new OverallProgress();
        JobStatus export = running("e1", JobType.EXPORT, 1000, 100, 0, T0.minusSeconds(100));
        JobStatus images = staged("b1", JobType.ASSETS, 1000, 0, T0.minusSeconds(1000), JobStage.DOWNLOADING);
        overall.observe(List.of(export, images), T0.minusSeconds(90), AVERAGES);
        images = staged("b1", JobType.ASSETS, 1000, 900, T0.minusSeconds(1000), JobStage.DOWNLOADING);
        overall.observe(List.of(export, images), T0, AVERAGES);

        OverallProgress.Estimate estimate = overall.estimate(List.of(export, images), T0, AVERAGES);

        // Export: 105 s je 110 Elemente; Bilder: (10 s + 90 s) je 910 Dateien seit dem Laden.
        double exportPerUnit = 105.0 / 110;
        double imagePerFile = 100.0 / 910;
        double expected = (100 * exportPerUnit + 900 * imagePerFile) / (1100 * exportPerUnit + 1000 * imagePerFile);
        assertThat(estimate.fraction()).isCloseTo(expected, within(0.001));
        assertThat(estimate.fraction()).as("nach Dauer, nicht nach Zahl").isBetween(0.15, 0.19);
        assertThat(estimate.indeterminate()).isFalse();
    }

    @Test
    void withNothingMeasurableTheBarRunsIndeterminate() {
        OverallProgress overall = new OverallProgress();
        JobStatus inventory = running("e1", JobType.EXPORT, 0, 0, 0, T0.minusSeconds(5));
        overall.observe(List.of(inventory), T0, AVERAGES);

        OverallProgress.Estimate estimate = overall.estimate(List.of(inventory), T0, AVERAGES);
        assertThat(estimate.indeterminate()).isTrue();
        assertThat(estimate.remaining()).isNull();
        JobProgressEvent event = overall.event(List.of(inventory), T0, AVERAGES, 4);
        assertThat(event.indeterminate()).isTrue();
        assertThat(event.text()).isEqualTo("Aufträge: 1 läuft");
    }

    /**
     * Fertige zählen im Durchgang voll, bis keiner mehr offen ist; dann „Fertig“. Der nächste Auftrag beginnt einen
     * neuen Durchgang, ohne die alten.
     */
    @Test
    void theBatchCountsFinishedJobsFullyUntilAllAreDoneThenStartsAgain() {
        OverallProgress overall = new OverallProgress();
        JobStatus a = running("a", JobType.SYNC, 100, 50, 0, T0.minusSeconds(50));
        JobStatus b = running("b", JobType.SYNC, 100, 50, 0, T0.minusSeconds(50));
        overall.observe(List.of(a, b), T0, AVERAGES);
        JobProgressEvent first = overall.event(List.of(a, b), T0, AVERAGES, 4);
        assertThat(first.outcome()).isEqualTo(Outcome.RUNNING);
        assertThat(first.text()).isEqualTo("Aufträge: 2 laufen · 50 % · noch < 1 min");
        assertThat(first.done()).isEqualTo(500);

        Instant later = T0.plusSeconds(10);
        a = completed("a", JobType.SYNC, 100, T0.minusSeconds(50), later);
        b = running("b", JobType.SYNC, 100, 60, 0, T0.minusSeconds(50));
        overall.observe(List.of(a, b), later, AVERAGES);
        JobProgressEvent second = overall.event(List.of(a, b), later, AVERAGES, 4);
        assertThat(second.text()).startsWith("Aufträge: 1 läuft · ");
        assertThat(second.done()).as("der fertige zählt voll").isGreaterThan(first.done());

        b = completed("b", JobType.SYNC, 100, T0.minusSeconds(50), later.plusSeconds(30));
        overall.observe(List.of(a, b), later.plusSeconds(30), AVERAGES);
        JobProgressEvent end = overall.event(List.of(a, b), later.plusSeconds(30), AVERAGES, 4);
        assertThat(end.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(end.text()).isEqualTo("Aufträge: 2 fertig");

        JobStatus c = running("c", JobType.EXPORT, 0, 0, 0, later.plusSeconds(60));
        List<JobStatus> jobs = List.of(c, a, b);
        overall.observe(jobs, later.plusSeconds(61), AVERAGES);
        assertThat(overall.batch(jobs)).extracting(JobStatus::id).containsExactly("c");
        assertThat(overall.event(jobs, later.plusSeconds(61), AVERAGES, 4).text()).isEqualTo("Aufträge: 1 läuft");
    }

    @Test
    void pausedJobsKeepTheirShareAndSayAngehalten() {
        OverallProgress overall = new OverallProgress();
        JobStatus paused = paused("e1", JobType.EXPORT, 100, 40);
        overall.observe(List.of(paused), T0, AVERAGES);

        JobProgressEvent event = overall.event(List.of(paused), T0, AVERAGES, 4);
        assertThat(event.outcome()).isEqualTo(Outcome.PAUSED);
        assertThat(event.text()).isEqualTo("Aufträge: 1 angehalten · 36 %");
        assertThat(event.done()).isEqualTo(364);
        assertThat(event.total()).isEqualTo(OverallProgress.SCALE);
    }

    /** Bilder hinter einem angehaltenen oder abgebrochenen Export warten nur; sie halten den Durchgang nicht offen. */
    @Test
    void imagesWaitingForAStoppedExportDoNotKeepTheBatchRunning() {
        OverallProgress overall = new OverallProgress();
        JobStatus export = running("e1", JobType.EXPORT, 100, 20, 0, T0.minusSeconds(20));
        JobStatus images = awaiting("b1", "e1");
        overall.observe(List.of(export, images), T0, AVERAGES);
        assertThat(overall.event(List.of(export, images), T0, AVERAGES, 4).text())
                .startsWith("Aufträge: 1 läuft, 1 wartet · ");

        export = paused("e1", JobType.EXPORT, 100, 20);
        overall.observe(List.of(export, images), T0.plusSeconds(5), AVERAGES);
        assertThat(overall.event(List.of(export, images), T0.plusSeconds(5), AVERAGES, 4).outcome())
                .isEqualTo(Outcome.PAUSED);

        export = cancelled("e1", JobType.EXPORT, 100, 20);
        overall.observe(List.of(export, images), T0.plusSeconds(10), AVERAGES);
        JobProgressEvent end = overall.event(List.of(export, images), T0.plusSeconds(10), AVERAGES, 4);
        assertThat(end.outcome()).isEqualTo(Outcome.CANCELLED);
        assertThat(end.text()).isEqualTo("Aufträge: 1 abgebrochen");
    }

    /**
     * Restzeit: das Größere aus der längsten offenen Dauer und der Summe offener Dauern geteilt durch die Zahl der
     * gleichzeitig laufenden; keine, solange eine offene Größe nur angenommen ist.
     */
    @Test
    void theRemainingTimeTakesTheLongerOfTheLongestAndTheSharedWork() {
        OverallProgress overall = new OverallProgress();
        JobStatus slow = running("a", JobType.SYNC, 1000, 100, 0, T0.minusSeconds(100));
        JobStatus fast = running("b", JobType.SYNC, 1000, 900, 0, T0.minusSeconds(900));
        overall.observe(List.of(slow, fast), T0, AVERAGES);

        OverallProgress.Estimate estimate = overall.estimate(List.of(slow, fast), T0, AVERAGES);
        // a: 900 offene Seiten zu je 105/110 s ≈ 859 s; länger als (859 + 99) / 2.
        assertThat(estimate.remaining()).isEqualTo(Duration.ofSeconds(859));
        assertThat(overall.event(List.of(slow, fast), T0, AVERAGES, 4).text()).endsWith(" · noch ≈ 15 min");

        JobStatus waiting = queued("c", JobType.SYNC);
        overall.observe(List.of(slow, fast, waiting), T0, AVERAGES);
        assertThat(overall.estimate(List.of(slow, fast, waiting), T0, AVERAGES).remaining())
                .as("Größe des wartenden nur angenommen").isNull();
    }

    /** Laufend nie ganz voll: „100 %“ und der volle Balken heißen nur „Fertig“. */
    @Test
    void aRunningBarNeverReadsAsFinished() {
        OverallProgress overall = new OverallProgress();
        JobStatus converting = running("e1", JobType.EXPORT, 100, 100, 100, T0.minusSeconds(60));
        overall.observe(List.of(converting), T0, AVERAGES);

        JobProgressEvent event = overall.event(List.of(converting), T0, AVERAGES, 4);
        assertThat(event.outcome()).isEqualTo(Outcome.RUNNING);
        assertThat(event.done()).isEqualTo(OverallProgress.SCALE - 1);
        assertThat(event.text()).contains(" · 99 %");
        assertThat(OverallProgress.percent(0.999)).isEqualTo(99);
    }

    /** Ein fortgesetzter Auftrag beginnt mit leeren Zählern und zählt mit seiner zuletzt gesehenen Größe. */
    @Test
    void aResumedJobKeepsItsLastKnownSize() {
        OverallProgress overall = new OverallProgress();
        JobStatus big = running("e1", JobType.EXPORT, 5000, 100, 0, T0.minusSeconds(100));
        JobStatus small = running("e2", JobType.EXPORT, 100, 50, 0, T0.minusSeconds(50));
        overall.observe(List.of(big, small), T0, AVERAGES);
        JobStatus resumed = queued("e1", JobType.EXPORT);
        overall.observe(List.of(resumed, small), T0.plusSeconds(1), AVERAGES);

        OverallProgress.Part part = overall.part(resumed, T0.plusSeconds(1), AVERAGES);
        assertThat(part.assumed()).isTrue();
        assertThat(part.total()).as("5500 Elemente zum zuletzt gemessenen Tempo")
                .isCloseTo(5500 * (105.0 / 110), within(0.5));
    }

    @Test
    void onlyAGroupWithSomethingRunningGetsABar() {
        assertThat(OverallProgress.running(List.of(running("e1", JobType.EXPORT, 10, 1, 0, T0), awaiting("b1", "e1"))))
                .isTrue();
        assertThat(OverallProgress.running(List.of(completed("e1", JobType.EXPORT, 10, T0, T0),
                staged("b1", JobType.ASSETS, 10, 0, T0, JobStage.PREPARED)))).as("nur wartend").isFalse();
        assertThat(OverallProgress.running(List.of(paused("e1", JobType.EXPORT, 10, 4)))).isFalse();
    }

    private static JobStatus running(String id, JobType type, long discovered, long processed, long written,
                                     Instant started) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(discovered, processed, written, 0), null, false, started.minusSeconds(1), started,
                null, null, null, Map.of(), false, false, null);
    }

    private static JobStatus staged(String id, JobType type, long discovered, long processed, Instant started,
                                    JobStage stage) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.RUNNING,
                new JobProgress(discovered, processed, processed, 0), null, false, started.minusSeconds(1), started,
                null, null, null, Map.of("exportId", "e1"), false, false, null, stage);
    }

    private static JobStatus completed(String id, JobType type, long discovered, Instant started, Instant finished) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.COMPLETED,
                new JobProgress(discovered, discovered, discovered, 0), null, false, started.minusSeconds(1), started,
                finished, null, null, Map.of(), false, false, null);
    }

    private static JobStatus paused(String id, JobType type, long discovered, long processed) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.PAUSED,
                new JobProgress(discovered, processed, 0, 0), null, false, T0.minusSeconds(61), T0.minusSeconds(60),
                T0.minusSeconds(20), null, null, Map.of(), false, false, null);
    }

    private static JobStatus cancelled(String id, JobType type, long discovered, long processed) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.CANCELLED,
                new JobProgress(discovered, processed, 0, 0), null, false, T0.minusSeconds(61), T0.minusSeconds(60),
                T0.minusSeconds(20), null, null, Map.of(), false, false, null);
    }

    private static JobStatus queued(String id, JobType type) {
        return new JobStatus(id, type, "wiki", "OPS", "local", JobState.QUEUED, JobProgress.NONE, null, false,
                T0.minusSeconds(1), null, null, null, null, Map.of(), false, false, null);
    }

    private static JobStatus awaiting(String id, String exportId) {
        return new JobStatus(id, JobType.ASSETS, "wiki", "OPS", "local", JobState.QUEUED, JobProgress.NONE, null,
                false, T0.minusSeconds(1), null, null, null, null, Map.of("exportId", exportId), false, false, null,
                JobStage.AWAITING);
    }
}
