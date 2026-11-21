package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bilder automatisch beschreiben (Fahrplan B1): Folgeschritt nach der Anreicherung einer Quelle und nach neuer
 * KI-Freigabe, gebaut wie die automatischen Synonyme ({@code SynonymDiscovery#automatic}). Nichts geschieht, solange
 * {@code images.automatic} aus ist (Standard). Sonst beschreibt ein eigener, abbrechbarer Auftrag
 * ({@link JobType#ENRICHMENT} mit {@value #TASK_PARAMETER} = {@value #TASK}) über denselben Weg wie der Knopf im Reiter
 * „Bilder“ ({@link PageImageAnalysis#describe}, also auch dieselbe Anmeldung, OTP und Bildablage) die Bilder der Seiten,
 * die für das Vision-Modell freigegeben sind (ADR 0028) und für das eingestellte Modell noch keine Beschreibung haben.
 * Die Ablage je Prüfsumme und Modell verhindert, dass ein Bild zweimal an das Modell geht.
 *
 * <p>Je Lauf und Quelle gehen höchstens {@code images.max-per-run} Bilder an das Modell ({@code 0} = unbegrenzt); eine
 * Seite, deren offene Bilder nicht mehr in die Grenze passen, bleibt für den nächsten Lauf. Ist die Grenze erreicht,
 * nennt ein gelbes Ereignis ({@value #LIMIT_REACHED}) die Zahl der offenen Bilder. Ohne Vision-Modell endet der Auftrag
 * gelb mit {@value #NO_MODEL}, nie rot. Verlangt die Quelle einen Einmalcode, endet er gelb mit {@value #SIGN_IN},
 * weil niemand ihn im Hintergrund eingeben kann. Abbrechen lässt alles stehen, was schon beschrieben ist. Kein
 * Zeitgeber, kein Abfragen: ausgelöst wird nur durch Abgleich und Freigabe.
 */
public final class AutomaticImageDescriptions {

    /** Parameter des Auftrags, an dem die App ihn als „Bilder beschreiben“ erkennt. */
    public static final String TASK_PARAMETER = "task";
    /** Wert von {@link #TASK_PARAMETER}. */
    public static final String TASK = "images";
    /** Ereignis: kein Vision-Modell eingestellt, bewusst ausgelassen (gelb). */
    public static final String NO_MODEL = "IMAGES_SKIPPED:NO_MODEL";
    /** Ereignis: Obergrenze je Lauf erreicht, offene Bilder folgen beim nächsten Lauf (gelb). */
    public static final String LIMIT_REACHED = "IMAGES_LIMIT_REACHED";
    /** Ereignis: die Quelle verlangt einen Einmalcode, der Lauf ist ausgelassen (gelb). */
    public static final String SIGN_IN = "IMAGES_SKIPPED:SIGN_IN";
    /** Ereignis mit den Zahlen des Laufs. */
    public static final String DONE = "IMAGES_DONE";
    /** Komponente der Ereignisse. */
    static final String COMPONENT = "Images";

    private static final System.Logger LOG = System.getLogger(AutomaticImageDescriptions.class.getName());

    private final Jobs jobs;
    private final PageImageAnalysis analysis;
    private final Function<String, Iterator<PageSnapshot>> snapshots;
    private final Supplier<ImageSettings> settings;
    private final Function<String, Boolean> knownSource;
    /** Je Quelle der Auftrag, der noch wartet; ein zweiter Anlass plant dann keinen weiteren ein. */
    private final Map<String, String> waiting = new HashMap<>();

    /**
     * @param snapshots   die gespeicherten Seiten einer Quelle, etwa {@code LucenePageCache::snapshots}
     * @param settings    die geltenden Einstellungen, zur Laufzeit gelesen
     * @param knownSource ob die Quelle eingerichtet ist
     */
    public AutomaticImageDescriptions(Jobs jobs, PageImageAnalysis analysis,
                                      Function<String, Iterator<PageSnapshot>> snapshots,
                                      Supplier<ImageSettings> settings, Function<String, Boolean> knownSource) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.knownSource = Objects.requireNonNull(knownSource, "knownSource");
    }

    /**
     * Plant den Lauf für eine Quelle ein, wenn {@code images.automatic} an ist, die Quelle bekannt ist und nicht schon
     * ein Lauf für sie wartet.
     *
     * @return der Auftrag, oder leer, wenn keiner nötig ist
     */
    public Optional<JobStatus> automatic(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        ImageSettings chosen = settings.get();
        if (chosen == null || !chosen.automatic() || !Boolean.TRUE.equals(knownSource.apply(sourceId))) {
            return Optional.empty();
        }
        synchronized (waiting) {
            String pending = waiting.get(sourceId);
            if (pending != null && stillWaiting(pending)) {
                return Optional.empty();
            }
            waiting.remove(sourceId);
            JobStatus status;
            try {
                status = jobs.submit(JobType.ENRICHMENT, sourceId, null, null, null, Map.of(TASK_PARAMETER, TASK),
                        JobTask.blocking(context -> {
                            synchronized (waiting) {
                                waiting.remove(sourceId, context.jobId());
                            }
                            run(sourceId, settings.get(), context);
                        }));
            } catch (JobCapacityExceededException | IllegalStateException e) {
                LOG.log(System.Logger.Level.INFO, "Bildbeschreibung für {0} nicht eingeplant ({1}); der nächste "
                        + "Abgleich holt sie nach", sourceId, e.getClass().getSimpleName());
                return Optional.empty();
            }
            if (!finished(status.state())) {
                waiting.put(sourceId, status.id());
            }
            LOG.log(System.Logger.Level.INFO, "Bildbeschreibung für {0} automatisch eingeplant", sourceId);
            return Optional.of(status);
        }
    }

    /** Beobachter der Liste freigegebener Seiten (ADR 0028): neu freigegebene Seiten beschreibt der nächste Lauf. */
    public void releasesChanged(PageReleases.Change change) {
        change.added().stream().map(entry -> entry.key().sourceId()).distinct().forEach(sourceId -> {
            try {
                automatic(sourceId);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Bildbeschreibung für {0} nicht eingeplant ({1})", sourceId,
                        e.getClass().getSimpleName());
            }
        });
    }

    /** Ergebnis eines Laufs: beschriebene Seiten und Bilder, offene Bilder, gescheiterte Seiten. */
    record Outcome(int pages, int images, int open, int failed) {
    }

    /** Der Lauf; im Auftrag, für Tests auch direkt. */
    Outcome run(String sourceId, ImageSettings chosen, JobContext context) {
        context.phase(COMPONENT, "Bilder beschreiben");
        ImageSettings limits = chosen == null ? ImageSettings.DEFAULT : chosen;
        if (!analysis.available()) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NO_MODEL,
                    "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben"));
            return new Outcome(0, 0, 0, 0);
        }
        int budget = limits.unlimited() ? Integer.MAX_VALUE : limits.maxPerRun();
        int sent = 0;
        int pages = 0;
        int open = 0;
        int failed = 0;
        Iterator<PageSnapshot> all = snapshots.apply(sourceId);
        while (all.hasNext()) {
            context.checkpoint();
            PageSnapshot snapshot = all.next();
            if (!analysis.released(snapshot.key())) {
                continue;
            }
            PageImages before;
            try {
                before = analysis.read(snapshot);
            } catch (RuntimeException e) {
                failed++;
                continue;
            }
            Work work = work(before);
            if (work == Work.NONE) {
                continue;
            }
            int pending = work.pending;
            if (pending > budget - sent) {
                // Passt nicht mehr: bleibt für den nächsten Lauf, kleinere Seiten dürfen noch.
                open += pending;
                continue;
            }
            context.discovered(1);
            try {
                PageImages after = analysis.describe(snapshot);
                int described = newlyDescribed(before, after);
                sent += described;
                pages++;
                context.written(1);
            } catch (SignInRequiredException e) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, SIGN_IN,
                        "Die Quelle verlangt einen Einmalcode; Bilder dieses Laufs bewusst ausgelassen"));
                context.processed(1);
                break;
            } catch (ImageDescriptionException e) {
                if (e.reason() == ImageDescriptionException.Reason.NOT_CONFIGURED) {
                    context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NO_MODEL,
                            "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben"));
                    context.processed(1);
                    break;
                }
                // NOT_RELEASED: die Freigabe fiel eben weg, die Seite bleibt draußen.
                if (e.reason() != ImageDescriptionException.Reason.NOT_RELEASED) {
                    failed++;
                    context.failed(1);
                }
            } catch (com.aresstack.spaceharvester.application.jobs.JobCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                // Eine Seite, deren Lese-Ansicht oder Bilder sich nicht laden lassen, hält die übrigen nicht auf.
                failed++;
                context.failed(1);
                LOG.log(System.Logger.Level.WARNING, "Bilder einer Seite in {0} nicht beschrieben ({1})", sourceId,
                        e.getClass().getName());
            }
            context.processed(1);
        }
        if (open > 0) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, LIMIT_REACHED,
                    "Obergrenze von " + limits.maxPerRun() + " Bildern je Lauf erreicht; " + open
                            + (open == 1 ? " Bild bleibt" : " Bilder bleiben") + " für den nächsten Lauf offen"));
            context.incomplete();
        }
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, DONE,
                pages + (pages == 1 ? " Seite, " : " Seiten, ") + sent + (sent == 1 ? " Bild" : " Bilder")
                        + " beschrieben" + (failed > 0 ? ", " + failed + " Seiten nicht" : ""), null, null, null));
        return new Outcome(pages, sent, open, failed);
    }

    /** Was an einer Seite zu tun ist: nichts, oder beschreiben mit (geschätzt) so vielen offenen Bildern. */
    private record Work(int pending) {
        static final Work NONE = new Work(-1);
    }

    /**
     * Offene Bilder einer Seite: noch nicht beschrieben, veraltet oder mit gescheitertem Modellaufruf. Ohne gespeicherte
     * Lese-Ansicht ist die Zahl unbekannt; die Seite zählt dann als ein offenes Bild, bis die Quelle sie liefert.
     */
    private static Work work(PageImages images) {
        return switch (images.state()) {
            case MISSING -> {
                if (PageImages.NO_TEXT.equals(images.reason())) {
                    yield Work.NONE;
                }
                long open = images.images().stream().filter(AutomaticImageDescriptions::open).count();
                // Ohne gespeicherte Lese-Ansicht ist die Liste leer; die Quelle sagt erst beim Beschreiben, was dort ist.
                yield images.images().isEmpty() ? new Work(1) : open == 0 ? Work.NONE : new Work((int) open);
            }
            case STALE -> new Work(Math.max(1, (int) images.images().stream()
                    .filter(image -> image.status() != ImageStatus.SKIPPED).count()));
            case FAILED -> new Work(1);
            case CURRENT -> {
                long open = images.images().stream().filter(image -> image.status() == ImageStatus.PENDING
                        && (image.reason() == null || "model-busy".equals(image.reason())) || image.status() == ImageStatus.FAILED
                        && PageImageAnalysis.modelFailure(image.reason())).count();
                yield open == 0 ? Work.NONE : new Work((int) open);
            }
        };
    }

    private static boolean open(ImagePayload.Entry image) {
        return image.status() != ImageStatus.SKIPPED && image.status() != ImageStatus.DESCRIBED;
    }

    /** Wie viele Bilder der Lauf an dieser Seite neu beschrieben hat (nach Prüfsumme, Bilder mehrfach einmal). */
    static int newlyDescribed(PageImages before, PageImages after) {
        Set<String> known = new HashSet<>();
        if (before.state() == PageImages.State.CURRENT) {
            before.images().stream().filter(image -> image.status() == ImageStatus.DESCRIBED && image.sha256() != null)
                    .forEach(image -> known.add(image.sha256()));
        }
        Set<String> counted = new HashSet<>();
        int count = 0;
        for (ImagePayload.Entry image : after.images()) {
            if (image.status() == ImageStatus.DESCRIBED && image.sha256() != null && !known.contains(image.sha256())
                    && counted.add(image.sha256())) {
                count++;
            }
        }
        return count;
    }

    private boolean stillWaiting(String jobId) {
        try {
            return !finished(jobs.status(jobId).state());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean finished(JobState state) {
        return state == JobState.COMPLETED || state == JobState.FAILED || state == JobState.CANCELLED;
    }
}
