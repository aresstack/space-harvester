package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * „Anhänge vorladen“ als Hintergrundauftrag (Fundstelle 18, Entwurf vom Architekten bestätigt): Öffnet der Benutzer einen
 * Anhang, lädt die App den gezeigten, seine Nachbarn und die Standbilder der Videomarken weiter selbst vor; die übrigen
 * Anhänge derselben Art gibt sie hierher ({@link #request}). Je Quelle und Space sammelt ein Teil die Seiten
 * ({@link BackgroundJobs#startUnlessWaiting}) und läuft auf einem normalen Hintergrundplatz; ein laufender Teil nimmt
 * weitere Seiten seines Space mit. Mit nur einem Platz bleibt es beim bisherigen Weg ({@link #request} ist leer).
 *
 * <p>Die Seiten liegen nur im Speicher, mit dem Lader des Aufrufers: Ein Teil von vor einem Neustart findet nichts mehr
 * und endet sofort. Anhalten lässt die übrigen Anhänge stehen, Fortsetzen lädt sie; Abbrechen verwirft die Seiten des
 * Space. Gescheiterte Anhänge stehen rot mit Grund im Teil, der dann scheitert.
 */
public final class AttachmentPrefetches implements AutoCloseable {

    /** Kennung der Art. */
    public static final String KIND = "attachment-prefetch";
    /** Titel der Gruppe. */
    public static final String TITLE = "Anhänge vorladen";
    /** Angabe des Teils: die Seite, die ihn anlegte (nur zur Anzeige). */
    public static final String PAGE = "page";
    /** Fehlercode: Anhänge ließen sich nicht laden. */
    public static final String ATTACHMENTS_MISSING = "attachments-missing";
    private static final String COMPONENT = "attachment-prefetch";
    /** Info beim Abschließen eines Teils von vor dem Neustart. */
    static final String AFTER_RESTART = "Seiten von vor dem Neustart liegen nicht mehr vor";

    /** Lädt einen Anhang und legt ihn ab, mit den Rechten des Aufrufers. */
    @FunctionalInterface
    public interface Loader {

        void load(String sourceId, String pageId, String attachmentId) throws Exception;
    }

    private record Where(String sourceId, String spaceKey) {
    }

    private record Page(String pageId, Deque<String> attachments, Loader loader) {
    }

    private final BackgroundJobs background;
    private final Object lock = new Object();
    /** Wartende Seiten je Space, in der Reihenfolge der Anfragen; unter {@link #lock}. */
    private final Map<Where, LinkedHashMap<String, Page>> pending = new HashMap<>();
    /** Der laufende Teil je Space, der weitere Seiten mitnimmt; unter {@link #lock}. */
    private final Map<Where, String> draining = new HashMap<>();
    /**
     * Die Seiten, die ein Teil noch laden soll, ohne dass er läuft, je Auftrag: was ein wartender Teil annahm und was
     * ein angehaltener zurücklegte. Wird er abgebrochen oder gelöscht, fallen genau diese Seiten weg, später angefragte
     * bleiben; sobald er läuft, nimmt er sie ohnehin. Unter {@link #lock}.
     */
    private final Map<String, Stop> stopped = new HashMap<>();
    private final com.aresstack.spaceharvester.application.observe.Subscription states;

    private record Stop(Where where, Map<String, Page> pages) {
    }

    public AttachmentPrefetches(BackgroundJobs background) {
        this.background = Objects.requireNonNull(background, "background");
        background.register(new BackgroundKind(KIND, this::work, () -> false));
        // Die Seiten liegen nur im Speicher: Teile von vor dem Neustart abschließen, statt sie als unterbrochen stehen
        // zu lassen (wie ConnectionChecks).
        background.retireResumable(KIND, AFTER_RESTART);
        // Anhalten und Abbrechen unterscheidet erst der Zustand: Abbrechen verwirft, was beim Anhalten liegen blieb.
        // Wartend und angehalten bleiben die Seiten dem Teil zugeordnet (auch nach Fortsetzen ohne freien Platz).
        this.states = background.subscribe(KIND, status -> {
            switch (status.state()) {
                case QUEUED, PAUSED -> { }
                case CANCELLED -> discard(status.id());
                case RUNNING, COMPLETED, FAILED -> {
                    synchronized (lock) {
                        stopped.remove(status.id());
                    }
                }
            }
        }, last -> discard(last.id()));
    }

    private void discard(String jobId) {
        synchronized (lock) {
            Stop stop = stopped.remove(jobId);
            if (stop == null) {
                return;
            }
            LinkedHashMap<String, Page> pages = pending.get(stop.where());
            if (pages != null) {
                stop.pages().forEach(pages::remove);
                if (pages.isEmpty()) {
                    pending.remove(stop.where());
                }
            }
        }
    }

    @Override
    public void close() {
        states.close();
    }

    /**
     * Gibt die Anhänge einer Seite zum Vorladen ab; wartet die Seite schon, kommen die neuen Anhänge hinzu (etwa die
     * einer anderen Art).
     *
     * @return der Teil, der sie lädt (neu, wartend oder laufend); leer bei nur einem Platz unter „Vorbereitung“ oder wenn
     *         kein Teil angelegt werden kann, dann lädt der Aufrufer wie bisher selbst
     */
    public Optional<JobStatus> request(String sourceId, String spaceKey, String pageId, List<String> attachmentIds,
                                       Loader loader) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(loader, "loader");
        if (attachmentIds == null || attachmentIds.isEmpty() || !background.canRun()) {
            return Optional.empty();
        }
        Where where = new Where(sourceId, spaceKey);
        String runningPart;
        Page page;
        synchronized (lock) {
            LinkedHashMap<String, Page> pages = pending.computeIfAbsent(where, ignored -> new LinkedHashMap<>());
            page = pages.get(pageId);
            if (page == null) {
                page = new Page(pageId, new ArrayDeque<>(attachmentIds), loader);
                pages.put(pageId, page);
            } else {
                // Wartende Seiten lädt gerade niemand: Hinzufügen unter der Sperre genügt.
                for (String attachmentId : attachmentIds) {
                    if (!page.attachments().contains(attachmentId)) {
                        page.attachments().addLast(attachmentId);
                    }
                }
            }
            runningPart = draining.get(where);
        }
        Page requested = page;
        if (runningPart != null) {
            Optional<JobStatus> running = background.part(runningPart);
            if (running.isPresent()) {
                return running;
            }
        }
        try {
            Optional<BackgroundGroup> group = background.startUnlessWaiting(KIND, TITLE,
                    new BackgroundPart.Draft(sourceId, spaceKey, Map.of(PAGE, pageId)));
            Optional<JobStatus> part = group.map(started -> started.parts().getFirst())
                    .or(() -> background.waiting(KIND, sourceId, spaceKey));
            part.filter(status -> status.state() == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED)
                    .ifPresent(queued -> assign(queued.id(), where, requested));
            return part;
        } catch (RuntimeException e) {
            synchronized (lock) {
                LinkedHashMap<String, Page> pages = pending.get(where);
                if (pages != null) {
                    pages.remove(pageId);
                    if (pages.isEmpty()) {
                        pending.remove(where);
                    }
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Ordnet eine Seite dem wartenden Teil zu: Wird er abgebrochen, bevor er läuft, fällt sie weg (sonst nähme der
     * nächste Teil des Space sie mit).
     */
    private void assign(String jobId, Where where, Page page) {
        synchronized (lock) {
            stopped.computeIfAbsent(jobId, ignored -> new Stop(where, new LinkedHashMap<>()))
                    .pages().put(page.pageId(), page);
        }
    }

    private JobTask work(BackgroundPart part) {
        Where where = new Where(part.sourceId(), part.partitionKey());
        return JobTask.blocking(context -> {
            synchronized (lock) {
                draining.put(where, context.jobId());
            }
            long failed = 0;
            try {
                while (true) {
                    Page page;
                    synchronized (lock) {
                        LinkedHashMap<String, Page> pages = pending.get(where);
                        if (pages == null || pages.isEmpty()) {
                            // Im selben Sperrblock wie das Eintragen einer Anfrage: Nichts bleibt liegen.
                            pending.remove(where);
                            draining.remove(where, context.jobId());
                            break;
                        }
                        page = pages.pollFirstEntry().getValue();
                    }
                    context.discovered(page.attachments().size());
                    failed += load(page, where, context);
                }
            } finally {
                synchronized (lock) {
                    draining.remove(where, context.jobId());
                }
            }
            if (failed > 0) {
                throw new JobFailedException(ATTACHMENTS_MISSING, failed + (failed == 1 ? " Anhang ließ" : " Anhänge "
                        + "ließen") + " sich nicht vorladen; sie laden beim Öffnen");
            }
        });
    }

    /**
     * Lädt die Anhänge einer Seite; endet der Teil vorzeitig, legt er den Rest zurück (Fortsetzen lädt ihn), und
     * Abbrechen verwirft ihn danach ({@link #discard}).
     */
    private long load(Page page, Where where, com.aresstack.spaceharvester.application.jobs.JobContext context) {
        long failed = 0;
        while (!page.attachments().isEmpty()) {
            try {
                context.checkpoint();
            } catch (RuntimeException stop) {
                synchronized (lock) {
                    LinkedHashMap<String, Page> rest = new LinkedHashMap<>();
                    rest.put(page.pageId(), page);
                    LinkedHashMap<String, Page> pages = pending.get(where);
                    if (pages != null) {
                        pages.forEach(rest::putIfAbsent);
                    }
                    pending.put(where, rest);
                    stopped.put(context.jobId(), new Stop(where, new LinkedHashMap<>(rest)));
                }
                throw stop;
            }
            String attachmentId = page.attachments().pollFirst();
            try {
                page.loader().load(where.sourceId(), page.pageId(), attachmentId);
                context.processed(1);
                context.written(1);
            } catch (Exception e) {
                failed++;
                context.processed(1);
                context.failed(1);
                // Rot mit Grund: nichts verschwindet still.
                context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE, COMPONENT,
                        ATTACHMENTS_MISSING, "Ein Anhang der Seite " + page.pageId() + " ließ sich nicht vorladen: "
                        + e.getClass().getSimpleName(), null, null, e));
            }
        }
        return failed;
    }

    /** Für Tests: wie viele Seiten im Space noch warten. */
    int waitingPages(String sourceId, String spaceKey) {
        synchronized (lock) {
            LinkedHashMap<String, Page> pages = pending.get(new Where(sourceId, spaceKey));
            return pages == null ? 0 : pages.size();
        }
    }
}
