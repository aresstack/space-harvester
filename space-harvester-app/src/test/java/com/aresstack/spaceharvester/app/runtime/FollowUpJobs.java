package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Duration;
import java.util.List;

/**
 * Wartet in Tests auf die Folgeaufträge, die der Kern nach einem Abgleich oder Export selbst einplant: Die Anreicherung
 * ({@link JobType#ENRICHMENT}) füllt Volltextindex, Akronyme und Begriffe, das Beschaffen der Bilder
 * ({@link JobType#ASSETS}) folgt dem Export. Abgleich und Export warten nicht darauf.
 */
public final class FollowUpJobs {

    /** So lange wartet {@link #awaitEnrichments} höchstens. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    private FollowUpJobs() {
    }

    /**
     * Wartet nach einem beendeten Abgleich, bis seine Anreicherung eingeplant und, wie jeder andere Folgeauftrag, beendet
     * ist. Der Abgleich plant sie ein, bevor er endet.
     *
     * @throws AssertionError wenn nach {@link #TIMEOUT} keine Anreicherung eingeplant ist oder Folgeaufträge noch laufen
     */
    public static void awaitEnrichments(Workspace workspace) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            List<JobStatus> followUps = workspace.jobs().stream().filter(job -> job.type().followUp()).toList();
            boolean planned = followUps.stream().anyMatch(job -> job.type() == JobType.ENRICHMENT);
            List<JobStatus> open = followUps.stream().filter(job -> !job.state().terminal()).toList();
            if (planned && open.isEmpty()) {
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError(planned ? "Folgeaufträge nach " + TIMEOUT.toSeconds() + " s nicht beendet: "
                        + open.stream().map(job -> job.type() + " " + job.id() + " " + job.state()).toList()
                        : "Keine Anreicherung nach dem Abgleich eingeplant");
            }
            Thread.sleep(50);
        }
    }
}
