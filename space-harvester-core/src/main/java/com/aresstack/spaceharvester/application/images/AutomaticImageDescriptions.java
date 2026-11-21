package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.assets.Assets;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bilder automatisch beschreiben (Fahrplan B1): Folgeschritt nach der Anreicherung einer Quelle und nach neuer
 * KI-Freigabe, gebaut wie die automatischen Synonyme ({@code SynonymDiscovery#automatic}). Nichts geschieht, solange
 * {@code images.automatic} aus ist (Standard). Sonst beschreibt ein eigener, abbrechbarer Auftrag über denselben Weg wie der Knopf im
 * Reiter „Bilder“ ({@link PageImageAnalysis#describe}, also auch dieselbe Anmeldung, OTP und Bildablage) die Bilder der Seiten,
 * die für das Vision-Modell freigegeben sind (ADR 0028) und für das eingestellte Modell noch keine Beschreibung haben.
 * Die Ablage je Prüfsumme und Modell verhindert, dass ein Bild zweimal an das Modell geht.
 *
 * <p>Je Lauf und Quelle gehen höchstens {@code images.max-per-run} Bilder an das Modell ({@code 0} = unbegrenzt); eine
 * Seite, deren offene Bilder nicht mehr in die Grenze passen, bleibt für den nächsten Lauf. Die Grenze kann um die
 * Bilder einer Seite überzogen werden: Eine Seite ohne gespeicherte Lese-Ansicht zählt vorab als ein Bild, und die erste
 * Seite eines Laufs läuft auch dann, wenn sie allein mehr offene Bilder hat als die ganze Grenze (sonst bliebe sie für
 * immer offen). Ist die Grenze erreicht,
 * nennt ein gelbes Ereignis ({@value #LIMIT_REACHED}) die Zahl der offenen Bilder. Ohne Vision-Modell endet der Auftrag
 * gelb mit {@value #NO_MODEL}, nie rot. Verlangt die Quelle einen Einmalcode, endet er gelb mit {@value #SIGN_IN},
 * weil niemand ihn im Hintergrund eingeben kann. Abbrechen lässt alles stehen, was schon beschrieben ist. Kein
 * Zeitgeber, kein Abfragen: ausgelöst wird nur durch Abgleich und Freigabe.
 *
 * <p>Nachrangig (Angelo 21.11.2026): Der Auftrag wartet hinter Abgleichen und Exporten ({@link JobType#followUp()})
 * und tritt vor jedem Bild zurück, solange der Benutzer selbst Bilder beschreiben lässt ({@link ModelPriority}, Phase
 * {@value #WAITING_PHASE}). Mehrere gleichzeitig (Angelo 21.11.2026): Bis zu {@code ai.parallel} Seiten laufen
 * zugleich, mit eigenem Kontingent je Auftrag ({@link Assets#inLane}), so dass ein Lauf die Beschreibungen anderer
 * Aufträge nicht verdrängt.
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
    /**
     * Ereignis: das Vision-Modell antwortete nicht (nicht erreichbar, Frist, Fehler, leere Antwort); der Lauf hört auf,
     * statt jedes weitere Bild an ein ausgefallenes Modell zu schicken (rot, ein Fehler).
     */
    public static final String MODEL_FAILED = "IMAGES_MODEL_FAILED";
    /** Ereignis mit den Zahlen des Laufs. */
    public static final String DONE = "IMAGES_DONE";
    /** Phase, solange der Lauf vor einer Beschreibung des Benutzers zurücktritt. */
    public static final String WAITING_PHASE = "Wartet auf freies Modell";
    /** Komponente der Ereignisse. */
    static final String COMPONENT = "Images";

    private static final System.Logger LOG = System.getLogger(AutomaticImageDescriptions.class.getName());

    private final Jobs jobs;
    private final PageImageAnalysis analysis;
    private final Function<String, Iterator<PageSnapshot>> snapshots;
    private final Supplier<ImageSettings> settings;
    private final Function<String, Boolean> knownSource;
    /** Eigentümer der Aufträge; die App zeigt nur Aufträge ihrer Eigentümer an. */
    private final String owner;
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
        this(jobs, analysis, snapshots, settings, knownSource, null);
    }

    /**
     * Wie oben, mit dem Eigentümer der Aufträge, etwa {@code Harvester#OWNER}: Ohne ihn sieht die App die Aufträge
     * nicht in ihrer Liste.
     */
    public AutomaticImageDescriptions(Jobs jobs, PageImageAnalysis analysis,
                                      Function<String, Iterator<PageSnapshot>> snapshots,
                                      Supplier<ImageSettings> settings, Function<String, Boolean> knownSource,
                                      String owner) {
        this.owner = owner;
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
                status = jobs.submit(JobType.ENRICHMENT, sourceId, null, null, owner,
                        Map.of(TASK_PARAMETER, TASK), task(sourceId));
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

    /** Die Arbeit eines Laufs; ist {@code images.automatic} inzwischen aus, geht nichts an das Modell. */
    private JobTask task(String sourceId) {
        return JobTask.blocking(context -> {
            synchronized (waiting) {
                waiting.remove(sourceId, context.jobId());
            }
            ImageSettings now = settings.get();
            if (now != null && now.automatic()) {
                run(sourceId, now, context);
            }
        });
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

    /** Der Lauf über alle Spaces der Quelle; für Tests. */
    Outcome run(String sourceId, ImageSettings chosen, JobContext context) {
        return run(sourceId, null, chosen, context);
    }

    /**
     * Der Lauf eines Unterauftrags: die Seiten eines Space ({@code null} = alle) mit eigenem Kontingent von
     * {@code ai.parallel} gleichzeitigen Beschreibungen ({@link Assets#inLane}). Bis zu so viele Seiten laufen
     * zugleich, jede Seite Bild für Bild; die Obergrenze gilt je Lauf.
     */
    Outcome run(String sourceId, String space, ImageSettings chosen, JobContext context) {
        context.phase(COMPONENT, "Bilder beschreiben");
        ImageSettings limits = chosen == null ? ImageSettings.DEFAULT : chosen;
        if (!analysis.available()) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NO_MODEL,
                    "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben"));
            return new Outcome(0, 0, 0, 0);
        }
        int budget = limits.unlimited() ? Integer.MAX_VALUE : limits.maxPerRun();
        int parallel = Math.max(1, analysis.parallel());
        String lane = "images:" + context.jobId();
        Tally tally = new Tally();
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(parallel);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(parallel,
                work -> {
                    Thread thread = new Thread(work, "bilder-beschreiben");
                    thread.setDaemon(true);
                    return thread;
                });
        List<java.util.concurrent.Future<?>> running = new java.util.ArrayList<>();
        RuntimeException stopped = null;
        try {
            Iterator<PageSnapshot> all = snapshots.apply(sourceId);
            while (all.hasNext() && tally.stop.get() == null) {
                context.checkpoint();
                PageSnapshot snapshot = all.next();
                if (space != null && !space.equals(snapshot.spaceKey())) {
                    continue;
                }
                if (!analysis.released(snapshot.key())) {
                    continue;
                }
                PageImages before;
                try {
                    before = analysis.read(snapshot);
                } catch (RuntimeException e) {
                    tally.failed.incrementAndGet();
                    continue;
                }
                Work work = work(before);
                if (work == Work.NONE) {
                    continue;
                }
                int pending = work.pending;
                synchronized (tally) {
                    // Eine Seite größer als die ganze Grenze darf als erste des Laufs trotzdem (überzieht einmal).
                    if (pending > budget - tally.reserved && !(tally.reserved == 0 && !tally.oversized)) {
                        // Passt nicht mehr: bleibt für den nächsten Lauf, kleinere Seiten dürfen noch.
                        tally.open += pending;
                        continue;
                    }
                    if (pending > budget - tally.reserved) {
                        tally.oversized = true;
                    }
                    tally.reserved += pending;
                }
                context.discovered(1);
                // Höchstens so viele Seiten zugleich, wie das Kontingent erlaubt; eine fertige Seite gibt ihren Platz
                // frei. Kein Takt: wartet am Semaphor, bis ein Arbeiter fertig ist.
                slots.acquireUninterruptibly();
                if (tally.stop.get() != null) {
                    slots.release();
                    break;
                }
                running.add(pool.submit(() -> {
                    try {
                        Assets.inLane(lane, () -> {
                            page(sourceId, snapshot, before, pending, tally, context);
                            return null;
                        });
                    } finally {
                        slots.release();
                    }
                }));
            }
        } catch (RuntimeException e) {
            stopped = e;
        } finally {
            // Erst wenn alle Arbeiter ihr laufendes Bild beendet haben, endet der Lauf: nichts arbeitet abgekoppelt.
            for (java.util.concurrent.Future<?> page : running) {
                try {
                    page.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (stopped == null && e.getCause() instanceof RuntimeException cause) {
                        stopped = cause;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            pool.shutdown();
        }
        if (stopped != null) {
            throw stopped;
        }
        int open;
        synchronized (tally) {
            open = tally.open;
        }
        if (open > 0) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, LIMIT_REACHED,
                    "Obergrenze von " + limits.maxPerRun() + " Bildern je Lauf erreicht; " + open
                            + (open == 1 ? " Bild bleibt" : " Bilder bleiben") + " für den nächsten Lauf offen"));
            context.incomplete();
        }
        int pages = tally.pages.get();
        int sent = tally.sent.get();
        int failed = tally.failed.get();
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, DONE,
                pages + (pages == 1 ? " Seite, " : " Seiten, ") + sent + (sent == 1 ? " Bild" : " Bilder")
                        + " beschrieben" + (failed > 0 ? ", " + failed + " Seiten nicht" : ""), null, null, null));
        return new Outcome(pages, sent, open, failed);
    }

    /** Zähler eines Laufs, von mehreren Arbeitern zugleich geschrieben. */
    private static final class Tally {
        final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger pages = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        /** Warum der Lauf keine weiteren Seiten beginnt (Modell aus, Einmalcode, kein Modell); der erste gilt. */
        final java.util.concurrent.atomic.AtomicReference<String> stop =
                new java.util.concurrent.atomic.AtomicReference<>();
        /** Vorgemerkte Bilder; nach jeder Seite um die tatsächlich beschriebenen berichtigt. Unter {@code this}. */
        int reserved;
        int open;
        boolean oversized;
    }

    /** Eine Seite in einem Arbeiter. */
    private void page(String sourceId, PageSnapshot snapshot, PageImages before, int pending, Tally tally,
                      JobContext context) {
        if (tally.stop.get() != null) {
            return;
        }
        try {
            // Zwischen den Bildern einer Seite: Abbrechen wirkt nach dem laufenden Bild, und ein ausgefallenes
            // Modell bekommt nach den Wiederholungen dieses Bildes keine weiteren.
            // Nachrangig: vor jedem Bild zurücktreten, solange der Benutzer selbst Bilder beschreiben lässt.
            if (analysis.priority().awaitIdle(context, () -> waitingForModel(context))) {
                context.phase(COMPONENT, "Bilder beschreiben");
            }
            PageImages after = analysis.describeInBackground(snapshot, entry -> {
                context.checkpoint();
                if (entry.status() == ImageStatus.FAILED && PageImageAnalysis.modelFailure(entry.reason())) {
                    throw new ModelDown(entry.reason());
                }
                if (tally.stop.get() != null) {
                    throw new ModelDown(null);
                }
                if (analysis.priority().awaitIdle(context, () -> waitingForModel(context))) {
                    context.phase(COMPONENT, "Bilder beschreiben");
                }
            });
            int described = newlyDescribed(before, after);
            tally.sent.addAndGet(described);
            synchronized (tally) {
                tally.reserved += described - pending;
            }
            tally.pages.incrementAndGet();
            List<String> failures = after.images().stream().filter(image -> image.status() == ImageStatus.FAILED)
                    .map(ImagePayload.Entry::reason).toList();
            if (failures.isEmpty()) {
                context.written(1);
            } else {
                tally.failed.incrementAndGet();
                context.failed(1);
                failures.stream().filter(PageImageAnalysis::modelFailure).findFirst()
                        .ifPresent(reason -> stop(tally, MODEL_FAILED, () -> context.record(modelFailed(reason))));
            }
        } catch (ModelDown e) {
            if (e.reason != null) {
                tally.failed.incrementAndGet();
                context.failed(1);
                stop(tally, MODEL_FAILED, () -> context.record(modelFailed(e.reason)));
            }
        } catch (SignInRequiredException e) {
            stop(tally, SIGN_IN, () -> context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT,
                    SIGN_IN, "Die Quelle verlangt einen Einmalcode; Bilder dieses Laufs bewusst ausgelassen")));
        } catch (ImageDescriptionException e) {
            if (e.reason() == ImageDescriptionException.Reason.NOT_CONFIGURED) {
                stop(tally, NO_MODEL, () -> context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT,
                        NO_MODEL, "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben")));
            } else if (e.reason() != ImageDescriptionException.Reason.NOT_RELEASED) {
                // NOT_RELEASED: die Freigabe fiel eben weg, die Seite bleibt draußen.
                tally.failed.incrementAndGet();
                context.failed(1);
            }
        } catch (com.aresstack.spaceharvester.application.jobs.JobCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            // Eine Seite, deren Lese-Ansicht oder Bilder sich nicht laden lassen, hält die übrigen nicht auf.
            tally.failed.incrementAndGet();
            context.failed(1);
            LOG.log(System.Logger.Level.WARNING, "Bilder einer Seite in {0} nicht beschrieben ({1})", sourceId,
                    e.getClass().getName());
        }
        context.processed(1);
    }

    /** Hält den Lauf an; nur der erste Grund meldet sich. */
    private static void stop(Tally tally, String code, Runnable report) {
        if (tally.stop.compareAndSet(null, code)) {
            report.run();
        }
    }

    private static void waitingForModel(JobContext context) {
        context.phase(COMPONENT, WAITING_PHASE);
    }

    private static JobEvent.Draft modelFailed(String reason) {
        return new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.JOB, COMPONENT, MODEL_FAILED,
                "Das Vision-Modell antwortet nicht (" + reason + "); Lauf beendet, offene Bilder folgen beim "
                        + "nächsten Lauf", null, null, null);
    }

    /** Das Modell scheiterte an einem Bild auch nach den Wiederholungen; die übrigen Bilder warten. */
    private static final class ModelDown extends RuntimeException {
        final String reason;

        ModelDown(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /** Bilder, deren Laden an einer vorübergehend nicht erreichbaren oder drosselnden Quelle scheiterte. */
    static boolean sourceFailure(String reason) {
        return "source-unavailable".equals(reason) || "source-rate-limited".equals(reason);
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
                        && (PageImageAnalysis.modelFailure(image.reason()) || sourceFailure(image.reason())))
                        .count();
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
