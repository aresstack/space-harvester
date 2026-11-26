package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.network.NetworkState;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
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
 * Katalog nach Lücken ab, wenn er sich geändert hat ({@link #wake()}, etwa nach jedem Commit des Seiten-Cache) und wenn
 * die Wartezeit einer gescheiterten Seite um ist.
 *
 * <p><b>Ohne Abfragen</b> (ADR 0027): Die Füllung wartet auf Ereignisse (beendeter Abruf, neue Lücken, geänderter
 * Katalog, Schalter, freier Platz im Planer), nie in festen Abständen; nur der Start, die Wiederholung gescheiterter
 * Seiten und die Pause nach einem unerwarteten Fehler haben eine Frist.
 *
 * <p><b>Meldungen</b> (ADR 0027): {@link #subscribe} liefert den jüngsten {@link #status()} nach jeder Änderung von
 * Füllung oder Planer, gebündelt; die App fragt nicht in Abständen.
 *
 * <p>Protokolliert nur Zähler und Quellen, nie Seiten-IDs.
 */
public final class BackgroundFill implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BackgroundFill.class.getName());
    /** So oft schaut die Füllung nach, solange etwas lädt. */
    private static final String STATUS = "status";
    /**
     * Entprellen (ADR 0027, Abschnitt 1): nach einer Änderung des Katalogs sucht die Füllung erst, wenn er so lange
     * ruht, damit viele Commits hintereinander, etwa beim Laden der Suchtreffer, eine Suche auslösen und nicht viele.
     */
    static final Duration SETTLE = Duration.ofSeconds(1);

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

    /** Quelle und Space einer Lücke; Space {@code null}, wenn unbekannt (Fundstelle 12: ein Teil je Quelle und Space). */
    public record Where(String sourceId, String spaceKey) {

        public Where {
            Objects.requireNonNull(sourceId, "sourceId");
        }
    }

    /**
     * Sieht der Füllung je Quelle und Space zu (Fundstelle 12), etwa um sie als Hintergrundauftrag zu zeigen. Die Aufrufe
     * kommen nie unter der Sperre der Füllung und dürfen nicht blockieren.
     */
    public interface Observer {

        /** Eine Seite des Space ging an den Planer. */
        default void handed(Where where) {
        }

        /** Der Abruf einer Seite endete, geladen oder gescheitert (zurückgezogene zählen nicht). */
        default void completed(Where where, boolean stored, Throwable failure) {
        }

        /** Im Space wartet und lädt nichts mehr, oder die Füllung ist aus. */
        default void idle(Where where) {
        }

        /**
         * Ein Abruf des Space scheiterte am Netz ({@link NetworkState#networkFailure}): Die Seite bleibt in der Schlange,
         * zählt nicht als gescheitert, und die Füllung wartet, bis das Netz zurück ist (Angelo 22.11.2026).
         */
        default void offline(Where where, Throwable cause) {
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

        /** Entfernt die Einträge dieser Seiten; die dauerhafte Ablage in einem Commit. */
        default void removeAll(java.util.Collection<PageKey> keys) {
            keys.forEach(this::remove);
        }

        /** Entfernt alle Einträge einer Quelle („Daten bereinigen“); die dauerhafte Ablage in einem Commit. */
        default void removeSource(String sourceId) {
            removeAll(pending(size()).stream().map(Gap::key).filter(key -> key.sourceId().equals(sourceId))
                    .toList());
        }

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
     * @param idle         so lange nach ihrem Fehlschlag kommt eine Seite frühestens wieder dran; ebenso lange pausiert
     *                     die Füllung nach einem unerwarteten Fehler
     * @param maxQueued    höchstens so viele Einträge reiht eine Suche im Katalog ein
     */
    public record Timing(Duration initialDelay, Duration idle, int maxQueued) {

        /** 30 s nach dem Start, gescheiterte Seiten nach 10 min wieder, je Suche bis 10 000 Seiten. */
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
    /**
     * Ordnet das Schreiben der Warteschlange und ihres Spiegels {@link #whereOf} (Abnahme #1087): Einreihen (spiegeln,
     * dann hinzufügen) und Austragen (entfernen, dann Spiegel löschen) laufen nie verschränkt, sonst bliebe ein Eintrag
     * ohne Spiegel zurück, den {@link #feed} nie anfordert. Darf über Ein-/Ausgabe der Schlange gehalten werden, wird
     * aber nie unter {@link #monitor} genommen (Reihenfolge: erst diese Sperre, dann {@code monitor}).
     */
    private final Object queueOrder = new Object();
    private final Map<PageKey, AcquisitionScheduler.Ticket> inFlight = new LinkedHashMap<>();
    private volatile boolean enabled;
    private volatile boolean closed;
    /**
     * Ob die Anlaufzeit ({@link Timing#initialDelay()}) vorbei ist; vorher wecken weder {@link #offer} noch
     * {@link #wake}.
     */
    private volatile boolean warmedUp;
    private boolean signalled;
    private boolean rescan;
    /** Der Planer nahm zuletzt nichts mehr an; seine nächste Änderung weckt die Füllung. Unter {@link #monitor}. */
    private boolean blocked;
    /** Zeitpunkt ({@link System#nanoTime()}) des letzten {@link #wake()}; unter {@link #monitor}. */
    private long lastWake = System.nanoTime() - SETTLE.toNanos();
    private long loaded;
    private long failed;
    /** Zählt beendete Abrufe; ein Blick in die Warteschlange gilt nur, solange keiner dazukam. */
    private long completions;
    /**
     * Gescheiterte Seiten und wann (nur im Speicher, höchstens {@link Timing#maxQueued()}): Eine Suche im Katalog reiht
     * sie erst nach {@link Timing#idle()} wieder ein, damit dauerhafte Fehlschläge die übrigen Lücken nicht blockieren.
     */
    private final LinkedHashMap<PageKey, Instant> failedAt = new LinkedHashMap<>();
    private final Listeners<Consumer<AcquisitionStatus>> listeners = new Listeners<>("Hintergrundfüllung");
    private final Coalescer<String, Object> notifier = new Coalescer<>("background-fill", (key, ignored) -> deliver());
    /** Anmeldung beim Planer, ab dem ersten Empfänger; unter {@link #monitor}. */
    private Subscription planner;
    private final Map<String, long[]> bySource = new TreeMap<>();
    /** Der Netzzustand, oder {@code null}: dann zählt ein Netzfehler wie jeder andere. */
    private volatile NetworkState network;
    /** Anmeldung beim Netzzustand; unter {@link #monitor}. */
    private Subscription networkWatch;
    private Instant lastScan;
    private Thread worker;
    /** Space je Seite, soweit bekannt (Kopfdaten im Cache); ohne Zuschauer ungenutzt. */
    private volatile java.util.function.Function<PageKey, java.util.Optional<String>> spaceOf = key ->
            java.util.Optional.empty();
    private volatile Observer observer = new Observer() {
    };
    /** Ob ein Space ruht (angehalten oder abgebrochen bis zum nächsten Abgleich, Fundstelle 12). */
    private volatile Predicate<Where> resting = where -> false;
    /** Wo jede wartende Seite liegt, gespiegelt zur Warteschlange; unter {@link #monitor}. */
    private final Map<PageKey, Where> whereOf = new LinkedHashMap<>();
    /** Wartende Seiten je Quelle und Space (übergebene zählen mit, bis ihr Abruf endet); unter {@link #monitor}. */
    private final Map<Where, Integer> waitingIn = new LinkedHashMap<>();
    /** Abrufe je Space, deren Abschluss gerade gemeldet wird; „leer“ erst danach (Codex #1087). Unter monitor. */
    private final Map<Where, Integer> reporting = new HashMap<>();
    /** Spaces, deren Schlange leer ist, deren „leer“ aber noch auf laufende Meldungen wartet; unter monitor. */
    private final Set<Where> idlePending = new HashSet<>();
    /** Ob {@link #whereOf} die Warteschlange schon spiegelt; unter {@link #monitor}. */
    private boolean mirrored;
    /** Spaces, die ruhen sollen; der Arbeiter räumt sie ab ({@link #rest}). Unter {@link #monitor}. */
    private final Set<Where> restPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Spaces, deren fortgesetzter Teil auf eine Suche im Katalog wartet ({@link #scanFor}). Unter monitor. */
    private final Set<Where> scanPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Quellen, die gerade bereinigt werden: {@link #feed} fordert für sie nichts an. Unter {@link #monitor}. */
    private final Set<String> purging = new HashSet<>();

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

    /**
     * Startet die Füllung auf einem eigenen Plattform-Thread (Daemon); ein zweiter Aufruf tut nichts. Kein virtueller:
     * Der Arbeiter wartet in {@code monitor.wait()} unter {@code synchronized} und hielte damit unter Java 21 seinen
     * Träger fest, ebenso jeder Weck-Thread (CI #1101).
     */
    public BackgroundFill start() {
        ensureMirrored();
        synchronized (monitor) {
            if (worker == null && !closed) {
                observePlanner();
                worker = Thread.ofPlatform().daemon().name("background-fill").start(this::loop);
            }
        }
        return this;
    }

    /** Meldet die Füllung beim Planer an, einmal; unter {@link #monitor}. */
    private void observePlanner() {
        if (planner == null && !closed) {
            planner = scheduler.subscribe(status -> plannerChanged());
        }
    }

    /** Der Planer hat sich geändert: Stand melden und, wenn er zuletzt nichts annahm, die Füllung wecken. */
    private void plannerChanged() {
        changed();
        synchronized (monitor) {
            if (blocked) {
                blocked = false;
                signalled = true;
                monitor.notifyAll();
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Meldet die Füllung je Quelle und Space an {@code observer} (Fundstelle 12) und lässt Spaces ruhen, für die
     * {@code resting} gilt: Deren Lücken reiht sie nicht ein und übergibt sie nicht. Vor {@link #start()} aufrufen.
     *
     * @param spaceOf der Space einer Seite, soweit bekannt; sonst zählt sie zum Space {@code null}
     */
    /**
     * Hängt den Netzzustand an (Angelo 22.11.2026: „nicht alle testen, wenn keine Verbindung besteht“): Ein Netzfehler
     * lässt die Seite in der Schlange, ohne Wartezeit und ohne Fehlschlag; solange das Netz weg ist, übergibt die
     * Füllung nur die eine Seite, mit der {@link NetworkState} prüft, und jede Änderung des Netzzustands weckt sie.
     */
    public void network(NetworkState network) {
        Subscription previous;
        synchronized (monitor) {
            previous = networkWatch;
            this.network = network;
            networkWatch = network == null ? null : network.subscribe(status -> {
                synchronized (monitor) {
                    signalled = true;
                    monitor.notifyAll();
                }
            });
        }
        if (previous != null) {
            previous.close();
        }
    }

    public void observe(java.util.function.Function<PageKey, java.util.Optional<String>> spaceOf, Observer observer,
                        Predicate<Where> resting) {
        this.spaceOf = Objects.requireNonNull(spaceOf, "spaceOf");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.resting = Objects.requireNonNull(resting, "resting");
    }

    /** Wie viele Seiten im Space warten oder laden. */
    public int waiting(Where where) {
        ensureMirrored();
        synchronized (monitor) {
            return waitingIn.getOrDefault(where, 0);
        }
    }

    /**
     * Lässt einen Space ruhen (Fundstelle 12): Seine übergebenen Anfragen werden zurückgezogen, seine Einträge verlassen
     * die Warteschlange in einem Commit; eine Suche im Katalog reiht sie wieder ein, sobald er nicht mehr ruht
     * ({@link #wake()}). Merkt nur vor und kehrt sofort zurück, der Arbeiter der Füllung räumt ab (Abnahme #1087): Der
     * Aufrufer hält etwa die Sperre der Aufträge. Laufende Abrufe enden regulär und bleiben bis dahin eingetragen, damit
     * ihr Ende gezählt wird.
     */
    public void rest(Where where) {
        Objects.requireNonNull(where, "where");
        if (closed) {
            return;
        }
        restPending.add(where);
        // Gleich zurückziehen, was beim Planer noch wartet (nur im Speicher): Nach der Rückkehr beginnt im Space nichts
        // mehr. Die Sperre der Füllung gilt nie über Zugriffe auf Warteschlange oder Cache, nur über ihre Karten.
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = new LinkedHashMap<>();
        synchronized (monitor) {
            whereOf.forEach((key, at) -> {
                AcquisitionScheduler.Ticket ticket = inFlight.get(key);
                if (ticket != null && at.equals(where)) {
                    tickets.put(key, ticket);
                }
            });
        }
        withdrawWaiting(tickets, new ArrayList<>());
        signal();
    }

    /** Weckt den Arbeiter. */
    private void signal() {
        synchronized (monitor) {
            signalled = true;
            monitor.notifyAll();
        }
    }

    /**
     * Zieht Anfragen zurück, deren Abruf noch nicht läuft; laufende bleiben angefragt und eingetragen, ihr Ende wird
     * gezählt (rot mit Grund, wenn es scheitert; Abnahme #1087), und fallen aus {@code keys}.
     */
    private void withdrawWaiting(Map<PageKey, AcquisitionScheduler.Ticket> tickets, List<PageKey> keys) {
        tickets.forEach((key, ticket) -> {
            if (ticket.cancelIfWaiting()) {
                synchronized (monitor) {
                    inFlight.remove(key, ticket);
                }
            } else if (!ticket.done().isCancelled()) {
                // Läuft oder ist fertig, aber vielleicht noch nicht gemeldet: Austragen übernimmt seine Meldung.
                keys.remove(key);
            }
        });
    }

    /**
     * Sucht für einen Space sofort im Katalog, etwa weil sein Teil fortgesetzt wurde (Abnahme #1087): Findet die Suche
     * dort nichts (mehr), meldet sie {@link Observer#idle}; sonst laden die Seiten in den laufenden Teil. Merkt nur vor,
     * der Arbeiter sucht.
     */
    public void scanFor(Where where) {
        Objects.requireNonNull(where, "where");
        if (closed) {
            return;
        }
        scanPending.add(where);
        // Ohne die Sperre des Aufrufers (etwa der Aufträge) zu halten: ein kurzer eigener Thread, kein virtueller, der
        // auf einen Träger warten müsste (CI #1101).
        Thread.ofPlatform().daemon().name("background-fill-scan").start(this::signal);
    }

    /**
     * Entfernt alles Wartende einer Quelle („Daten bereinigen“, Abnahme #1087) samt Spiegel, in einem Commit: Übergebene
     * Anfragen werden zurückgezogen, laufende enden ohne Meldung, und jeder betroffene Space meldet „leer“, damit sein
     * Teil endet.
     */
    public void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        ensureMirrored();
        synchronized (monitor) {
            // Erst sperren und austragen, dann zurückziehen (Abnahme #1087): Sonst forderte feed die Seiten neu an.
            purging.add(sourceId);
        }
        try {
            purgeSource(sourceId);
        } finally {
            synchronized (monitor) {
                purging.remove(sourceId);
            }
        }
        changed();
    }

    private void purgeSource(String sourceId) {
        Set<Where> emptied = new java.util.LinkedHashSet<>();
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = new LinkedHashMap<>();
        synchronized (queueOrder) {
            purgeQueued(sourceId, emptied, tickets);
        }
        cancel(tickets);
        emptied.forEach(this::settle);
    }

    private void purgeQueued(String sourceId, Set<Where> emptied, Map<PageKey, AcquisitionScheduler.Ticket> tickets) {
        queue.removeSource(sourceId);
        synchronized (monitor) {
            inFlight.forEach((key, ticket) -> {
                if (key.sourceId().equals(sourceId)) {
                    tickets.put(key, ticket);
                }
            });
            whereOf.entrySet().removeIf(entry -> {
                if (!entry.getKey().sourceId().equals(sourceId)) {
                    return false;
                }
                Where at = entry.getValue();
                if (waitingIn.merge(at, -1, Integer::sum) <= 0) {
                    waitingIn.remove(at);
                    emptied.add(at);
                }
                return true;
            });
            failedAt.keySet().removeIf(key -> key.sourceId().equals(sourceId));
        }
    }

    /** Arbeiter: räumt vorgemerkte ruhende Spaces ab und sucht für vorgemerkte fortgesetzte. */
    private void housekeeping() {
        List<Where> resting = new ArrayList<>();
        for (Where where : List.copyOf(restPending)) {
            if (restPending.remove(where)) {
                resting.add(where);
            }
        }
        List<Where> scans = List.copyOf(scanPending);
        resting.forEach(this::restNow);
        scans.forEach(this::scanNow);
    }

    private void restNow(Where where) {
        if (!resting(where)) {
            // Inzwischen fortgesetzt: nichts entfernen (Abnahme #1087).
            return;
        }
        Map<PageKey, AcquisitionScheduler.Ticket> tickets = new LinkedHashMap<>();
        List<PageKey> keys = new ArrayList<>();
        ensureMirrored();
        synchronized (monitor) {
            whereOf.forEach((key, at) -> {
                if (at.equals(where)) {
                    keys.add(key);
                    AcquisitionScheduler.Ticket ticket = inFlight.get(key);
                    if (ticket != null) {
                        tickets.put(key, ticket);
                    }
                }
            });
        }
        withdrawWaiting(tickets, keys);
        dequeueAll(keys);
        changed();
    }

    /**
     * Sucht für einen Space im Katalog; reiht sie nichts ein, meldet sie „leer“, sobald dort nichts mehr lädt. Bis sie
     * fertig ist, meldet der Space kein „leer“ ({@link #settle}), sonst endete der fortgesetzte Teil vor ihren Seiten.
     */
    private void scanNow(Where where) {
        int added = 0;
        try {
            added = scanSpace(where);
        } catch (RuntimeException e) {
            // Jede Suche einzeln abgefangen: Der Arbeiter steht nie still, der Teil endet.
            LOG.log(System.Logger.Level.INFO, "Suche der Hintergrundfüllung im Space gescheitert ({0})",
                    e.getClass().getName());
        } finally {
            synchronized (monitor) {
                scanPending.remove(where);
            }
            if (added == 0) {
                settle(where);
            }
            changed();
        }
    }

    private int scanSpace(Where where) {
        int added = 0;
        // Ohne Space (Teile ohne Space-Zuordnung) gibt es keine Suche je Space: sofort „leer“.
        if (where.spaceKey() != null && enabled && !closed && allowed(where.sourceId()) && !resting(where)) {
            List<Gap> found = new ArrayList<>();
            Map<PageKey, Where> at = new LinkedHashMap<>();
            for (Gap gap : catalog.gaps(where.sourceId(), where.spaceKey(), timing.maxQueued())) {
                if (gap.key().sourceId().equals(where.sourceId())) {
                    found.add(gap);
                    at.put(gap.key(), where);
                }
            }
            synchronized (monitor) {
                found.removeIf(gap -> failedAt.containsKey(gap.key()) || whereOf.containsKey(gap.key()));
            }
            if (!found.isEmpty()) {
                int before = queue.size();
                enqueue(found, at);
                added = queue.size() - before;
            }
        }
        return added;
    }

    private Where where(PageKey key) {
        String spaceKey;
        try {
            spaceKey = spaceOf.apply(key).orElse(null);
        } catch (RuntimeException e) {
            spaceKey = null;
        }
        return new Where(key.sourceId(), spaceKey);
    }

    private boolean resting(Where where) {
        try {
            return resting.test(where);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Spiegelt die Warteschlange einmal, etwa nach dem Neustart; liest sie außerhalb von {@link #monitor}, denn deren
     * Sperre gilt auch über ihre Commits (Abnahme #1087). {@link #start()} spiegelt, bevor der Arbeiter läuft.
     */
    private void ensureMirrored() {
        synchronized (monitor) {
            if (mirrored) {
                return;
            }
        }
        synchronized (queueOrder) {
            mirrorQueue();
        }
    }

    private void mirrorQueue() {
        synchronized (monitor) {
            if (mirrored) {
                return;
            }
        }
        List<Gap> pending;
        try {
            pending = queue.pending(queue.size());
        } catch (RuntimeException e) {
            pending = List.of();
        }
        // Der Space je Seite kommt aus dem Cache: außerhalb der Sperre nachgesehen.
        Map<PageKey, Where> located = new LinkedHashMap<>();
        pending.forEach(gap -> located.put(gap.key(), where(gap.key())));
        synchronized (monitor) {
            if (mirrored) {
                return;
            }
            mirrored = true;
            for (Gap gap : pending) {
                if (!whereOf.containsKey(gap.key())) {
                    Where at = located.get(gap.key());
                    whereOf.put(gap.key(), at);
                    waitingIn.merge(at, 1, Integer::sum);
                }
            }
        }
    }

    /**
     * Reiht ein und spiegelt die neuen Einträge; {@code at} nennt den Space, wo bekannt. Erst gespiegelt, dann
     * eingereiht (Abnahme #1087): Sonst könnte {@link #feed} einen Eintrag übergeben und abschließen, bevor er gespiegelt
     * ist, und der Spiegel zählte ihn danach ewig als wartend. Scheitert das Einreihen, wird zurückgenommen.
     */
    private void enqueue(List<Gap> gaps, Map<PageKey, Where> at) {
        Map<PageKey, Where> mirroredNow = new LinkedHashMap<>();
        ensureMirrored();
        Map<PageKey, Where> located = new LinkedHashMap<>(at);
        gaps.forEach(gap -> located.computeIfAbsent(gap.key(), this::where));
        synchronized (queueOrder) {
            List<Gap> added = new ArrayList<>();
            synchronized (monitor) {
                for (Gap gap : gaps) {
                    // Schon gespiegelt heißt: wartet schon (oder endet gerade); dann nicht erneut hinzufügen, sonst
                    // hinge der Eintrag nach seinem Abschluss ohne Spiegel in der Schlange. Eine Quelle im Bereinigen
                    // nimmt nichts an.
                    if (!whereOf.containsKey(gap.key()) && !purging.contains(gap.key().sourceId())) {
                        Where where = located.get(gap.key());
                        whereOf.put(gap.key(), where);
                        waitingIn.merge(where, 1, Integer::sum);
                        mirroredNow.put(gap.key(), where);
                        added.add(gap);
                    }
                }
            }
            if (added.isEmpty()) {
                return;
            }
            try {
                queue.add(added);
                return;
            } catch (RuntimeException e) {
                Set<Where> emptied = new java.util.LinkedHashSet<>();
                synchronized (monitor) {
                    mirroredNow.forEach((key, where) -> {
                        if (whereOf.remove(key, where) && waitingIn.merge(where, -1, Integer::sum) <= 0) {
                            waitingIn.remove(where);
                            emptied.add(where);
                        }
                    });
                }
                emptied.forEach(this::settle);
                throw e;
            }
        }
    }

    /**
     * Trägt Einträge aus, die ohne Spiegel in der Warteschlange stehen (Abnahme #1087), in einem Commit. Unter
     * {@link #queueOrder} nachgeprüft: Was inzwischen gespiegelt ist oder zu einer Quelle im Bereinigen gehört, bleibt.
     *
     * @return ob etwas ausgetragen wurde
     */
    private boolean dropOrphans(List<PageKey> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        synchronized (queueOrder) {
            List<PageKey> orphans;
            synchronized (monitor) {
                orphans = candidates.stream().filter(key -> !whereOf.containsKey(key)
                        && !inFlight.containsKey(key) && !purging.contains(key.sourceId())).toList();
            }
            if (orphans.isEmpty()) {
                return false;
            }
            try {
                queue.removeAll(orphans);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                        e.getClass().getName());
                return false;
            }
            return true;
        }
    }

    /** Nimmt die Einträge aus der Warteschlange, in einem Commit; meldet „leer“ für Spaces ohne Eintrag. */
    private void dequeueAll(List<PageKey> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Set<Where> emptied = new java.util.LinkedHashSet<>();
        synchronized (queueOrder) {
            try {
                queue.removeAll(keys);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                        e.getClass().getName());
                return;
            }
            synchronized (monitor) {
                for (PageKey key : keys) {
                    Where at = whereOf.remove(key);
                    if (at != null && waitingIn.merge(at, -1, Integer::sum) <= 0) {
                        waitingIn.remove(at);
                        emptied.add(at);
                    }
                }
            }
        }
        emptied.forEach(this::settle);
    }

    /** Nimmt einen Eintrag aus der Warteschlange; war es der letzte seines Space, meldet das {@link Observer#idle}. */
    private void dequeue(PageKey key) {
        settle(dequeueQuietly(key));
    }

    /**
     * Meldet „leer“ für einen Space, dessen Schlange eben leer wurde, aber erst, wenn kein gleichzeitiger Abschluss
     * dieses Space mehr gemeldet wird: Der Teil endet nie, bevor alle seine Seiten gezählt sind.
     */
    private void settle(Where where) {
        if (where == null) {
            return;
        }
        boolean idle;
        synchronized (monitor) {
            idlePending.add(where);
            idle = reporting.getOrDefault(where, 0) == 0 && !waitingIn.containsKey(where)
                    && !scanPending.contains(where) && idlePending.remove(where);
        }
        if (idle) {
            notifyIdle(where);
        }
    }

    /** Wie {@link #dequeue}, meldet aber nichts; nennt den Space, wenn es sein letzter Eintrag war. */
    private Where dequeueQuietly(PageKey key) {
        synchronized (queueOrder) {
            try {
                queue.remove(key);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                        e.getClass().getName());
                return null;
            }
            synchronized (monitor) {
                Where at = whereOf.remove(key);
                if (at != null && waitingIn.merge(at, -1, Integer::sum) <= 0) {
                    waitingIn.remove(at);
                    return at;
                }
            }
            return null;
        }
    }

    private void notifyIdle(Where where) {
        try {
            observer.idle(where);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Zuschauer der Hintergrundfüllung scheiterte ({0})",
                    e.getClass().getName());
        }
    }

    /**
     * Meldet einen Empfänger für den Stand an (ADR 0027): nach jeder Änderung der Warteschlange, der übergebenen und
     * beendeten Abrufe, der Zähler, des Schalters und des Planers der jüngste {@link #status()}, höchstens alle
     * {@link Coalescer#INTERVAL}, auf dem Zustell-Thread des Core. Erst anmelden, dann {@link #status()} lesen.
     */
    public Subscription subscribe(Consumer<AcquisitionStatus> listener) {
        Subscription subscription = listeners.add(Objects.requireNonNull(listener, "listener"));
        synchronized (monitor) {
            observePlanner();
        }
        return subscription;
    }

    /** Merkt den Stand zur Meldung vor; ohne Empfänger nichts. */
    private void changed() {
        if (!listeners.isEmpty()) {
            notifier.mark(STATUS);
        }
    }

    private void deliver() {
        AcquisitionStatus status = status();
        listeners.deliver(null, listener -> listener.accept(status));
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
            List<Where> spaces;
            synchronized (monitor) {
                spaces = new ArrayList<>(waitingIn.keySet());
            }
            // Aus: Die Teile enden, die Warteschlange bleibt für das Einschalten.
            spaces.forEach(this::notifyIdle);
        } else {
            revalidate();
        }
        wake();
        changed();
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
        if (!tickets.isEmpty()) {
            changed();
        }
    }

    /**
     * Sucht bald wieder im Katalog, etwa nach einem Abgleich oder jedem anderen Commit des Seiten-Cache; während der
     * Anlaufzeit erst danach.
     */
    public void wake() {
        synchronized (monitor) {
            rescan = true;
            lastWake = System.nanoTime();
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
        Map<PageKey, Where> at = new LinkedHashMap<>();
        List<Gap> wanted = gaps.stream().filter(Objects::nonNull).filter(gap -> allowed(gap.key().sourceId()))
                .filter(gap -> {
                    Where where = where(gap.key());
                    at.put(gap.key(), where);
                    return !resting(where);
                }).toList();
        if (wanted.isEmpty()) {
            return;
        }
        try {
            if (queue.size() >= timing.maxQueued()) {
                return;
            }
            enqueue(wanted, at);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Warteschlange der Hintergrundfüllung nicht schreibbar ({0})",
                    e.getClass().getName());
            return;
        }
        changed();
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
        warmUp();
        warmedUp = true;
        while (!closed) {
            try {
                housekeeping();
                if (!enabled) {
                    // Bis zum Einschalten: enabled(true) weckt.
                    await(null);
                    continue;
                }
                if (feed()) {
                    // Bis ein Abruf endet, Neues kommt oder der Planer wieder annimmt; kein Nachsehen in Abständen.
                    await(null);
                    continue;
                }
                if (scanDue()) {
                    Duration settle = settling();
                    if (settle != null) {
                        // Ein weiteres wake() verlängert die Ruhe; danach wird einmal gesucht.
                        await(settle);
                        continue;
                    }
                    if (scan() > 0) {
                        continue;
                    }
                }
                await(nextRetry());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Hintergrundfüllung unterbrochen ({0}); neuer Versuch später",
                        e.getClass().getName());
                synchronized (monitor) {
                    // Brach die Suche im Katalog ab, steht sie noch aus; nach der Pause wird erneut gesucht, sonst
                    // wartete die Füllung bis zum nächsten Ereignis.
                    rescan = true;
                }
                await(timing.idle());
            }
        }
    }

    /**
     * Die Anlaufzeit ({@link Timing#initialDelay()}): Nur {@link #close()} beendet sie vorzeitig. Ein {@link #wake()}
     * nach einem Commit des Seiten-Cache, etwa wenn die Titelsuche ihre Funde katalogisiert, merkt nur vor; die Füllung
     * lädt erst danach und bremst den Start nicht.
     */
    private void warmUp() {
        long deadline = System.nanoTime() + timing.initialDelay().toNanos();
        synchronized (monitor) {
            while (!closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    monitor.wait(Math.max(1, remaining / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Wartet, bis {@link #wake()}, neue Lücken, ein beendeter Abruf, der Planer oder {@link #close()} weckt; mit
     * {@code duration} höchstens so lange, ohne ({@code null}) unbegrenzt.
     */
    private void await(Duration duration) {
        long deadline = duration == null ? 0 : System.nanoTime() + duration.toNanos();
        synchronized (monitor) {
            while (!signalled && !closed) {
                try {
                    if (duration == null) {
                        monitor.wait();
                    } else {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            break;
                        }
                        monitor.wait(Math.max(1, remaining / 1_000_000));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            signalled = false;
        }
    }

    /** Ob im Katalog zu suchen ist: nach einer Änderung, beim ersten Mal und wenn eine gescheiterte Seite wieder darf. */
    private boolean scanDue() {
        synchronized (monitor) {
            return rescan || lastScan == null || retryDue();
        }
    }

    /** Wie lange der Katalog nach dem letzten {@link #wake()} noch ruhen muss; {@code null}: jetzt suchen. */
    private Duration settling() {
        synchronized (monitor) {
            if (!rescan || lastScan == null) {
                return null;
            }
            long left = lastWake + SETTLE.toNanos() - System.nanoTime();
            return left > 0 ? Duration.ofNanos(left) : null;
        }
    }

    /** Ob die Wartezeit einer gescheiterten Seite um ist. Unter {@link #monitor}. */
    private boolean retryDue() {
        Instant earliest = earliestFailure();
        return earliest != null && !clock.instant().isBefore(earliest.plus(timing.idle()));
    }

    /** Bis die erste gescheiterte Seite wieder darf; {@code null}, wenn keine wartet (dann nur auf Ereignisse). */
    private Duration nextRetry() {
        synchronized (monitor) {
            Instant earliest = earliestFailure();
            if (earliest == null) {
                return null;
            }
            Duration left = Duration.between(clock.instant(), earliest.plus(timing.idle()));
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    /** Unter {@link #monitor}; {@link #failedAt} ist nach Zeit geordnet, der erste Eintrag ist der älteste. */
    private Instant earliestFailure() {
        return failedAt.isEmpty() ? null : failedAt.values().iterator().next();
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
        int flying;
        synchronized (monitor) {
            if (inFlight.size() >= window) {
                return true;
            }
            flying = inFlight.size();
            seen = completions;
        }
        // Außerhalb der Sperre gelesen (Abnahme #1087): Die Warteschlange hält ihre eigene Sperre über ihre Commits.
        ensureMirrored();
        next = queue.pending(window + flying);
        boolean again = false;
        boolean dropped = false;
        List<PageKey> unmirrored = new ArrayList<>();
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
            Where at;
            boolean gone;
            synchronized (monitor) {
                at = whereOf.get(gap.key());
                // Inzwischen ausgetragen (geladen, geruht, bereinigt) oder die Quelle wird bereinigt: nicht anfordern.
                gone = at == null || purging.contains(sourceId);
                if (at == null && !purging.contains(sourceId)) {
                    // Ohne Spiegel: ein Waise, den sonst nichts austrägt; er belegte dauerhaft einen Platz im Blick.
                    unmirrored.add(gap.key());
                }
            }
            if (gone) {
                continue;
            }
            if (!allowed(sourceId) || resting(at)) {
                // Die Quelle will das (nicht mehr) oder der Space ruht; ein neuer Blick in den Katalog reiht es wieder
                // ein, wenn doch.
                dequeue(gap.key());
                dropped = true;
                continue;
            }
            NetworkState net = network;
            if (net != null && !net.tryPermit()) {
                // Offline: keine weitere Seite, bis das Netz zurück ist oder die nächste Prüfung frei wird; beides
                // weckt die Füllung (network).
                break;
            }
            AcquisitionScheduler.Ticket ticket;
            try {
                ticket = scheduler.request(caller, gap.key(), AcquisitionScheduler.Priority.BACKGROUND,
                        new AcquisitionScheduler.Mode(false, false,
                                com.aresstack.spaceharvester.application.changelog.ChangeTrigger.BACKGROUND));
            } catch (RejectedExecutionException e) {
                // Der Planer ist voll: seine nächste Änderung weckt die Füllung (plannerChanged).
                synchronized (monitor) {
                    blocked = true;
                }
                again = true;
                break;
            }
            synchronized (monitor) {
                inFlight.put(gap.key(), ticket);
            }
            // Liefen revalidate() oder das Ruhen des Space zwischen der Prüfung oben und inFlight.put, sahen sie diese
            // Anfrage nicht (Abnahme #1087): zurückziehen, nicht übergeben.
            boolean withdraw;
            synchronized (monitor) {
                withdraw = purging.contains(sourceId);
            }
            withdraw = withdraw || !allowed(sourceId) || resting(at);
            if (!withdraw) {
                try {
                    observer.handed(at);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Zuschauer der Hintergrundfüllung scheiterte ({0})",
                            e.getClass().getName());
                }
            }
            // Was gleich endet, kommt hier schon zurück; danach gleich weiter in der Schlange.
            again = true;
            ticket.done().whenComplete((stored, failure) -> completed(gap.key(), ticket, stored, failure));
            if (withdraw) {
                cancel(Map.of(gap.key(), ticket));
            }
        }
        if (dropOrphans(unmirrored)) {
            dropped = true;
        }
        if (again || dropped) {
            changed();
        }
        synchronized (monitor) {
            if (dropped && !again) {
                // Hinter dem entfernten Anfang kann noch Arbeit warten, die kein Ereignis mehr meldet: gleich wieder
                // in die Schlange sehen, bis sie leer ist oder etwas übergeben wurde.
                signalled = true;
                return true;
            }
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
        boolean cancelled = failure instanceof CancellationException
                || failure instanceof CompletionException && failure.getCause() instanceof CancellationException;
        // Der Planer schließt einen gescheiterten Abruf mit false und merkt den Grund am Ticket.
        Throwable reason = ticket.failure().map(Throwable.class::cast).orElse(failure);
        NetworkState net = network;
        // Am Netz gescheitert: wie zurückgezogen, die Seite bleibt in der Schlange und zählt nicht (Angelo 22.11.2026).
        boolean offline = !cancelled && !Boolean.TRUE.equals(stored) && net != null && net.failed(reason);
        if (!cancelled && !offline && net != null) {
            // Geladen oder mit Antwort der Quelle gescheitert: Das Netz ist da.
            net.succeeded();
        }
        boolean withdrawn = cancelled || offline;
        Where at;
        synchronized (monitor) {
            at = whereOf.get(key);
            if (at != null && !withdrawn && !closed) {
                reporting.merge(at, 1, Integer::sum);
            }
            if (!withdrawn && !Boolean.TRUE.equals(stored)) {
                // Vor dem Austragen vermerkt: Eine Suche dazwischen reihte die Seite sonst sofort wieder ein.
                failedAt.remove(key);
                failedAt.put(key, clock.instant());
                while (failedAt.size() > timing.maxQueued()) {
                    failedAt.remove(failedAt.keySet().iterator().next());
                }
            }
        }
        if (!withdrawn && !closed) {
            // Geladen oder gescheitert: der Eintrag ist erledigt; eine bleibende Lücke findet die nächste Suche im
            // Katalog wieder. Zurückgezogenes bleibt für den nächsten Start. Erst die Schlange, dann inFlight, damit
            // feed() die Seite nie wartend und zugleich nicht übergeben sieht.
            Where emptied = dequeueQuietly(key);
            if (at != null) {
                // Erst austragen, dann zählen, dann „leer“: Der Teil des Space endet nie vor seiner letzten Seite, und
                // „wartend“ zählt eine erledigte Seite nie mit.
                try {
                    observer.completed(at, Boolean.TRUE.equals(stored), reason);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Zuschauer der Hintergrundfüllung scheiterte ({0})",
                            e.getClass().getName());
                } finally {
                    synchronized (monitor) {
                        if (reporting.merge(at, -1, Integer::sum) <= 0) {
                            reporting.remove(at);
                        }
                    }
                }
                if (emptied == null) {
                    // Wurde die Schlange leer, während diese Meldung lief, meldet die letzte Meldung „leer“.
                    synchronized (monitor) {
                        if (idlePending.contains(at)) {
                            emptied = at;
                        }
                    }
                }
            }
            settle(emptied);
        }
        if (offline && !closed && at != null) {
            try {
                observer.offline(at, reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Zuschauer der Hintergrundfüllung scheiterte ({0})",
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
                }
            }
            signalled = true;
            monitor.notifyAll();
        }
        changed();
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
        Map<PageKey, Where> at = new LinkedHashMap<>();
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
            Where where = new Where(sourceId, space.spaceKey());
            if (!allowed(sourceId) || resting(where)) {
                continue;
            }
            for (Gap gap : catalog.gaps(sourceId, space.spaceKey(), limit)) {
                if (gap.key().sourceId().equals(sourceId)) {
                    found.add(gap);
                    at.put(gap.key(), where);
                }
            }
        }
        synchronized (monitor) {
            // Kürzlich Gescheitertes erst nach der Wartezeit wieder, sonst käme bei übervoller Schlange immer dieselbe
            // Tranche dran.
            Instant retry = clock.instant().minus(timing.idle());
            failedAt.values().removeIf(failure -> !failure.isAfter(retry));
            found.removeIf(gap -> failedAt.containsKey(gap.key()));
        }
        found.sort(RANK);
        boolean more = found.size() > timing.maxQueued();
        List<Gap> gaps = more ? found.subList(0, timing.maxQueued()) : found;
        int before = queue.size();
        if (!gaps.isEmpty()) {
            enqueue(gaps, at);
        }
        int added = queue.size() - before;
        Map<String, Integer> perSource = new LinkedHashMap<>();
        gaps.forEach(gap -> perSource.merge(gap.key().sourceId(), 1, Integer::sum));
        synchronized (monitor) {
            lastScan = clock.instant();
            // Ein wake() während der Suche bleibt stehen: Sie sah den Katalog vielleicht noch ohne diese Änderung.
            rescan = rescan || more && added > 0;
        }
        changed();
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
        Subscription subscribed;
        synchronized (monitor) {
            closed = true;
            signalled = true;
            monitor.notifyAll();
            running = worker;
            subscribed = planner;
        }
        withdraw();
        if (running != null) {
            // Kein Unterbrechen: die Füllung liest höchstens gerade den Katalog.
            running.join();
        }
        withdraw();
        if (subscribed != null) {
            subscribed.close();
        }
        Subscription watched;
        synchronized (monitor) {
            watched = networkWatch;
            networkWatch = null;
        }
        if (watched != null) {
            watched.close();
        }
        notifier.close();
    }
}
