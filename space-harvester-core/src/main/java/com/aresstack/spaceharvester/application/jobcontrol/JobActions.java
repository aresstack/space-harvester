package com.aresstack.spaceharvester.application.jobcontrol;

import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Zentraler Einstieg für alle Aktionen auf Aufträgen ({@link JobAction}). Prüft zuerst am Zustandsobjekt
 * ({@link JobStatus#lifecycle()}), ob die Aktion erlaubt ist, und gibt sie dann an den zuständigen Anwendungsfall
 * weiter: Abgleiche an {@link SpaceSyncJobs}, Exporte an {@link Exports}, Bilder an {@link ExportConversions}, alles
 * andere an {@link Jobs}. Die Oberfläche bietet nur an, was {@link JobLifecycleState#actions()} nennt, und ruft dann
 * {@link #perform}; eigene Zustandslogik braucht sie nicht.
 *
 * <p>Fortsetzen, „Nur Fehler neu versuchen“ und „Neu erzwingen“ bleiben immer im selben Eintrag, auch nach einem Neustart (dann aus
 * den abgelegten {@link JobStatus#parameters()}); es entsteht nie ein neuer Auftrag.
 */
public class JobActions {

    private final Jobs jobs;
    private final Exports exports;
    private final SpaceSyncJobs syncs;
    private final ExportConversions conversions;

    /**
     * @param syncs       Abgleiche oder {@code null}, wenn kein Abgleich angebunden ist
     * @param conversions Bilder der Exporte oder {@code null}
     */
    public JobActions(Jobs jobs, Exports exports, SpaceSyncJobs syncs, ExportConversions conversions) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.syncs = syncs;
        this.conversions = conversions;
    }

    /** Der wirksame Zustand des eigenen Auftrags mit den jetzt erlaubten Aktionen. */
    public JobLifecycleState state(String jobId, String owner) {
        return jobs.status(jobId, owner).lifecycle();
    }

    /** Wie {@link #perform(String, String, JobAction, UnaryOperator)}, ohne Lese- und Exportprüfung zu erneuern. */
    public JobStatus perform(String jobId, String owner, JobAction action) {
        return perform(jobId, owner, action, UnaryOperator.identity());
    }

    /**
     * Führt eine Aktion auf dem eigenen Auftrag aus.
     *
     * @param access erneuert beim Fortsetzen eines Exports Lese- und Exportprüfung mit dem aktuellen Zugang des
     *               Aufrufers ({@link Exports#resume(String, String, UnaryOperator)})
     * @return der Zustand danach; bei {@link JobAction#DELETE} der letzte Zustand vor dem Löschen
     * @throws JobActionNotAllowedException wenn der Zustand die Aktion nicht anbietet
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen eigenen Auftrag mit
     *                                                                           dieser ID gibt
     */
    public JobStatus perform(String jobId, String owner, JobAction action, UnaryOperator<ExportRequest> access) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(access, "access");
        JobStatus current = jobs.status(jobId, owner);
        JobLifecycleState state = current.lifecycle();
        if (!state.allows(action)) {
            throw new JobActionNotAllowedException(jobId, action, state);
        }
        boolean sync = current.type() == JobType.SYNC && syncs != null;
        return switch (action) {
            case START_NOW -> jobs.prioritize(jobId, owner);
            case PAUSE -> sync ? syncs.pause(jobId, owner).status() : jobs.pause(jobId, owner);
            case CANCEL -> sync ? syncs.cancel(jobId, owner).status() : jobs.cancel(jobId, owner);
            case RESUME -> resume(current, owner, access);
            case RETRY_FAILED -> {
                if (current.type() != JobType.EXPORT) {
                    throw new JobNotResumableException(jobId, "nur Downloads laden gescheiterte Seiten gezielt nach");
                }
                yield exports.retryFailed(jobId, owner, access);
            }
            case FORCE_REFRESH -> {
                if (current.type() != JobType.EXPORT) {
                    throw new JobNotResumableException(jobId, "nur Downloads erzwingen einen neuen Stand");
                }
                yield exports.forceRefresh(jobId, owner, access);
            }
            case DELETE -> {
                if (sync) {
                    syncs.delete(jobId, owner);
                } else {
                    jobs.delete(jobId, owner);
                }
                yield current;
            }
        };
    }

    private JobStatus resume(JobStatus current, String owner, UnaryOperator<ExportRequest> access) {
        return switch (current.type()) {
            case SYNC -> {
                if (syncs == null) {
                    throw new JobNotResumableException(current.id(), "kein Abgleich angebunden");
                }
                yield syncs.resume(current.id(), owner).status();
            }
            case EXPORT -> exports.resume(current.id(), owner, access);
            case ASSETS, ATTACHMENTS -> {
                if (conversions == null) {
                    throw new JobNotResumableException(current.id(), "keine Bild- oder Anhangbeschaffung angebunden");
                }
                yield conversions.resumeImages(current.id(), owner);
            }
            default -> throw new JobNotResumableException(current.id(), "diese Auftragsart lässt sich nicht fortsetzen");
        };
    }
}
