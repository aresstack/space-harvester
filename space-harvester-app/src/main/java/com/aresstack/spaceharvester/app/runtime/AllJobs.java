package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Welche Aufträge „Alle anhalten“, „Alle fortsetzen“ und „Alle abbrechen“ in der Kopfzeile der Aufträge betreffen
 * (Angelo 11.11.2026), in der Reihenfolge, in der die App sie bearbeitet.
 *
 * <p>Wartende kommen vor laufenden dran: Sonst rückte ein wartender in den Platz nach, den ein laufender beim Anhalten
 * oder Abbrechen freigibt. Bilder und Anhänge, die noch auf ihren Export warten ({@link JobStage#AWAITING}), folgen
 * ihm und bleiben unberührt, wie in ihrer Zeile. Fertige Aufträge startet nichts neu.
 */
public final class AllJobs {

    private AllJobs() {
    }

    /** Was eine Aktion für alle bewirkt hat: wie viele Aufträge sie erreicht hat, wie viele nicht und warum. */
    public record Outcome(int changed, int failed, RuntimeException problem) {
    }

    /**
     * Was „Alle anhalten“ anhält: jeden wartenden oder laufenden Auftrag einer Art, die sich fortsetzen lässt, auch
     * einen wartenden, den seine Zeile nicht anhalten kann ({@code Jobs#pause} kennt das); nicht, was schon anhält oder
     * abbricht. Arten ohne Fortsetzen (etwa Anreicherungen) laufen weiter.
     */
    public static List<JobStatus> toPause(List<JobStatus> jobs) {
        return waitingFirst(jobs.stream().filter(AllJobs::pausable));
    }

    /** Ob „Alle anhalten“ diesen Auftrag anhält. */
    public static boolean pausable(JobStatus job) {
        return job.type().resumable() && !job.cancelRequested() && (job.state() == JobState.RUNNING
                || job.state() == JobState.QUEUED && job.stage() != JobStage.AWAITING);
    }

    /**
     * Was „Alle fortsetzen“ fortsetzt: jeden angehaltenen Auftrag, auch einen, der sich selbst angehalten hat (fehlende
     * Anmeldung, Verbindung), älteste zuerst, damit die Warteschlange ihre Reihenfolge behält. Gescheiterte und
     * abgebrochene bleiben, wie sie sind.
     */
    public static List<JobStatus> toResume(List<JobStatus> jobs) {
        return jobs.stream().filter(job -> job.state() == JobState.PAUSED && job.lifecycle().allows(JobAction.RESUME))
                .sorted(Comparator.comparing(JobStatus::createdAt)).toList();
    }

    /** Was „Alle abbrechen“ abbricht: alles, dessen Zustand Abbrechen anbietet (wartend, laufend, beim Anhalten). */
    public static List<JobStatus> toCancel(List<JobStatus> jobs) {
        return waitingFirst(jobs.stream().filter(job -> job.lifecycle().allows(JobAction.CANCEL)));
    }

    /**
     * Führt {@code action} für jeden Auftrag aus. Ein inzwischen gelöschter oder umgeschlagener Auftrag (etwa gerade
     * fertig geworden) zählt weder als erreicht noch als Fehler; jeder andere Fehler hält die übrigen nicht auf.
     */
    static Outcome apply(List<JobStatus> jobs, Consumer<JobStatus> action) {
        int changed = 0;
        int failed = 0;
        RuntimeException problem = null;
        for (JobStatus job : jobs) {
            try {
                action.accept(job);
                changed++;
            } catch (UnknownJobException | JobActionNotAllowedException e) {
                // Inzwischen gelöscht oder in einem Zustand, der die Aktion nicht mehr anbietet: nichts zu tun.
            } catch (RuntimeException e) {
                failed++;
                if (problem == null) {
                    problem = e;
                }
            }
        }
        return new Outcome(changed, failed, problem);
    }

    /** Wartende zuerst, dann vorbereitete Downloads, die auf einen Platz warten, dann laufende; je älteste zuerst. */
    private static List<JobStatus> waitingFirst(Stream<JobStatus> jobs) {
        return jobs.sorted(Comparator.comparingInt(AllJobs::rank).thenComparing(JobStatus::createdAt)).toList();
    }

    private static int rank(JobStatus job) {
        if (job.state() == JobState.QUEUED) {
            return 0;
        }
        return job.stage() == JobStage.PREPARED ? 1 : 2;
    }
}
