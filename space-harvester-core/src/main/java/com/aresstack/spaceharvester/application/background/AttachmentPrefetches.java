package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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
 * und endet sofort. Anhalten lässt die übrigen Anhänge stehen, Fortsetzen lädt sie; Abbrechen oder Löschen verwirft die
 * Seiten, für die der Teil steht, auch bevor er läuft. Was nach dem Abbrechen kommt, lädt ein neuer Teil. Gescheiterte
 * Anhänge stehen rot mit Grund im Teil, der dann scheitert, auch über Anhalten und Fortsetzen hinweg. Je Space warten
 * höchstens {@code jobs.max-waiting-prefetches} Anhänge ({@link JobLimits#maxWaitingPrefetches()}).
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

    /** Die Anhänge einer Seite aus einer Anfrage. Felder außer der Liste unter {@link #lock}. */
    private static final class Page {

        final String pageId;
        /** Nur der Teil, der die Seite genommen hat, liest daraus; wartend ändert sie niemand. */
        final Deque<String> attachments;
        final Loader loader;
        /** Der Teil, der für die wartende Seite steht: Wird er abgebrochen oder gelöscht, fällt sie weg. */
        String owner;
        /** Der Teil, der sie gerade lädt. */
        String takenBy;
        /** Schon in {@code discovered} gezählt (ein angehaltener Teil legt den Rest zurück). */
        boolean counted;

        Page(String pageId, Deque<String> attachments, Loader loader) {
            this.pageId = pageId;
            this.attachments = attachments;
            this.loader = loader;
        }
    }

    private final BackgroundJobs background;
    private final int maxWaiting;
    private final Object lock = new Object();
    /** Wartende Seiten je Space, in der Reihenfolge der Anfragen; unter {@link #lock}. */
    private final Map<Where, Deque<Page>> pending = new HashMap<>();
    /** Der laufende Teil je Space, der weitere Seiten mitnimmt; unter {@link #lock}. */
    private final Map<Where, String> draining = new HashMap<>();
    /** Gescheiterte Anhänge je Teil, über Anhalten und Fortsetzen hinweg; unter {@link #lock}. */
    private final Map<String, Long> failures = new HashMap<>();
    private final com.aresstack.spaceharvester.application.observe.Subscription states;
    /** Der Netzzustand; ohne ihn zählt ein Netzfehler wie jeder andere. */
    private volatile com.aresstack.spaceharvester.application.network.NetworkState network;

    /** Mit dem Standard {@link JobLimits#DEFAULT_MAX_WAITING_PREFETCHES}. */
    public AttachmentPrefetches(BackgroundJobs background) {
        this(background, JobLimits.DEFAULT_MAX_WAITING_PREFETCHES);
    }

    /** @param maxWaiting so viele Anhänge warten höchstens je Quelle und Space ({@code jobs.max-waiting-prefetches}) */
    public AttachmentPrefetches(BackgroundJobs background, int maxWaiting) {
        this.background = Objects.requireNonNull(background, "background");
        if (maxWaiting < 1) {
            throw new IllegalArgumentException("maxWaiting muss mindestens 1 sein");
        }
        this.maxWaiting = maxWaiting;
        background.register(new BackgroundKind(KIND, this::work, () -> false));
        // Die Seiten liegen nur im Speicher: Teile von vor dem Neustart abschließen, statt sie als unterbrochen stehen
        // zu lassen (wie ConnectionChecks).
        background.retireResumable(KIND, AFTER_RESTART);
        // Abbrechen und Löschen verwerfen die Seiten, für die der Teil steht; wartend und angehalten bleiben sie ihm.
        this.states = background.subscribe(KIND, status -> {
            switch (status.state()) {
                case QUEUED, RUNNING, PAUSED -> { }
                case CANCELLED -> discard(status.id());
                case COMPLETED, FAILED -> release(status.id());
            }
        }, last -> discard(last.id()));
    }

    /** Verwirft die wartenden Seiten des Teils. */
    private void discard(String jobId) {
        synchronized (lock) {
            failures.remove(jobId);
            pending.values().forEach(pages -> pages.removeIf(page -> jobId.equals(page.owner)));
            pending.values().removeIf(Deque::isEmpty);
        }
    }

    /** Der Teil endete: Was ihm noch zugeordnet ist, lädt der nächste Teil des Space, nichts geht verloren. */
    private void release(String jobId) {
        synchronized (lock) {
            failures.remove(jobId);
            pending.values().forEach(pages -> pages.forEach(page -> {
                if (jobId.equals(page.owner)) {
                    page.owner = null;
                }
            }));
        }
    }

    /**
     * Hängt den Netzzustand an (Angelo 22.11.2026): Ein Netzfehler lässt keinen Anhang scheitern; der Teil wartet
     * sichtbar, bis das Netz zurück ist, und lädt denselben Anhang im selben Teil erneut.
     */
    public void network(com.aresstack.spaceharvester.application.network.NetworkState network) {
        this.network = network;
    }

    @Override
    public void close() {
        states.close();
    }

    /**
     * Gibt die Anhänge einer Seite zum Vorladen ab; schon wartende Anhänge derselben Seite zählen nicht doppelt.
     *
     * @return der Teil, der sie lädt (neu, wartend, angehalten oder laufend); leer bei nur einem Platz unter
     *         „Vorbereitung“, über {@code jobs.max-waiting-prefetches} wartenden Anhängen im Space oder wenn kein Teil angelegt werden
     *         kann: dann wartet nichts davon, und der Aufrufer lädt wie bisher selbst
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
        Page page = null;
        synchronized (lock) {
            Deque<Page> pages = pending.computeIfAbsent(where, ignored -> new ArrayDeque<>());
            java.util.Set<String> waiting = new java.util.HashSet<>();
            int count = 0;
            for (Page each : pages) {
                count += each.attachments.size();
                if (each.pageId.equals(pageId)) {
                    waiting.addAll(each.attachments);
                }
            }
            List<String> fresh = attachmentIds.stream().distinct().filter(id -> !waiting.contains(id)).toList();
            if (count + fresh.size() > maxWaiting) {
                if (pages.isEmpty()) {
                    pending.remove(where);
                }
                return Optional.empty();
            }
            if (!fresh.isEmpty()) {
                page = new Page(pageId, new ArrayDeque<>(fresh), loader);
                pages.addLast(page);
            } else if (pages.isEmpty()) {
                pending.remove(where);
            }
        }
        // Ein wartender Teil kann zwischen startUnlessWaiting und waiting starten: dann noch einmal nachsehen.
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<JobStatus> part = target(where, pageId);
            if (part.isPresent()) {
                if (page != null) {
                    synchronized (lock) {
                        if (page.owner == null) {
                            page.owner = part.get().id();
                        }
                    }
                }
                return part;
            }
        }
        // Kein Teil: Das Ergebnis passt zum Stand. Die Seite wartet nicht, außer ein Teil hat sie schon genommen.
        String taken;
        synchronized (lock) {
            if (page == null) {
                return Optional.empty();
            }
            taken = page.takenBy;
            if (taken == null) {
                Deque<Page> pages = pending.get(where);
                if (pages != null) {
                    Page own = page;
                    pages.removeIf(each -> each == own);
                    if (pages.isEmpty()) {
                        pending.remove(where);
                    }
                }
                return Optional.empty();
            }
        }
        return background.part(taken);
    }

    /**
     * Der Teil, der eine eben eingetragene Seite des Space lädt: der laufende, solange er nicht abgebrochen wird (ein
     * angehaltener nimmt sie beim Fortsetzen), sonst ein wartender oder neuer; leer, wenn keiner angelegt werden kann.
     */
    private Optional<JobStatus> target(Where where, String pageId) {
        String runningPart;
        synchronized (lock) {
            runningPart = draining.get(where);
        }
        if (runningPart != null) {
            Optional<JobStatus> running = background.part(runningPart)
                    .filter(status -> status.state() == JobState.RUNNING
                            && (!status.cancelRequested() || status.pauseRequested()));
            if (running.isPresent()) {
                return running;
            }
        }
        try {
            Optional<BackgroundGroup> group = background.startUnlessWaiting(KIND, TITLE,
                    new BackgroundPart.Draft(where.sourceId(), where.spaceKey(), Map.of(PAGE, pageId)));
            return group.map(started -> started.parts().getFirst())
                    .or(() -> background.waiting(KIND, where.sourceId(), where.spaceKey()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private JobTask work(BackgroundPart part) {
        Where where = new Where(part.sourceId(), part.partitionKey());
        return JobTask.blocking(context -> {
            String jobId = context.jobId();
            com.aresstack.spaceharvester.application.network.NetworkState net = network;
            com.aresstack.spaceharvester.application.network.NetworkWait wait = net == null ? null
                    : new com.aresstack.spaceharvester.application.network.NetworkWait(net, context, COMPONENT);
            synchronized (lock) {
                draining.put(where, jobId);
            }
            try {
                while (true) {
                    Page page;
                    boolean count;
                    String owner;
                    synchronized (lock) {
                        Deque<Page> pages = pending.get(where);
                        if (pages == null || pages.isEmpty()) {
                            // Im selben Sperrblock wie das Eintragen einer Anfrage: Nichts bleibt liegen.
                            pending.remove(where);
                            draining.remove(where, jobId);
                            break;
                        }
                        page = pages.pollFirst();
                        page.takenBy = jobId;
                        owner = page.owner;
                        count = !page.counted;
                        page.counted = true;
                    }
                    if (abandoned(owner, jobId)) {
                        // Sein Teil wird abgebrochen oder ist es schon; das Verwerfen kommt gleich nach.
                        continue;
                    }
                    if (count) {
                        context.discovered(page.attachments.size());
                    }
                    load(page, where, context, wait);
                }
            } finally {
                synchronized (lock) {
                    draining.remove(where, jobId);
                }
            }
            long failed;
            synchronized (lock) {
                failed = failures.getOrDefault(jobId, 0L);
            }
            if (failed > 0) {
                throw new JobFailedException(ATTACHMENTS_MISSING, failed + (failed == 1 ? " Anhang ließ" : " Anhänge "
                        + "ließen") + " sich nicht vorladen; sie laden beim Öffnen");
            }
        });
    }

    /**
     * Ob die Seite einem anderen Teil gehört, der abgebrochen wird, abgebrochen oder gelöscht ist: Ihr Verwerfen
     * ({@link #discard}) kommt mit eigener Meldung und kann später eintreffen als der Start dieses Teils.
     */
    private boolean abandoned(String owner, String self) {
        if (owner == null || owner.equals(self)) {
            return false;
        }
        return background.part(owner).map(status -> status.state() == JobState.CANCELLED
                || status.state() == JobState.RUNNING && status.cancelRequested() && !status.pauseRequested())
                .orElse(true);
    }

    /**
     * Lädt die Anhänge einer Seite; endet der Teil vorzeitig, legt er den Rest vorn zurück: Fortsetzen lädt ihn,
     * Abbrechen oder Löschen verwirft ihn ({@link #discard}), außer die Seite gehört schon einem anderen Teil.
     */
    private void load(Page page, Where where, com.aresstack.spaceharvester.application.jobs.JobContext context,
                      com.aresstack.spaceharvester.application.network.NetworkWait wait) {
        String jobId = context.jobId();
        while (!page.attachments.isEmpty()) {
            try {
                context.checkpoint();
                if (wait != null) {
                    // Offline wartet der Teil hier, statt jeden übrigen Anhang am Netz scheitern zu lassen.
                    wait.beforeFetch();
                }
            } catch (RuntimeException stop) {
                putBack(page, where, jobId);
                throw stop;
            }
            String attachmentId = page.attachments.pollFirst();
            try {
                page.loader.load(where.sourceId(), page.pageId, attachmentId);
                if (wait != null) {
                    wait.answered();
                }
                context.processed(1);
                context.written(1);
            } catch (Exception e) {
                if (wait != null
                        && com.aresstack.spaceharvester.application.network.NetworkState.networkFailure(e)) {
                    // Kein Versuch verbraucht, nichts rot: derselbe Anhang, sobald das Netz zurück ist.
                    page.attachments.addFirst(attachmentId);
                    try {
                        wait.failed(e);
                    } catch (RuntimeException stop) {
                        putBack(page, where, jobId);
                        throw stop;
                    }
                    continue;
                }
                if (wait != null) {
                    wait.answered();
                }
                synchronized (lock) {
                    failures.merge(jobId, 1L, Long::sum);
                }
                context.processed(1);
                context.failed(1);
                // Rot mit Grund: nichts verschwindet still.
                context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE, COMPONENT,
                        ATTACHMENTS_MISSING, "Ein Anhang der Seite " + page.pageId + " ließ sich nicht vorladen: "
                        + e.getClass().getSimpleName(), null, null, e));
            }
        }
    }

    /** Legt den Rest der Seite vorn zurück: Fortsetzen lädt ihn, Abbrechen oder Löschen verwirft ihn. */
    private void putBack(Page page, Where where, String jobId) {
        synchronized (lock) {
            page.takenBy = null;
            if (page.owner == null) {
                page.owner = jobId;
            }
            pending.computeIfAbsent(where, ignored -> new ArrayDeque<>()).addFirst(page);
            // Wer jetzt anfragt, schließt nicht mehr an diesen Teil an.
            draining.remove(where, jobId);
        }
    }

    /** Für Tests: wie viele Seiten im Space noch warten. */
    int waitingPages(String sourceId, String spaceKey) {
        synchronized (lock) {
            Deque<Page> pages = pending.get(new Where(sourceId, spaceKey));
            return pages == null ? 0 : pages.size();
        }
    }
}
