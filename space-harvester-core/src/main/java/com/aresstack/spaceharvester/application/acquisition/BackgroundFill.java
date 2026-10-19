package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * Hintergrundfüllung (ADR 0016, Punkt 5): lädt in Leerlaufzeit Seiten, die der Katalog kennt, deren Inhalt aber fehlt
 * ({@link Kind#NOT_LOADED}) oder veraltet ist ({@link Kind#OUTDATED}), über den {@link AcquisitionScheduler} und damit
 * über denselben Beschaffungsweg wie die Suche. Standardmäßig an, solange das Programm läuft; begrenzt durch
 * {@link AcquisitionScheduler.Limits#background()}. Eine Aktion des Benutzers hat Vorrang: Der Planer beginnt keine
 * Hintergrundseite, solange der Vordergrund lädt.
 *
 * <p><b>Was geladen wird:</b> {@link Kind#NOT_LOADED} und {@link Kind#OUTDATED} jeder zugelassenen Quelle, in
 * Rangfolge (zuletzt geändert zuerst, ohne Änderungsdatum zuletzt). Die Füllung ist ein eigener Mechanismus und hängt
 * nicht an der Betriebsart der Synchronisierung ({@code sync.mode}); die bestimmt weiter nur, was ein Abgleich spiegelt
 * oder entfernt. Abschalten geht global ({@code sync.background-fill}) oder je Quelle
 * ({@code sync.sources.<Quelle>.background-fill}); beides prüft {@code eligible} beim Einreihen und beim Laden.
 * Gesperrte Seiten ({@code WITHHELD}) hat der Katalog nicht mehr als Seiten; sie erscheinen nie als Lücke. Die
 * Freigabe an den KI-Dienst prüft die Pipeline selbst: Inhalte nicht freigegebener Quellen gehen nie an den Dienst,
 * Embeddings entstehen nur für freigegebene.
 *
 * <p><b>Rechte.</b> Das Laden ist eine technische Aktion des Programms, nie im Auftrag eines Benutzers: Es läuft mit dem
 * technischen Aufrufer {@code caller} und den Zugangsdaten der Quelle, nur für Quellen, die {@code eligible} zulässt
 * (etwa: Quelle aktiv und für den technischen Aufrufer quellweit freigegeben). Benutzeranmeldungen werden weder
 * benutzt noch gespeichert.
 *
 * <p><b>Dauerhaft.</b> Die Warteschlange ({@link FillQueue}) hält nur Quelle, Seiten-ID und Art. Ein Eintrag fällt
 * erst weg, wenn sein Abruf endet (geladen oder gescheitert) oder seine Quelle ihn nicht mehr will; was beim Beenden
 * noch wartet oder lädt, bleibt und kommt nach dem Neustart zuerst dran. Ist die Schlange leer, sucht die Füllung den
 * Katalog nach Lücken ab, höchstens alle {@link Timing#idle()}.
 *
 * <p>Protokolliert nur Zähler und Quellen, nie Seiten-IDs.
 */
public final class BackgroundFill implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BackgroundFill.class.getName());
    /** So oft schaut die Füllung nach, solange etwas lädt. */
    private static final Duration POLL = Duration.ofSeconds(1);

    /** Standard der Parallelität im Hintergrund. */
    public static final int DEFAULT_PARALLELISM = 2;
    /** Höchste Parallelität im Hintergrund. */
    public static final int MAX_PARALLELISM = 16;
    /** Zuletzt geändert zuerst, ohne Änderungsdatum zuletzt; sonst stabil. */
    public static final Comparator<Gap> RANK = Comparator.comparing(Gap::modifiedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    /** Warum eine Seite geladen werden soll. */
    public enum Kind {
        /** Der Katalog kennt die Seite, ihr Inhalt fehlt im Cache (ADR 0014, {@code NOT_LOADED}). */
        NOT_LOADED,
        /** Die Kopfdaten nennen eine neuere Version als der gespeicherte Inhalt ({@code OUTDATED}). */
        OUTDATED
    }

    /**
     * Eine Lücke im Cache.
     *
     * @param modifiedAt Änderungsdatum laut Katalog, für die Rangfolge, oder {@code null}
     */
    public record Gap(PageKey key, Kind kind, Instant modifiedAt) {

        public Gap {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
        }

        public Gap(PageKey key, Kind kind) {
            this(key, kind, null);
        }
    }

    /** Der Katalog: welche Spaces er führt und welche ihrer Seiten fehlen oder veraltet sind. Liest nur. */
    public interface Catalog {

        /** Spaces mit bestätigter Generation, also aus einer Inventur des technischen Kontos. */
        List<SyncBaselineStore.SpaceRef> spaces();

        /** Höchstens {@code limit} Lücken des Space, zuletzt geänderte zuerst ({@link #RANK}). */
        List<Gap> gaps(String sourceId, String spaceKey, int limit);
    }

    /**
     * Die Warteschlange der Füllung; die Umsetzung im Lucene-Cache überdauert das Beenden
     * ({@code LuceneFillQueue}). Je Seite höchstens ein Eintrag; die Reihenfolge ist die des Einreihens.
     */
    public interface FillQueue {

        /** Die ersten {@code limit} Einträge. */
        List<Gap> pending(int limit);

        /** Reiht ein, was noch nicht wartet. */
        void add(List<Gap> gaps);

        /** Entfernt den Eintrag der Seite, wenn es einen gibt. */
        void remove(PageKey key);

        int size();

        /** Flüchtig, für Tests und Betrieb ohne Cache-Verzeichnis. */
        static FillQueue inMemory() {
            return new FillQueue() {
                private final Map<PageKey, Gap> gaps = new LinkedHashMap<>();

                @Override
                public synchronized List<Gap> pending(int limit) {
                    return gaps.values().stream().limit(Math.max(0, limit)).toList();
                }

                @Override
                public synchronized void add(List<Gap> added) {
                    added.forEach(gap -> gaps.putIfAbsent(gap.key(), gap));
                }

                @Override
                public synchronized void remove(PageKey key) {
                    gaps.remove(key);
                }

                @Override
                public synchronized int size() {
                    return gaps.size();
                }
            };
        }
    }

    /**
     * Zeitverhalten.
     *
     * @param initialDelay so lange nach dem Start wartet die Füllung, damit sie den Start nicht bremst
     * @param idle         frühestens so lange nach der letzten Suche im Katalog sucht sie erneut nach Lücken
     * @param maxQueued    höchstens so viele Einträge reiht eine Suche im Katalog ein
     */
    public record Timing(Duration initialDelay, Duration idle, int maxQueued) {

        /** 30 s nach dem Start, danach höchstens alle 10 min den Katalog absuchen, je Suche bis 10 000 Seiten. */
        public static final Timing DEFAULT = new Timing(Duration.ofSeconds(30), Duration.ofMinutes(10), 10_000);

        public Timing {
            if (initialDelay == null || initialDelay.isNegative() || idle == null || idle.isNegative()
                    || maxQueued < 1) {
                throw new IllegalArgumentException("Zeitverhalten der Hintergrundfüllung ungültig");
            }
        }
    }

    private final AcquisitionScheduler scheduler;
    private final FillQueue queue;
    private final Catalog catalog;
    private final AccessContext caller;
    private final Predicate<String> eligible;
    private final Timing timing;
    private final Clock clock;
    private final Object monitor = new Object();
    private final Map<PageKey, AcquisitionScheduler.Ticket> inFlight = new LinkedHashMap<>();
    private volatile boolean enabled;
    private volatile boolean closed;
    /** Ob die Anlaufzeit ({@link Timing#initialDelay()}) vorbei ist; vorher weckt {@link #offer} nicht. */
    private volatile boolean warmedUp;
    private boolean signalled;
    private boolean rescan;
    private long loaded;
    private long failed;
    /** Zählt beendete Abrufe; ein Blick in die Warteschlange gilt nur, solange keiner dazukam. */
    private long completions;
    /**
     * Gescheiterte Seiten und wann (nur im Speicher, höchstens {@link Timing#maxQueued()}): Eine Suche im Katalog reiht
     * sie erst nach {@link Timing#idle()} wieder ein, damit dauerhafte Fehlschläge die übrigen Lücken nicht blockieren.
     */
    private final LinkedHashMap<PageKey, Instant> failedAt = new LinkedHashMap<>();
    private final Map<String, long[]> bySource = new TreeMap<>();
    private Instant lastScan;
    private Thread worker;

    /**
     * @param caller   technischer Aufrufer, ohne Benutzeranmeldung
     * @param eligible ob eine Quelle im Hintergrund geladen werden darf (Quellen-ID): aktiv, für den technischen
     *                 Aufrufer freigegeben und ihre Füllung nicht abgeschaltet; zum Zeitpunkt des Ladens gefragt
     * @param enabled  ob die Füllung von Anfang an läuft
     * @throws IllegalArgumentException wenn {@code caller} eine Benutzeranmeldung trägt
     */
    public BackgroundFill(AcquisitionScheduler scheduler, FillQueue queue, Catalog catalog, AccessContext caller,
                          Predicate<String> eligible, Timing timing, boolean enabled) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.caller = Objects.requireNonNull(caller, "caller");
        if (caller.credential() != null) {
            throw new IllegalArgumentException("Die Hintergrundfüllung lädt nie mit der Anmeldung eines Benutzers");
        }
        this.eligible = Objects.requireNonNull(eligible, "eligible");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.clock = Clock.systemUTC();
        this.enabled = enabled;
    }

    /** Startet die Füllung auf einem eigenen virtuellen Thread; ein zweiter Aufruf tut nichts. */
    public BackgroundFill start() {
        synchronized (monitor) {
            if (worker == null && !closed) {
                worker = Thread.ofVirtual().name("background-fill").start(this::loop);
            }
        }
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Schaltet die Füllung ein oder aus. Aus zieht die übergebenen Anfragen zurück (laufende Abrufe enden regulär); die
     * Warteschlange bleibt und geht beim Einschalten weiter. An prüft die übergebenen Anfragen neu gegen
     * {@code eligible} und zieht die von Quellen zurück, deren Füllung inzwischen aus ist; nach einem Wechsel der
     * Konfiguration je Quelle genügt deshalb dieser Aufruf.
     */
    public void enabled(boolean value) {
        enabled = value;
        if (!value) {
            withdraw();
        } else {
            revalidate();
        }
        wake();
    }

    /** Zieht übergebene Anfragen von Quellen zurück, die nicht mehr geladen werden dürfen; laufende enden regulär. */
    public void revalidate() {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = new LinkedHashMap<>();
        synchronized (monitor) {
            inFlight.forEach((key, ticket) -> {
                if (!allowed(key.sourceId())) {
                    tickets.put(key, ticket);
                }
            });
        }
        cancel(tickets);
    }

    /**
     * Zieht Anfragen zurück und nimmt die zurückgezogenen gleich aus {@code inFlight}: Nach der Rückkehr zählt
     * {@link #status()} sie nicht mehr, auch wenn {@link #feed} den Abschluss-Rückruf noch nicht angehängt hat.
     */
    private void cancel(Map<PageKey, AcquisitionScheduler.Ticket> tickets) {
        tickets.forEach((key, ticket) -> {
            if (ticket.cancel()) {
                synchronized (monitor) {
                    inFlight.remove(key, ticket);
                }
            }
        });
    }

    /** Sucht bald wieder im Katalog, etwa nach einem Abgleich. */
    public void wake() {
        synchronized (monitor) {
            rescan = true;
            signalled = true;
            monitor.notifyAll();
        }
    }

    /**
     * Reiht Lücken ein, die eben katalogisierte Funde der Suche sind ({@link FindingCataloging}), auch in Spaces ohne
     * bestätigte Generation, die die Suche im Katalog nicht absucht. Nur zugelassene Quellen, nur solange die Füllung an
     * ist und die Warteschlange weniger als {@link Timing#maxQueued()} hält; was schon wartet, bleibt, wo es ist.
     */
    public void offer(List<Gap> gaps) {
        if (closed || !enabled || gaps == null || gaps.isEmpty()) {
            return;
        }
        List<Gap> wanted = gaps.stream().filter(Objects::nonNull).filter(gap -> allowed(gap.key().sourceId()))
                .toList();
        if (wanted.isEmpty()) {
            return;
        }
        try {
            if (queue.size() >= timing.maxQueued()) {
                return;
            }
            queue.add(wanted);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                    e.getClass().getName());
            return;
        }
        if (!warmedUp) {
            return;
        }
        synchronized (monitor) {
            signalled = true;
            monitor.notifyAll();
        }
    }

    /**
     * Stand von Füllung und Planer, nur Zähler; je Quelle nur zugelassene Quellen.
     */
    public AcquisitionStatus status() {
        Map<String, Integer> queuedBySource = new TreeMap<>();
        int size;
        try {
            size = queue.size();
            queue.pending(size).forEach(gap -> queuedBySource.merge(gap.key().sourceId(), 1, Integer::sum));
        } catch (RuntimeException e) {
            size = 0;
        }
        AcquisitionScheduler.Status planner = scheduler.status();
        int flying;
        long done;
        long gaps;
        Instant scanned;
        Map<String, long[]> counts = new TreeMap<>();
        synchronized (monitor) {
            flying = inFlight.size();
            done = loaded;
            gaps = failed;
            scanned = lastScan;
            bySource.forEach((id, c) -> counts.put(id, c.clone()));
        }
        List<AcquisitionStatus.SourceCounts> sources = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>(queuedBySource.keySet());
        ids.addAll(counts.keySet());
        for (String sourceId : ids) {
            boolean allowed;
            try {
                allowed = eligible.test(sourceId);
            } catch (RuntimeException e) {
                allowed = false;
            }
            if (allowed) {
                long[] c = counts.getOrDefault(sourceId, new long[2]);
                sources.add(new AcquisitionStatus.SourceCounts(sourceId, queuedBySource.getOrDefault(sourceId, 0),
                        c[0], c[1]));
            }
        }
        AcquisitionStatus.Pause pause;
        if (closed) {
            pause = AcquisitionStatus.Pause.CLOSED;
        } else if (!enabled) {
            pause = AcquisitionStatus.Pause.DISABLED;
        } else if (planner.pause() != AcquisitionStatus.Pause.NONE) {
            pause = planner.pause();
        } else if (size == 0 && flying == 0) {
            pause = AcquisitionStatus.Pause.IDLE;
        } else {
            pause = AcquisitionStatus.Pause.NONE;
        }
        return new AcquisitionStatus(enabled, pause, size, flying, planner.foregroundQueued(),
                planner.foregroundRunning(), planner.backgroundRunning(), done, gaps, planner.promoted(),
                scheduler.limits().background(), scanned, sources);
    }

    /** Der Stand nur des Planers, ohne Füllung (abgeschaltet). */
    public static AcquisitionStatus withoutFill(AcquisitionScheduler scheduler) {
        AcquisitionScheduler.Status planner = scheduler.status();
        return new AcquisitionStatus(false, AcquisitionStatus.Pause.DISABLED, 0, 0, planner.foregroundQueued(),
                planner.foregroundRunning(), planner.backgroundRunning(), 0, 0, planner.promoted(),
                scheduler.limits().background(), null, List.of());
    }

    private void loop() {
        await(timing.initialDelay());
        warmedUp = true;
        while (!closed) {
            try {
                if (!enabled) {
                    await(timing.idle());
                    continue;
                }
                boolean busy = feed();
                if (busy) {
                    await(POLL);
                    continue;
                }
                if (scanDue()) {
                    if (scan() > 0) {
                        continue;
                    }
                }
                await(nextScan());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Hintergrundfüllung unterbrochen ({0}); neuer Versuch später",
                        e.getClass().getName());
                await(timing.idle());
            }
        }
    }

    /** Wartet höchstens {@code duration}, bis {@link #wake()}, ein beendeter Abruf oder {@link #close()} weckt. */
    private void await(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        synchronized (monitor) {
            while (!signalled && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    monitor.wait(Math.max(1, remaining / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            signalled = false;
        }
    }

    private boolean scanDue() {
        synchronized (monitor) {
            return rescan || lastScan == null || !clock.instant().isBefore(lastScan.plus(timing.idle()));
        }
    }

    private Duration nextScan() {
        synchronized (monitor) {
            if (lastScan == null) {
                return timing.idle();
            }
            Duration left = Duration.between(clock.instant(), lastScan.plus(timing.idle()));
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    /**
     * Übergibt Einträge der Warteschlange an den Planer, bis zu doppelt so viele, wie er im Hintergrund zugleich lädt.
     *
     * @return ob etwas übergeben ist, lädt oder gleich wieder nachzusehen ist
     */
    private boolean feed() {
        int window = 2 * Math.max(1, scheduler.limits().background());
        List<Gap> next;
        long seen;
        synchronized (monitor) {
            if (inFlight.size() >= window) {
                return true;
            }
            next = queue.pending(window + inFlight.size());
            seen = completions;
        }
        boolean again = false;
        for (Gap gap : next) {
            if (closed || !enabled) {
                break;
            }
            synchronized (monitor) {
                if (inFlight.size() >= window || completions != seen) {
                    // Endete inzwischen ein Abruf, ist der Blick in die Warteschlange veraltet: Sonst käme eine eben
                    // geladene Seite ein zweites Mal dran. Der nächste Durchgang sieht neu nach.
                    again = true;
                    break;
                }
                if (inFlight.containsKey(gap.key())) {
                    continue;
                }
            }
            String sourceId = gap.key().sourceId();
            if (!allowed(sourceId)) {
                // Die Quelle will das (nicht mehr); ein neuer Blick in den Katalog reiht es wieder ein, wenn doch.
                queue.remove(gap.key());
                continue;
            }
            AcquisitionScheduler.Ticket ticket;
            try {
                ticket = scheduler.request(caller, gap.key(), AcquisitionScheduler.Priority.BACKGROUND);
            } catch (RejectedExecutionException e) {
                break;
            }
            synchronized (monitor) {
                inFlight.put(gap.key(), ticket);
            }
            // Was gleich endet, kommt hier schon zurück; danach gleich weiter in der Schlange.
            again = true;
            ticket.done().whenComplete((stored, failure) -> completed(gap.key(), ticket, stored, failure));
            if (!allowed(sourceId)) {
                // Lief revalidate() zwischen der Prüfung oben und inFlight.put, sah es diese Anfrage nicht.
                cancel(Map.of(gap.key(), ticket));
            }
        }
        synchronized (monitor) {
            return again || !inFlight.isEmpty();
        }
    }

    private boolean allowed(String sourceId) {
        try {
            return eligible.test(sourceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void completed(PageKey key, AcquisitionScheduler.Ticket ticket, Boolean stored, Throwable failure) {
        boolean withdrawn = failure instanceof CancellationException
                || failure instanceof CompletionException && failure.getCause() instanceof CancellationException;
        if (!withdrawn && !closed) {
            // Geladen oder gescheitert: der Eintrag ist erledigt; eine bleibende Lücke findet die nächste Suche im
            // Katalog wieder. Zurückgezogenes bleibt für den nächsten Start. Erst die Schlange, dann inFlight, damit
            // feed() die Seite nie wartend und zugleich nicht übergeben sieht.
            try {
                queue.remove(key);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                        e.getClass().getName());
            }
        }
        synchronized (monitor) {
            inFlight.remove(key, ticket);
            completions++;
            if (!withdrawn) {
                long[] counts = bySource.computeIfAbsent(key.sourceId(), id -> new long[2]);
                if (Boolean.TRUE.equals(stored)) {
                    loaded++;
                    counts[0]++;
                } else {
                    failed++;
                    counts[1]++;
                    failedAt.remove(key);
                    failedAt.put(key, clock.instant());
                    while (failedAt.size() > timing.maxQueued()) {
                        failedAt.remove(failedAt.keySet().iterator().next());
                    }
                }
            }
            signalled = true;
            monitor.notifyAll();
        }
    }

    /**
     * Sucht im Katalog nach Lücken der zugelassenen Quellen und reiht sie in Rangfolge ({@link #RANK}) über alle Spaces
     * ein, höchstens {@link Timing#maxQueued()}.
     *
     * @return wie viele eingereiht wurden
     */
    private int scan() {
        synchronized (monitor) {
            rescan = false;
        }
        List<Gap> found = new ArrayList<>();
        int skipped;
        synchronized (monitor) {
            skipped = failedAt.size();
        }
        // Je Space so viele mehr, wie gescheiterte Seiten übersprungen werden, damit sie die Tranche nicht füllen, und
        // eine darüber, damit „es gibt mehr“ auch bei nur einem Space auffällt.
        int limit = (int) Math.min(Integer.MAX_VALUE, (long) timing.maxQueued() + skipped + 1);
        for (SyncBaselineStore.SpaceRef space : catalog.spaces()) {
            if (closed || !enabled) {
                break;
            }
            String sourceId = space.sourceId();
            if (!allowed(sourceId)) {
                continue;
            }
            for (Gap gap : catalog.gaps(sourceId, space.spaceKey(), limit)) {
                if (gap.key().sourceId().equals(sourceId)) {
                    found.add(gap);
                }
            }
        }
        synchronized (monitor) {
            // Kürzlich Gescheitertes erst nach der Wartezeit wieder, sonst käme bei übervoller Schlange immer dieselbe
            // Tranche dran.
            Instant retry = clock.instant().minus(timing.idle());
            failedAt.values().removeIf(at -> !at.isAfter(retry));
            found.removeIf(gap -> failedAt.containsKey(gap.key()));
        }
        found.sort(RANK);
        boolean more = found.size() > timing.maxQueued();
        List<Gap> gaps = more ? found.subList(0, timing.maxQueued()) : found;
        int before = queue.size();
        if (!gaps.isEmpty()) {
            queue.add(gaps);
        }
        int added = queue.size() - before;
        Map<String, Integer> perSource = new LinkedHashMap<>();
        gaps.forEach(gap -> perSource.merge(gap.key().sourceId(), 1, Integer::sum));
        synchronized (monitor) {
            lastScan = clock.instant();
            rescan = more && added > 0;
        }
        perSource.forEach((sourceId, count) -> LOG.log(System.Logger.Level.INFO,
                "Hintergrundfüllung: {0} Lücken in Quelle {1}", count, sourceId));
        return added;
    }

    /** Zieht die übergebenen Anfragen zurück; die Einträge bleiben in der Warteschlange. */
    private void withdraw() {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets;
        synchronized (monitor) {
            tickets = new LinkedHashMap<>(inFlight);
        }
        cancel(tickets);
    }

    /**
     * Beendet die Füllung: übergebene Anfragen werden zurückgezogen, ein laufender Abruf endet regulär im Planer. Die
     * Warteschlange bleibt für den nächsten Start; schließt sie nicht.
     */
    @Override
    public void close() throws InterruptedException {
        Thread running;
        synchronized (monitor) {
            closed = true;
            signalled = true;
            monitor.notifyAll();
            running = worker;
        }
        withdraw();
        if (running != null) {
            // Kein Unterbrechen: die Füllung liest höchstens gerade den Katalog.
            running.join();
        }
        withdraw();
    }
}
