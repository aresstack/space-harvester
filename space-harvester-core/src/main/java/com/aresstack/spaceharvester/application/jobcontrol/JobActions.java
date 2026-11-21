package com.aresstack.spaceharvester.application.jobcontrol;

import com.aresstack.spaceharvester.application.assets.DocumentRereads;
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
import com.aresstack.spaceharvester.application.metadata.SourceQueryExports;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Zentraler Einstieg für alle Aktionen auf Aufträgen ({@link JobAction}). Prüft zuerst am Zustandsobjekt
 * ({@link JobStatus#lifecycle()}), ob die Aktion erlaubt ist, und gibt sie dann an den zuständigen Anwendungsfall
 * weiter: Abgleiche an {@link SpaceSyncJobs}, Exporte an {@link Exports}, Bilder an {@link ExportConversions}, „Neu
 * lesen“ an {@link DocumentRereads}, alles andere an {@link Jobs}. Die Oberfläche bietet nur an, was {@link JobLifecycleState#actions()} nennt, und ruft dann
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
    private final DocumentRereads rereads;
    private final SourceQueryExports sourceQueryExports;
    /** „In PPTX umwandeln“ oder {@code null}; bindet der Harvester nach dem Bau an. */
    private volatile com.aresstack.spaceharvester.application.presentation.VideoPresentations presentations;

    /**
     * @param syncs       Abgleiche oder {@code null}, wenn kein Abgleich angebunden ist
     * @param conversions Bilder der Exporte oder {@code null}
     */
    public JobActions(Jobs jobs, Exports exports, SpaceSyncJobs syncs, ExportConversions conversions) {
        this(jobs, exports, syncs, conversions, null);
    }

    /**
     * @param syncs       Abgleiche oder {@code null}, wenn kein Abgleich angebunden ist
     * @param conversions Bilder der Exporte oder {@code null}
     * @param rereads     „Neu lesen“ oder {@code null}, wenn keine strukturierte Extraktion angebunden ist
     */
    public JobActions(Jobs jobs, Exports exports, SpaceSyncJobs syncs, ExportConversions conversions,
                      DocumentRereads rereads) {
        this(jobs, exports, syncs, conversions, rereads, null);
    }

    /**
     * @param syncs              Abgleiche oder {@code null}, wenn kein Abgleich angebunden ist
     * @param conversions        Bilder der Exporte oder {@code null}
     * @param rereads            „Neu lesen“ oder {@code null}, wenn keine strukturierte Extraktion angebunden ist
     * @param sourceQueryExports Metadaten- und Analytics-Exporte oder {@code null}
     */
    public JobActions(Jobs jobs, Exports exports, SpaceSyncJobs syncs, ExportConversions conversions,
                      DocumentRereads rereads, SourceQueryExports sourceQueryExports) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.syncs = syncs;
        this.conversions = conversions;
        this.rereads = rereads;
        this.sourceQueryExports = sourceQueryExports;
    }

    /** „Suchqualität messen“ oder {@code null}; bindet der Harvester nach dem Bau an. */
    private volatile com.aresstack.spaceharvester.application.quality.SearchQualityRuns searchQuality;

    /** Bindet „Suchqualität messen“ an: Fortsetzen misst dann nur die fehlenden Fragen, auch nach einem Neustart. */
    public void searchQuality(com.aresstack.spaceharvester.application.quality.SearchQualityRuns searchQuality) {
        this.searchQuality = searchQuality;
    }

    /** Bindet „In PPTX umwandeln“ an: Fortsetzen und „Nur Fehler“ laufen dann auch nach dem Ende im selben Eintrag. */
    public void presentations(com.aresstack.spaceharvester.application.presentation.VideoPresentations presentations) {
        this.presentations = presentations;
    }

    /** Hintergrundaufträge oder {@code null}; bindet der Harvester nach dem Bau an. */
    private volatile com.aresstack.spaceharvester.application.background.BackgroundJobs background;

    /** Bindet die Hintergrundaufträge an: Fortsetzen baut ihre Arbeit neu, auch nach einem Neustart. */
    public void background(com.aresstack.spaceharvester.application.background.BackgroundJobs background) {
        this.background = background;
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
        if (action == JobAction.RETRY_FAILED && (state instanceof JobLifecycleState.Restarting
                || state instanceof JobLifecycleState.Stopping stopping && stopping.pausing())) {
            // Startet er für ein schon gedrücktes ↻ gerade neu oder hält er an: kein Fehler, höchstens ein Neustart.
            return jobs.requestRetryFailed(jobId, owner);
        }
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
                if (state instanceof JobLifecycleState.Running || state instanceof JobLifecycleState.Preparing) {
                    // Während des Laufs: der Durchgang hält an und läuft sofort im selben Eintrag neu (Angelo
                    // 12.11.2026), Mehrfachdrücken löst nur einen Neustart aus.
                    yield jobs.requestRetryFailed(jobId, owner);
                }
                if (current.type() == JobType.ASSETS || current.type() == JobType.ATTACHMENTS) {
                    // In jedem beendeten oder angehaltenen Zustand: Seiten mit gescheiterten Dateien erneut besuchen;
                    // abgelegte Dateien kommen aus dem Speicher.
                    if (conversions == null) {
                        throw new JobNotResumableException(jobId, "keine Bild- oder Anhangbeschaffung angebunden");
                    }
                    yield conversions.retryImages(jobId, owner);
                }
                if (state instanceof JobLifecycleState.Paused || state instanceof JobLifecycleState.Stopped
                        || current.type().sourceQuery() || current.type().presentation()) {
                    // Angehalten oder abgebrochen: Fortsetzen holt nur, was fehlt, das Gescheiterte eingeschlossen.
                    // Metadaten und Analytics auch nach „fertig, unvollständig“: ein neuer Lauf im selben Eintrag.
                    yield resume(current, owner, access);
                }
                if (sync) {
                    yield syncs.retryFailed(jobId, owner).status();
                }
                if (current.type() != JobType.EXPORT) {
                    throw new JobNotResumableException(jobId,
                            "nur Downloads und Abgleiche laden gescheiterte Seiten gezielt nach");
                }
                yield exports.retryFailed(jobId, owner, access);
            }
            case FORCE_REFRESH -> {
                if (sync) {
                    yield syncs.forceRefresh(jobId, owner).status();
                }
                if (current.type() == JobType.ASSETS || current.type() == JobType.ATTACHMENTS) {
                    if (conversions == null) {
                        throw new JobNotResumableException(jobId, "keine Bild- oder Anhangbeschaffung angebunden");
                    }
                    yield conversions.forceImages(jobId, owner);
                }
                if (current.type() != JobType.EXPORT) {
                    throw new JobNotResumableException(jobId, "nur Downloads und Abgleiche erzwingen einen neuen Stand");
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
            case REREAD -> {
                if (rereads == null) {
                    throw new JobNotResumableException(current.id(), "kein „Neu lesen“ angebunden");
                }
                yield rereads.resume(current.id(), owner);
            }
            case METADATA_EXPORT, ANALYTICS_EXPORT -> {
                if (sourceQueryExports == null) {
                    throw new JobNotResumableException(current.id(), "kein Metadaten- oder Analytics-Export angebunden");
                }
                yield sourceQueryExports.resume(current.id(), owner);
            }
            // „In PPTX umwandeln“: derselbe Auftrag läuft im selben Eintrag neu und fragt nur, was fehlt.
            case VIDEO_TRANSCRIPT, VIDEO_SLIDES, TRANSCRIPT_CORRECTION, SLIDE_NOTES, PRESENTATION_EXPORT -> {
                com.aresstack.spaceharvester.application.presentation.VideoPresentations bound = presentations;
                yield bound != null ? bound.resume(current.id(), owner) : jobs.resume(current.id(), owner);
            }
            case SEARCH_QUALITY -> {
                com.aresstack.spaceharvester.application.quality.SearchQualityRuns bound = searchQuality;
                yield bound != null ? bound.resume(current.id(), owner) : jobs.resume(current.id(), owner);
            }
            // Podcast: derselbe Auftrag läuft im selben Eintrag neu und erzeugt nur, was auf der Platte fehlt.
            case UPDATE_PODCAST -> jobs.resume(current.id(), owner);
            case BACKGROUND -> {
                com.aresstack.spaceharvester.application.background.BackgroundJobs bound = background;
                yield bound != null ? bound.resumeJob(current.id()) : jobs.resume(current.id(), owner);
            }
            default -> throw new JobNotResumableException(current.id(), "diese Auftragsart lässt sich nicht fortsetzen");
        };
    }
}
