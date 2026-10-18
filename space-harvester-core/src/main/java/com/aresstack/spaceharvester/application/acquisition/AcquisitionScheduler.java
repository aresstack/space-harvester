package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Eine Warteschlange für alle Beschaffungen von Seiteninhalten (ADR 0016, Punkt 5 und 6): den Vordergrund (Funde einer
 * Suche, {@code SearchHitPrefetch}) und den Hintergrund ({@link BackgroundFill}). Beide gehen durch denselben
 * Beschaffungsweg {@code load}, in Harvester und Server {@code PageQueries.page}: Version prüfen, laden, ablegen, dann
 * Volltextindex und Anreicherungen samt Embeddings. Es gibt keinen zweiten Ladeweg.
 *
 * <p>Regeln ({@link Limits}):
 *
 * <ul>
 *   <li><b>Vorrang des Vordergrunds.</b> Er hat eigene {@link Limits#foreground()} Plätze, die der Hintergrund nie
 *       belegt. Solange eine Seite im Vordergrund wartet oder lädt, beginnt der Hintergrund keine neue Seite; ebenso,
 *       solange jemand {@link #pauseBackground()} hält (eine Aktion des Benutzers), und bis {@code resumeDelay}
 *       (Standard {@link #DEFAULT_RESUME_DELAY}) nach der letzten Aktion des Vordergrunds (Anfrage, Ende eines Abrufs,
 *       Freigabe einer Pause) vergangen ist. Danach läuft er von selbst weiter.</li>
 *   <li><b>Je Quelle begrenzt.</b> Je Quelle laden höchstens {@link Limits#perSource()} Seiten zugleich, der
 *       Hintergrund davon höchstens {@code perSource − 1} (mindestens eine): ein Platz je Quelle bleibt dem Vordergrund
 *       reserviert. Insgesamt lädt der Hintergrund höchstens {@link Limits#background()} Seiten zugleich.</li>
 *   <li><b>Hochstufen statt doppelt laden.</b> Je Seite ({@link PageKey}) gibt es höchstens einen Eintrag. Fragt der
 *       Vordergrund eine Seite an, die im Hintergrund wartet, rückt sie in den Vordergrund; lädt sie schon, wartet er
 *       auf denselben Abruf.</li>
 *   <li><b>Kooperativ anhalten.</b> Sichere Punkte sind die Grenzen zwischen zwei Seiten: Der Hintergrund hält an,
 *       indem er keine neue beginnt. Ein laufender Abruf wird nie unterbrochen, auch nicht beim Schließen; ein
 *       Interrupt mitten im Schreiben schlösse die Dateikanäle der Lucene-Indizes (wie {@code Enrichments.stop}).</li>
 *   <li><b>Keine Benutzeranmeldung im Hintergrund.</b> Hintergrundanfragen sind technische Aktionen des Programms;
 *       ein Aufrufer mit durchgereichter Anmeldung ({@link AccessContext#credential()}) wird abgelehnt. Wartet eine
 *       Seite für beide, lädt sie mit dem technischen Aufrufer; bricht ein delegierter Benutzer ab, fällt seine
 *       Anmeldung aus dem Eintrag.</li>
 * </ul>
 *
 * <p>Protokolliert nie Seiten-IDs, nur Quellen und Zähler.
 */
public final class AcquisitionScheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AcquisitionScheduler.class.getName());

    /** So lange nach der letzten Aktion des Vordergrunds bleibt der Hintergrund still. */
    public static final Duration DEFAULT_RESUME_DELAY = Duration.ofSeconds(5);

    /** Wer eine Seite braucht. */
    public enum Priority {
        /** Eine Aktion des Benutzers, etwa die Funde einer Suche. */
        FOREGROUND,
        /** Füllen in Leerlaufzeit, eine technische Aktion des Programms. */
        BACKGROUND
    }

    /**
     * Grenzen des Planers.
     *
     * @param foreground Seiten, die der Vordergrund höchstens zugleich lädt (reserviert), mindestens 1
     * @param background Seiten, die der Hintergrund höchstens zugleich lädt; 0 schaltet ihn ab
     * @param perSource  Seiten je Quelle höchstens zugleich, beide Wege zusammen, mindestens 1
     * @param capacity   wartende Seiten je Weg höchstens; darüber lehnt {@link #request} ab
     */
    public record Limits(int foreground, int background, int perSource, int capacity) {

        /** Vier im Vordergrund (wie bisher die Suche), zwei im Hintergrund, vier je Quelle, 200 wartende. */
        public static final Limits DEFAULT = new Limits(4, 2, 4, 200);

        public Limits {
            if (foreground < 1 || perSource < 1 || capacity < 1 || background < 0) {
                throw new IllegalArgumentException("Grenzen der Beschaffung: Vordergrund, je Quelle und Warteschlange "
                        + "mindestens 1, Hintergrund mindestens 0");
            }
        }

        /** Wie diese, mit {@code background} Plätzen im Hintergrund. */
        public Limits withBackground(int value) {
            return new Limits(foreground, value, perSource, capacity);
        }

        /** So viele Hintergrundabrufe laufen je Quelle höchstens. */
        int backgroundPerSource() {
            return Math.max(1, perSource - 1);
        }
    }

    /**
     * Stand des Planers, nur Zähler.
     *
     * @param promoted wie oft eine Seite aus dem Hintergrund hochgestuft wurde, seit dem Start
     */
    public record Status(int foregroundQueued, int foregroundRunning, int backgroundQueued, int backgroundRunning,
                         long promoted, AcquisitionStatus.Pause pause) {
    }

    private final BiConsumer<AccessContext, PageKey> load;
    private volatile Limits limits;
    private final long resumeDelayNanos;
    /** Letzte Aktion des Vordergrunds ({@link System#nanoTime()}), anfangs lange her. */
    private long lastForeground;
    private boolean wakeScheduled;
    private final Executor executor;
    private final ExecutorService owned;
    private final Object lock = new Object();
    private final Map<PageKey, Entry> entries = new HashMap<>();
    private final LinkedHashSet<Entry> foregroundQueue = new LinkedHashSet<>();
    private final LinkedHashSet<Entry> backgroundQueue = new LinkedHashSet<>();
    private final Map<String, int[]> runningPerSource = new HashMap<>();
    private int foregroundRunning;
    private int backgroundRunning;
    private int pauses;
    private long promoted;
    private boolean closed;

    /**
     * Lädt auf eigenen virtuellen Threads.
     *
     * @param load lädt eine Seite mit den Rechten des Aufrufers in den Cache und kehrt danach zurück; eine Ausnahme heißt
     *             „nicht geladen“
     */
    public AcquisitionScheduler(BiConsumer<AccessContext, PageKey> load, Limits limits) {
        this(load, limits, null);
    }

    /**
     * @param executor führt die Abrufe aus; der Planer übergibt nur, wofür ein Platz frei ist, und schließt ihn nicht.
     *                 {@code null}: eigene virtuelle Threads
     */
    public AcquisitionScheduler(BiConsumer<AccessContext, PageKey> load, Limits limits, Executor executor) {
        this(load, limits, executor, DEFAULT_RESUME_DELAY);
    }

    /** @param resumeDelay so lange nach der letzten Aktion des Vordergrunds bleibt der Hintergrund still */
    public AcquisitionScheduler(BiConsumer<AccessContext, PageKey> load, Limits limits, Executor executor,
                                Duration resumeDelay) {
        this.load = Objects.requireNonNull(load, "load");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (resumeDelay == null || resumeDelay.isNegative()) {
            throw new IllegalArgumentException("resumeDelay darf nicht negativ sein");
        }
        this.resumeDelayNanos = resumeDelay.toNanos();
        this.lastForeground = System.nanoTime() - resumeDelayNanos;
        if (executor == null) {
            this.owned = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("acquisition-", 0).factory());
            this.executor = owned;
        } else {
            this.owned = null;
            this.executor = executor;
        }
    }

    public Limits limits() {
        return limits;
    }

    /**
     * Ändert die Parallelität des Hintergrunds zur Laufzeit (ADR 0015); laufende Abrufe enden regulär, neue folgen
     * der neuen Grenze.
     */
    public void backgroundParallelism(int background) {
        synchronized (lock) {
            limits = limits.withBackground(background);
        }
        dispatch();
    }

    /**
     * Fordert eine Seite an. Wartet oder lädt sie schon, teilt sich die Anfrage den Eintrag (ein Abruf); eine Anfrage
     * des Vordergrunds stuft eine im Hintergrund wartende Seite hoch.
     *
     * @throws RejectedExecutionException wenn die Warteschlange dieses Wegs voll oder der Planer geschlossen ist
     * @throws IllegalArgumentException   bei einer Hintergrundanfrage mit durchgereichter Benutzeranmeldung
     */
    public Ticket request(AccessContext caller, PageKey key, Priority priority) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(priority, "priority");
        if (priority == Priority.BACKGROUND && caller.credential() != null) {
            throw new IllegalArgumentException("Der Hintergrund lädt nie mit der Anmeldung eines Benutzers");
        }
        Ticket ticket;
        synchronized (lock) {
            if (closed) {
                throw new RejectedExecutionException("Beschaffung beendet");
            }
            Entry entry = entries.get(key);
            if (entry == null) {
                LinkedHashSet<Entry> queue = queue(priority);
                if (queue.size() >= limits.capacity()) {
                    throw new RejectedExecutionException("Beschaffung ausgelastet");
                }
                entry = new Entry(key, priority);
                entries.put(key, entry);
                queue.add(entry);
            } else if (priority == Priority.FOREGROUND && !entry.running && entry.queued == Priority.BACKGROUND) {
                // Hochstufen: dieselbe Seite, kein zweiter Abruf.
                backgroundQueue.remove(entry);
                foregroundQueue.add(entry);
                entry.queued = Priority.FOREGROUND;
                promoted++;
            }
            ticket = new Ticket(entry, priority);
            if (priority == Priority.FOREGROUND) {
                lastForeground = System.nanoTime();
                entry.foreground.put(ticket, caller);
            } else {
                entry.background.add(ticket);
                if (entry.technical == null) {
                    entry.technical = caller;
                }
            }
        }
        dispatch();
        return ticket;
    }

    /** Ob gerade etwas im Vordergrund wartet oder lädt. */
    public boolean foregroundBusy() {
        synchronized (lock) {
            return foregroundRunning > 0 || !foregroundQueue.isEmpty();
        }
    }

    /** Lage des Vordergrunds: Parallelität und was dort wartet. */
    public int foregroundBacklog() {
        synchronized (lock) {
            return foregroundQueue.size();
        }
    }

    public Status status() {
        synchronized (lock) {
            return new Status(foregroundQueue.size(), foregroundRunning, backgroundQueue.size(), backgroundRunning,
                    promoted, pause());
        }
    }

    /** Warum der Hintergrund gerade keine neue Seite beginnt; {@code NONE}, wenn er darf. Unter {@link #lock}. */
    private AcquisitionStatus.Pause pause() {
        if (closed) {
            return AcquisitionStatus.Pause.CLOSED;
        }
        if (foregroundRunning > 0 || !foregroundQueue.isEmpty()) {
            return AcquisitionStatus.Pause.FOREGROUND;
        }
        if (pauses > 0) {
            return AcquisitionStatus.Pause.USER_ACTION;
        }
        if (System.nanoTime() - lastForeground < resumeDelayNanos) {
            return AcquisitionStatus.Pause.COOLDOWN;
        }
        return AcquisitionStatus.Pause.NONE;
    }

    /**
     * Hält den Hintergrund an, bis die Rückgabe geschlossen wird, etwa während einer Aktion des Benutzers: Laufende
     * Abrufe enden regulär, neue beginnen erst danach. Mehrfach haltbar.
     */
    public AutoCloseable pauseBackground() {
        synchronized (lock) {
            pauses++;
        }
        boolean[] released = {false};
        return () -> {
            synchronized (lock) {
                if (released[0]) {
                    return;
                }
                released[0] = true;
                pauses--;
                lastForeground = System.nanoTime();
            }
            dispatch();
        };
    }

    private LinkedHashSet<Entry> queue(Priority priority) {
        return priority == Priority.FOREGROUND ? foregroundQueue : backgroundQueue;
    }

    private int running(String sourceId, boolean background) {
        int[] counts = runningPerSource.get(sourceId);
        return counts == null ? 0 : background ? counts[1] : counts[0] + counts[1];
    }

    private void count(Entry entry, int delta) {
        int[] counts = runningPerSource.computeIfAbsent(entry.key.sourceId(), id -> new int[2]);
        counts[entry.slot == Priority.FOREGROUND ? 0 : 1] += delta;
        if (counts[0] == 0 && counts[1] == 0) {
            runningPerSource.remove(entry.key.sourceId());
        }
        if (entry.slot == Priority.FOREGROUND) {
            foregroundRunning += delta;
        } else {
            backgroundRunning += delta;
        }
    }

    /** Startet, wofür ein Platz frei ist: zuerst der Vordergrund, der Hintergrund nur, wenn dort nichts ansteht. */
    private void dispatch() {
        List<Entry> start = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (Iterator<Entry> it = foregroundQueue.iterator();
                 it.hasNext() && foregroundRunning < limits.foreground(); ) {
                Entry entry = it.next();
                if (running(entry.key.sourceId(), false) < limits.perSource()) {
                    it.remove();
                    begin(entry, Priority.FOREGROUND);
                    start.add(entry);
                }
            }
            AcquisitionStatus.Pause pause = pause();
            if (pause == AcquisitionStatus.Pause.COOLDOWN && !backgroundQueue.isEmpty() && !wakeScheduled) {
                // Weiter, sobald die Wartezeit nach der letzten Aktion des Vordergrunds um ist.
                wakeScheduled = true;
                long remaining = Math.max(0, lastForeground + resumeDelayNanos - System.nanoTime());
                Thread.ofVirtual().name("acquisition-resume").start(() -> {
                    try {
                        Thread.sleep(Duration.ofNanos(remaining).plusMillis(1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (lock) {
                        wakeScheduled = false;
                    }
                    dispatch();
                });
            }
            if (pause == AcquisitionStatus.Pause.NONE) {
                for (Iterator<Entry> it = backgroundQueue.iterator();
                     it.hasNext() && backgroundRunning < limits.background(); ) {
                    Entry entry = it.next();
                    String sourceId = entry.key.sourceId();
                    if (running(sourceId, false) < limits.perSource()
                            && running(sourceId, true) < limits.backgroundPerSource()) {
                        it.remove();
                        begin(entry, Priority.BACKGROUND);
                        start.add(entry);
                    }
                }
            }
        }
        for (Entry entry : start) {
            try {
                executor.execute(() -> run(entry));
            } catch (RejectedExecutionException e) {
                finish(entry, false);
            }
        }
    }

    private void begin(Entry entry, Priority slot) {
        entry.running = true;
        entry.slot = slot;
        // Technisch vor delegiert: so bleibt keine Benutzeranmeldung länger als nötig im Spiel.
        entry.caller = entry.technical != null ? entry.technical : entry.foreground.values().iterator().next();
        count(entry, 1);
    }

    private void run(Entry entry) {
        boolean stored = false;
        try {
            load.accept(entry.caller, entry.key);
            stored = true;
        } catch (RuntimeException e) {
            // Ohne Seiten-ID.
            LOG.log(System.Logger.Level.INFO, "Beschaffung einer Seite in {0} gescheitert ({1})",
                    entry.key.sourceId(), e.getClass().getName());
        } finally {
            finish(entry, stored);
        }
    }

    private void finish(Entry entry, boolean stored) {
        List<Ticket> tickets;
        synchronized (lock) {
            entries.remove(entry.key, entry);
            if (entry.slot == Priority.FOREGROUND) {
                lastForeground = System.nanoTime();
            }
            count(entry, -1);
            entry.caller = null;
            tickets = entry.tickets();
            entry.foreground.clear();
            entry.background.clear();
            lock.notifyAll();
        }
        tickets.forEach(ticket -> ticket.done.complete(stored));
        dispatch();
    }

    /**
     * Beendet den Planer: Wartendes entfällt (seine Anfragen gelten als abgebrochen), laufende Abrufe enden regulär und
     * ohne Unterbrechung; wartet auf sie.
     */
    @Override
    public void close() throws InterruptedException {
        List<Ticket> dropped = new ArrayList<>();
        synchronized (lock) {
            if (!closed) {
                closed = true;
                for (Entry entry : List.copyOf(foregroundQueue)) {
                    dropped.addAll(entry.tickets());
                    entries.remove(entry.key, entry);
                }
                for (Entry entry : List.copyOf(backgroundQueue)) {
                    dropped.addAll(entry.tickets());
                    entries.remove(entry.key, entry);
                }
                foregroundQueue.clear();
                backgroundQueue.clear();
            }
        }
        dropped.forEach(ticket -> ticket.done.cancel(false));
        synchronized (lock) {
            while (foregroundRunning + backgroundRunning > 0) {
                lock.wait(30_000);
                if (foregroundRunning + backgroundRunning > 0) {
                    LOG.log(System.Logger.Level.INFO, "Beschaffung endet noch; warte auf {0} laufende Abrufe",
                            foregroundRunning + backgroundRunning);
                }
            }
        }
        if (owned != null) {
            // Kein Unterbrechen: es läuft nichts mehr.
            owned.shutdown();
        }
    }

    /** Eine Seite und wer sie braucht. */
    private static final class Entry {

        private final PageKey key;
        /** Wo die Seite wartet; nach dem Start ohne Bedeutung. */
        private Priority queued;
        private boolean running;
        /** Auf welchem Platz sie lädt. */
        private Priority slot;
        /** Der Aufrufer des laufenden Abrufs; danach {@code null}. */
        private AccessContext caller;
        /** Der technische Aufrufer des Hintergrunds, wenn der die Seite auch braucht. */
        private AccessContext technical;
        private final Map<Ticket, AccessContext> foreground = new LinkedHashMap<>();
        private final List<Ticket> background = new ArrayList<>();

        private Entry(PageKey key, Priority queued) {
            this.key = key;
            this.queued = queued;
        }

        private List<Ticket> tickets() {
            List<Ticket> all = new ArrayList<>(foreground.keySet());
            all.addAll(background);
            return all;
        }
    }

    /**
     * Eine Anfrage: {@link #done()} wird {@code true}, wenn die Seite im Cache liegt, {@code false}, wenn sie sich
     * nicht laden ließ, und ist abgebrochen nach {@link #cancel()} oder {@link #close()}.
     */
    public final class Ticket {

        private final Entry entry;
        private final Priority priority;
        private final CompletableFuture<Boolean> done = new CompletableFuture<>();

        private Ticket(Entry entry, Priority priority) {
            this.entry = entry;
            this.priority = priority;
        }

        public CompletableFuture<Boolean> done() {
            return done;
        }

        public Priority priority() {
            return priority;
        }

        /**
         * Zieht die Anfrage zurück. Wartet die Seite noch und braucht sie sonst niemand, entfällt ihr Abruf; braucht sie
         * nur noch der Hintergrund, rückt sie dorthin zurück, mit dem technischen Aufrufer. Ein laufender Abruf endet
         * regulär.
         *
         * @return {@code false}, wenn die Anfrage schon beendet war
         */
        public boolean cancel() {
            boolean demoted = false;
            synchronized (lock) {
                if (done.isDone()) {
                    return false;
                }
                if (priority == Priority.FOREGROUND) {
                    entry.foreground.remove(this);
                } else {
                    entry.background.remove(this);
                    if (entry.background.isEmpty()) {
                        entry.technical = null;
                    }
                }
                if (!entry.running && entries.get(entry.key) == entry) {
                    if (entry.foreground.isEmpty() && entry.background.isEmpty()) {
                        queue(entry.queued).remove(entry);
                        entries.remove(entry.key, entry);
                    } else if (entry.foreground.isEmpty() && entry.queued == Priority.FOREGROUND) {
                        foregroundQueue.remove(entry);
                        backgroundQueue.add(entry);
                        entry.queued = Priority.BACKGROUND;
                        demoted = true;
                    }
                }
            }
            boolean cancelled = done.cancel(false);
            if (demoted || cancelled) {
                dispatch();
            }
            return cancelled;
        }
    }
}
