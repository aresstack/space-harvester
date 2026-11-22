package com.aresstack.spaceharvester.application.jobs;

import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Nimmt Aufträge an, führt sie nach den Einstellungen „Vorbereitung“ und „Downloads“ gleichzeitig aus und hält ihren Zustand fest.
 *
 * <p>Ein Auftrag ist sofort nach {@link #submit} als {@link JobState#QUEUED} abrufbar; der Aufrufer wartet nie auf die
 * Arbeit. Abbruch ist kooperativ: ein wartender Auftrag endet sofort, ein laufender beim nächsten
 * {@link JobContext#checkpoint()}. Nach einem Neustart gelten Aufträge, die laut {@link JobStore} noch warteten oder
 * liefen, als gescheitert ({@code INTERRUPTED}); sie werden nicht stillschweigend fortgesetzt. Beendete Aufträge
 * bleiben {@link JobLimits#retention()} lang abrufbar und werden danach beim Start und bei jeder Annahme entfernt.
 *
 * <p>Zu jedem Auftrag hält {@code Jobs} Diagnoseereignisse fest ({@link JobEvent}, docs/DIAGNOSTICS.md): Annahme,
 * Start, Phasenwechsel, Meldungen des Auftrags und das Ende, beim Scheitern mit Komponente, Phase, Fehlercode,
 * HTTP-Status, Proxy-Route, TLS-Befund und der maskierten Ausnahmekette. Die Ereignisse leben so lange wie ihr Auftrag.
 *
 * <p>Der Auftraggeber kann seine Aufträge in jedem Zustand löschen ({@link #delete(String, String)},
 * {@link #deleteAll}); wartende und laufende bricht das vorher ab (ADR 0025). Ereignisse löscht er nur bei beendeten
 * Aufträgen ({@link #deleteEvent}, {@link #deleteEventsBefore(Instant, String)}, sonst
 * {@link JobStillRunningException}).
 *
 * <p>Erreicht ein laufender Auftrag nach einem angeforderten Abbruch oder Anhalten {@link #STOP_GRACE} lang keinen
 * {@link JobContext#checkpoint()}, etwa weil ein Abruf der Quelle hängt, gilt er als abgebrochen bzw. angehalten und
 * gibt seinen Platz frei; die hängende Arbeit wird abgekoppelt: sie meldet nichts mehr, ändert den Eintrag nicht mehr
 * und endet an ihrem nächsten Prüfpunkt.
 *
 * <p>Wer zusehen will, meldet sich an ({@link #subscribe(String, JobListener)}, ADR 0027), statt in Abständen zu fragen:
 * Jede Änderung von Zustand, Zählern, Phase oder Pause und jedes Diagnoseereignis wird gemeldet, gebündelt auf einem
 * eigenen Thread. Damit ein hängender Schritt auch ohne Abfrage abgekoppelt wird, prüft {@code Jobs} nach einem
 * angeforderten Abbruch oder Anhalten nach {@link #STOP_GRACE} selbst.
 *
 * <p>Hält sich ein Auftrag wegen fehlender Verbindung an ({@link PauseReason#CONNECTION_LOST}), läuft er nach
 * {@link RetrySettings#resumeDelay(int)} von selbst wieder an, nie endgültig aufgebend; ebenso läuft ein während des
 * Laufs angefordertes „Nur Fehler neu versuchen“ direkt nach dem Durchgang ({@link #recovery(JobRecovery)},
 * ADR 0025 Punkt 17).
 */
public class Jobs implements AutoCloseable {

    static final String INTERRUPTED = JobLifecycleState.INTERRUPTED;
    static final String TIMEOUT = "TIMEOUT";
    static final String UNEXPECTED = "UNEXPECTED";
    static final String SHUTDOWN = "SHUTDOWN";
    /** Text eines Hintergrundauftrags, den das Beenden des Dienstes unterbrochen hat. */
    static final String SHUTDOWN_INTERRUPTED = "Der Dienst wurde beendet; der Hintergrundauftrag setzt nach dem "
            + "Neustart fort";

    private static final System.Logger LOG = System.getLogger(Jobs.class.getName());
    private static final String COMPONENT = "Jobs";
    /** So lange darf ein laufender Auftrag nach angefordertem Abbruch oder Anhalten bis zu seinem Prüfpunkt brauchen. */
    public static final Duration STOP_GRACE = Duration.ofSeconds(30);

    private final JobStore store;
    private final JobEventStore events;
    /** Angemeldete Empfänger der Auftragsmeldungen (ADR 0027). */
    private final Listeners<JobListener> listeners = new Listeners<>("Aufträge");
    /** Gebündelte Zustellung je Auftrag; die Werte sind die Eigentümer, für die Meldung einer Löschung. */
    private final Coalescer<String, Mark> notifier = new Coalescer<>("jobs", this::deliver);
    /** Wann nach einem angeforderten Abbruch oder Anhalten selbst geprüft wird; in Tests kürzer. */
    private volatile Duration stopWatch = STOP_GRACE.plusSeconds(1);
    private final RouteLookup routes;
    private final Executor executor;
    private final JobLimits limits;
    private final Clock clock;

    private final Object lock = new Object();
    private final Deque<Job> queued = new ArrayDeque<>();
    private final Map<String, Job> open = new HashMap<>();
    /**
     * Angehaltene, gescheiterte und abgebrochene Aufträge einer Art mit {@link JobType#resumable()}, die sich im selben
     * Eintrag fortsetzen lassen; nur bis zum Ende dieses Dienstes, denn die Arbeit selbst lässt sich nicht ablegen.
     */
    private final Map<String, Job> stopped = new HashMap<>();
    /**
     * Aufträge, deren abgekoppelte Arbeit noch läuft ({@link Job#detached}). Solange, startet derselbe Eintrag keinen
     * neuen Lauf: die alte Arbeit hält womöglich noch Dateien oder Sitzungen unter derselben ID.
     */
    private final Set<String> lingering = new HashSet<>();
    private int running;
    /** Hielt ein Lauf mindestens so lange, beginnt die Wartezeit des Wiederanlaufs wieder von vorn. */
    static final Duration RECONNECT_RESET_AFTER = Duration.ofMinutes(2);
    /** Wer Wiederanlauf und nachgeholtes „Nur Fehler“ ausführt; {@code null}, bis die Anwendung es verbindet. */
    private volatile JobRecovery recovery;
    /** Wie oft ein nach dem Durchgang abgelehntes „Nur Fehler neu versuchen“ höchstens versucht wird. */
    static final int RETRY_AFTER_PASS_ATTEMPTS = 48;
    /** So lange nach dem Start eines Laufs für „Nur Fehler neu versuchen“ löst weiteres Drücken keinen weiteren aus. */
    static final Duration RETRY_DEBOUNCE = Duration.ofSeconds(5);
    private JobTimer timer;
    private java.util.concurrent.ScheduledExecutorService ownTimer;
    /** Geplante Wiederanläufe je Auftrag; nur der zuletzt geplante zählt ({@link Reconnect#token}). */
    private final Map<String, Reconnect> reconnects = new HashMap<>();
    /** Nummer des nächsten Wiederanlaufs je Auftrag, ab 0. */
    private final Map<String, Integer> reconnectAttempts = new HashMap<>();

    private record Reconnect(Object token, Instant at) {
    }

    /** Laufende Folgeaufträge ({@link JobType#followUp()}) außer Downloads. */
    private int runningFollowUps;
    /** Laufende Hintergrundaufträge ({@link JobType#background()}). */
    private int runningBackground;
    /**
     * So viele Hintergrundaufträge dürfen zugleich offen sein (eigener Vorrat, ohne Grenze je Auslöser): je Space einer,
     * also auch für viele Spaces genug; dass dieselbe Arbeit nicht doppelt eingeplant wird, prüft
     * {@code BackgroundJobs}.
     */
    static final int BACKGROUND_CAPACITY = 1000;
    /** Code der Warnung: ein Hintergrundauftrag wartet, weil nur ein Platz eingestellt ist. */
    public static final String SINGLE_SLOT = "SINGLE_SLOT";
    static final String SINGLE_SLOT_TEXT = "Wartet, nur ein Platz eingestellt: Hintergrundaufträge laufen erst ab zwei "
            + "Plätzen unter „Vorbereitung“, damit ein Auftrag des Benutzers immer sofort starten kann";
    /**
     * Angabe eines Hintergrundauftrags: die IDs seiner Vorgänger, durch Komma getrennt (D1, Nachtrag ADR 0025). Er
     * startet erst, wenn {@link PredecessorGate} keinen mehr hält; bis dahin zählt er für die Vorfahrt nicht als
     * wartend und hält keinen anderen Auftrag auf. Liegt in den Angaben, übersteht also den Neustart.
     */
    public static final String AFTER = "background.after";
    /** Angabe: die Titel der Vorstufen für „wartet auf …“. */
    public static final String AFTER_TITLES = "background.afterTitles";
    /** Code des Hinweises: ein Hintergrundauftrag wartet auf seine Vorstufe. */
    public static final String WAITING_FOR = "WAITING_FOR";

    /** Ob ein Vorgänger seinen Nachfolger noch hält. */
    @FunctionalInterface
    public interface PredecessorGate {

        /**
         * @param predecessor der Vorgänger oder {@code null}, wenn es ihn nicht mehr gibt
         * @return {@code true}, solange der Nachfolger warten soll
         */
        boolean holds(JobStatus predecessor);

        /**
         * Wie {@link #holds(JobStatus)}, mit dem wartenden Nachfolger, etwa damit ein angehaltener Vorgänger nur
         * Nachfolger derselben Quelle hält (Abnahme #1077). Vorgabe: fragt {@link #holds(JobStatus)}.
         *
         * @param follower der wartende Auftrag
         */
        default boolean holds(JobStatus predecessor, JobStatus follower) {
            return holds(predecessor);
        }

        /** Vorgabe: hält, solange der Vorgänger offen oder angehalten ist. */
        PredecessorGate DEFAULT = predecessor -> predecessor != null
                && (!predecessor.state().terminal() || predecessor.state() == JobState.PAUSED);
    }

    private volatile PredecessorGate gate = PredecessorGate.DEFAULT;
    /**
     * Unter dem Sperrobjekt: abgelegter Stand beendeter oder gelöschter Vorgänger ({@link #AFTER}), damit
     * {@link #next()} nicht bei jedem Lauf die Ablage liest; wird beim Öffnen und Löschen eines Auftrags verworfen.
     */
    private final Map<String, Optional<JobStatus>> predecessors = new HashMap<>();
    /** Downloads ({@link JobType#download()}) in {@link JobStage#PREPARING}. */
    private int preparing;
    /** Downloads in {@link JobStage#DOWNLOADING}. */
    private int downloading;
    /** Vorbereitete Downloads in der Reihenfolge, in der sie einen Download-Platz bekommen. */
    private final Deque<Job> prepared = new ArrayDeque<>();
    private int parallelPreparations;
    private int parallelDownloads;
    private boolean closed;
    /** Nach {@link #interruptBackground()}: kein Hintergrundteil startet oder wird angenommen. */
    private boolean backgroundStopped;

    /**
     * @param executor führt {@link JobTask#start} aus; die Parallelität begrenzt {@code Jobs} selbst, der Executor muss
     *                 also mindestens so viele Aufgaben wie Plätze gleichzeitig annehmen
     */
    public Jobs(JobStore store, Executor executor, JobLimits limits, Clock clock) {
        this(store, new InMemoryJobEventStore(), RouteLookup.NONE, executor, limits, clock);
    }

    /**
     * @param events Ablage der Diagnoseereignisse
     * @param routes Proxy-Route je Quelle für Ereignisse gescheiterter Aufträge
     */
    public Jobs(JobStore store, JobEventStore events, RouteLookup routes, Executor executor, JobLimits limits,
                Clock clock) {
        this.store = new ObservedStore(Objects.requireNonNull(store, "store"));
        this.events = Objects.requireNonNull(events, "events");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.parallelPreparations = limits.parallelPreparations();
        this.parallelDownloads = limits.parallelDownloads();
        markInterrupted();
        purgeExpired();
    }

    /**
     * Nimmt einen Auftrag an, ohne auf ihn zu warten.
     *
     * @param partitionKey Partition (Space) oder {@code null}
     * @param owner        auslösender Client oder {@code null} für interne Aufträge; siehe {@link JobStatus#owner()}
     * @return der Auftrag im Zustand {@link JobState#QUEUED}
     * @throws JobCapacityExceededException wenn bereits {@code parallelPreparations + maxQueued} Aufträge offen sind
     * @throws IllegalStateException         nach {@link #close()}
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String owner, JobTask task) {
        return submit(type, sourceId, partitionKey, null, owner, task);
    }

    /**
     * Wie {@link #submit(JobType, String, String, String, JobTask)}, für einen Auftrag, der nur eine Seite mit ihren
     * Unterseiten betrifft; der Zustand nennt sie ({@link JobStatus#rootPageId()}).
     *
     * @param rootPageId Startseite oder {@code null} für die ganze Partition
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
                            JobTask task) {
        return submit(type, sourceId, partitionKey, rootPageId, owner, Map.of(), task);
    }

    /**
     * Angabe eines Auftrags, den ein Klick des Benutzers angestoßen hat ({@link #submitInteractive}): er zieht an jedem
     * Hintergrundauftrag vorbei.
     */
    public static final String INTERACTIVE = "jobs.interactive";
    /**
     * Angabe: was nach einem Abbruch mit der Arbeit geschieht, etwa „der nächste Start führt den Entzug zu Ende“; das
     * Ereignis des Abbruchs nennt es (Abnahme #1073), ob der Auftrag wartete oder lief.
     */
    public static final String CANCEL_NOTE = "jobs.cancelNote";
    /**
     * Angabe eines Hintergrundauftrags auf eigener Spur (Abnahme Fundstellen 12 und 13): Er belegt keinen Platz unter
     * „Vorbereitung“, zählt nicht gegen die Plätze von Folge- und Hintergrundaufträgen und hat keine Laufzeitgrenze. Nur
     * für Teile, die selbst nichts rechnen, sondern die Arbeit eines eigenen, begrenzten Ausführers sichtbar machen,
     * etwa die Suchtreffer oder die Hintergrundfüllung über den Planer ({@code BackgroundKind#ownLane()}). Wie jeder
     * Hintergrund startet er nicht bei nur einem Platz und nicht nach {@link #interruptBackground()}.
     */
    public static final String OWN_LANE = "jobs.ownLane";

    /**
     * Angabe eines Auftrags, der wie ein Hintergrundauftrag das Ende des Dienstes übersteht: Er gilt danach als
     * unterbrochen ({@code INTERRUPTED}), nicht als abgebrochen, damit ihn der nächste Start fortsetzt, etwa das
     * Löschen der Daten einer entfernten Quelle (Fundstelle 22), das ein Neuladen der App nicht beenden darf.
     */
    public static final String SURVIVES_SHUTDOWN = "jobs.survivesShutdown";

    /**
     * Wie {@link #submit(JobType, String, String, String, String, Map, JobTask)} für einen Klick des Benutzers, etwa
     * „Bilder beschreiben“ je Seite oder „Fachbegriffe ermitteln“: Ein Klick wartet nie hinter Hintergrund. Er nimmt
     * den nächsten freien Platz vor jedem wartenden Hintergrundteil, und laufende Hintergrundteile zählen für ihn nicht
     * gegen die Plätze der Folgeaufträge; unterbrochen wird keiner. Übersteht den Neustart ({@value #INTERACTIVE}).
     */
    public JobStatus submitInteractive(JobType type, String sourceId, String partitionKey, String rootPageId,
                                       String owner, Map<String, String> parameters, JobTask task) {
        if (type.background()) {
            throw new IllegalArgumentException("Ein Hintergrundauftrag ist nie ein Klick");
        }
        Map<String, String> marked = new java.util.HashMap<>(parameters);
        marked.put(INTERACTIVE, "true");
        return submit(type, sourceId, partitionKey, rootPageId, owner, marked, task);
    }

    /**
     * Wie {@link #submit(JobType, String, String, String, String, JobTask)}, mit den Angaben, aus denen sich der
     * Auftrag nach einem Neustart gleichwertig neu anlegen lässt ({@link JobStatus#parameters()}).
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
                            Map<String, String> parameters, JobTask task) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parameters, "parameters");
        purgeExpired();
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, rootPageId, owner,
                Map.copyOf(parameters), task, clock.instant());
        JobStatus status;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && job.type.background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            requireCapacity(type, owner);
            // Vor dem Einreihen: sonst könnte ein paralleler dispatch() den Start vor der Annahme festhalten.
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag angenommen", null, null,
                    null, null);
            open.put(job.id, job);
            predecessors.remove(job.id);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /**
     * Wie {@link #submit(JobType, String, String, String, String, Map, JobTask)}; {@code prepare} läuft, bevor der
     * Auftrag starten kann, etwa um seine Folgeaufträge anzulegen ({@link #submitAwaiting}). Ein Fehler darin hält den
     * Auftrag nicht auf.
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
                            Map<String, String> parameters, JobTask task,
                            java.util.function.Consumer<JobStatus> prepare) {
        Objects.requireNonNull(prepare, "prepare");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parameters, "parameters");
        purgeExpired();
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, rootPageId, owner,
                Map.copyOf(parameters), task, clock.instant());
        job.starting = true;
        JobStatus status;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && job.type.background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            requireCapacity(type, owner);
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag angenommen", null, null,
                    null, null);
            open.put(job.id, job);
            predecessors.remove(job.id);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        try {
            prepare.accept(status);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Vorbereitung von Auftrag {0} gescheitert ({1})", job.id,
                    e.getClass().getName());
        } finally {
            synchronized (lock) {
                job.starting = false;
            }
            dispatch();
        }
        return status;
    }

    /** Angabe eines wartenden Auftrags ({@link #submitAwaiting}): der Auftrag, dem er folgt. */
    public static final String AWAITS = "awaits";

    /**
     * Wie {@link #submit(JobType, String, String, String, String, Map, JobTask)}, aber der Auftrag wartet, bis
     * {@link #releaseAwaiting} ihn freigibt, etwa Bilder und Anhänge, bis der Text ihres Exports veröffentlicht ist
     * (Angelo 11.11.2026). Bis dahin belegt er keinen Platz und zeigt {@link JobStage#AWAITING}. Wird der Auftrag
     * {@code awaited} gelöscht, verschwindet er mit.
     *
     * @param awaited der Auftrag, dem er folgt
     */
    public JobStatus submitAwaiting(JobType type, String sourceId, String partitionKey, String rootPageId,
                                    String owner, Map<String, String> parameters, JobTask task, String awaited) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(awaited, "awaited");
        Map<String, String> withAwaited = new java.util.HashMap<>(parameters);
        withAwaited.put(AWAITS, awaited);
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, rootPageId, owner,
                Map.copyOf(withAwaited), task, clock.instant());
        job.awaiting = awaited;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && job.type.background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            // Ohne Platzprüfung: belegt bis zur Freigabe nichts und ist durch die Zahl der Vorgänger begrenzt.
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                    "Auftrag angenommen; startet, wenn sein Vorgänger fertig ist", null, null, null, null);
            open.put(job.id, job);
            predecessors.remove(job.id);
            queued.addLast(job);
            JobStatus status = job.snapshot();
            store.save(status);
            return status;
        }
    }

    /**
     * Wie {@link #submitAwaiting}, aber freigegeben, sobald der Vorgänger erfolgreich endet ({@link JobState#COMPLETED}),
     * ohne dass jemand {@link #releaseAwaiting} ruft; etwa die Datei eines Exports, die entsteht, sobald sein
     * Datenbestand fertig ist (Angelo 16.11.2026). Ist der Vorgänger schon erfolgreich beendet, reiht sich der Auftrag
     * gleich ein. Hält der Vorgänger an, scheitert er oder wird abgebrochen, wartet der Auftrag weiter, bis derselbe
     * Eintrag später erfolgreich endet; wird der Vorgänger gelöscht, verschwindet er mit.
     *
     * @param awaited der Auftrag, dem er folgt
     * @throws UnknownJobException wenn es den Vorgänger nicht gibt
     */
    public JobStatus submitAfter(JobType type, String sourceId, String partitionKey, String rootPageId,
                                 String owner, Map<String, String> parameters, JobTask task, String awaited) {
        return submitAfter(type, sourceId, partitionKey, rootPageId, owner, parameters, task, List.of(awaited));
    }

    /**
     * Wie {@link #submitAfter(JobType, String, String, String, String, Map, JobTask, String)}, aber nach mehreren
     * Vorgängern: frei, sobald jeder erfolgreich beendet ist, etwa die Datei eines Exports nach dem Export und seinen
     * Bildern und Anhängen. Gewartet wird der Reihe nach; {@link JobStatus#parameter} {@link #AWAITS} nennt den ersten.
     *
     * @param awaited die Vorgänger, mindestens einer
     * @throws UnknownJobException wenn es einen Vorgänger nicht gibt
     */
    public JobStatus submitAfter(JobType type, String sourceId, String partitionKey, String rootPageId,
                                 String owner, Map<String, String> parameters, JobTask task,
                                 java.util.Collection<String> awaited) {
        if (awaited.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Vorgänger");
        }
        JobStatus status;
        synchronized (lock) {
            java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
            for (String id : awaited) {
                Objects.requireNonNull(id, "awaited");
                boolean done = !open.containsKey(id) && store.find(id)
                        .orElseThrow(() -> new UnknownJobException(id)).state() == JobState.COMPLETED;
                if (!done && !pending.contains(id)) {
                    pending.add(id);
                }
            }
            String first = awaited.iterator().next();
            status = submitAwaiting(type, sourceId, partitionKey, rootPageId, owner, parameters, task,
                    pending.isEmpty() ? first : pending.peekFirst());
            Job job = open.get(status.id());
            job.releaseWhenDone = true;
            if (pending.isEmpty()) {
                job.awaiting = null;
            } else {
                pending.pollFirst();
                job.laterAwaited.addAll(pending);
            }
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /**
     * Unter dem Sperrobjekt: Die Aufträge, die auf das erfolgreiche Ende dieses Auftrags warten, warten auf ihren
     * nächsten noch nicht erfolgreich beendeten Vorgänger oder sind frei.
     */
    private void releaseFollowers(Job ended) {
        for (Job follower : open.values()) {
            releaseFollowerFrom(follower, ended.id);
        }
    }

    /** Unter dem Sperrobjekt: Wartet der Nachfolger auf {@code predecessor}, wartet er auf den nächsten oder ist frei. */
    private void releaseFollowerFrom(Job follower, String predecessor) {
        if (follower.releaseWhenDone && predecessor.equals(follower.awaiting) && follower.state == JobState.QUEUED) {
            String next = null;
            while (next == null && !follower.laterAwaited.isEmpty()) {
                String candidate = follower.laterAwaited.pollFirst();
                // Ein gelöschter späterer Vorgänger hält nichts mehr auf.
                boolean done = !open.containsKey(candidate) && store.find(candidate)
                        .map(other -> other.state() == JobState.COMPLETED).orElse(true);
                next = done ? null : candidate;
            }
            follower.awaiting = next;
            store.save(follower.snapshot());
        }
    }

    /**
     * Gibt einen wartenden Auftrag aus {@link #submitAwaiting} frei; er reiht sich dann wie jeder andere ein.
     *
     * @return ob es den Auftrag noch wartend gab; {@code false}, wenn er gelöscht, abgebrochen oder schon frei ist
     */
    public boolean releaseAwaiting(String jobId) {
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job == null || job.awaiting == null || job.state != JobState.QUEUED) {
                return false;
            }
            job.awaiting = null;
            store.save(job.snapshot());
        }
        dispatch();
        return true;
    }

    /** Unter dem Sperrobjekt: ob noch ein Auftrag dieser Art für diesen Eigentümer Platz hat. */
    private void requireCapacity(JobType type, String owner) {
        if (type.background()) {
            // Eigener Vorrat ohne Grenze je Auslöser: der Hintergrund nimmt nie einem Benutzer den Platz.
            if (open.values().stream().filter(other -> other.type.background()).count() >= BACKGROUND_CAPACITY) {
                throw new JobCapacityExceededException(BACKGROUND_CAPACITY);
            }
            return;
        }
        int capacity = parallelPreparations + limits.maxQueued();
        // Folgeaufträge (Anreicherung, Bilder) entstehen, während ihr Auslöser noch einen Platz belegt. Sie haben
        // daher einen eigenen Vorrat derselben Größe und eine eigene Grenze je Eigentümer: zwei je erlaubtem
        // Auslöser, sonst nähme etwa maxOpenPerOwner=1 nie einen an, und ein Client belegt den Vorrat nicht allein.
        // Wer auf seinen Vorgänger wartet (submitAwaiting), belegt nichts: höchstens zwei je angenommenem Export.
        if (open.values().stream().filter(other -> other.awaiting == null && !other.type.background()
                && other.type.followUp() == type.followUp()).count() >= capacity) {
            throw new JobCapacityExceededException(capacity);
        }
        int perOwner = type.followUp() ? 2 * limits.maxOpenPerOwner() : limits.maxOpenPerOwner();
        if (owner != null && open.values().stream()
                .filter(other -> other.awaiting == null && !other.type.background()
                        && other.type.followUp() == type.followUp() && owner.equals(other.owner))
                .count() >= perOwner) {
            throw JobCapacityExceededException.perOwner(perOwner);
        }
    }

    /**
     * Meldet einen Empfänger für die Aufträge eines Auftraggebers an (ADR 0027): gemeldet wird jede Änderung von
     * Zustand, Zählern, Phase oder Pause ({@link JobListener#changed}, je Auftrag höchstens alle
     * {@link Coalescer#INTERVAL}), jede Löschung und jedes neue Diagnoseereignis. Wer danach {@link #list} oder
     * {@link #status} liest, verpasst nichts.
     *
     * @param owner Auftraggeber wie bei {@link #submit}; {@code null} für Aufträge ohne Auftraggeber
     */
    public Subscription subscribe(String owner, JobListener listener) {
        Objects.requireNonNull(listener, "listener");
        return listeners.add(listener, subject -> Objects.equals(subject, owner));
    }

    /**
     * Meldet einen Empfänger für die Aufträge aller Auftraggeber an, wie {@link #subscribe}; für Dienste des Core, die
     * jeden Auftrag einer Art verfolgen (ADR 0030: Update-Marke nach manueller Synchronisierung).
     */
    public Subscription subscribeAll(JobListener listener) {
        Objects.requireNonNull(listener, "listener");
        return listeners.add(listener);
    }

    /** Die Aufträge einer Art aller Auftraggeber mit laufenden Zählern, älteste zuerst. */
    public List<JobStatus> listAll(JobType type) {
        Objects.requireNonNull(type, "type");
        List<JobStatus> result = new ArrayList<>();
        for (JobStatus stored : store.all()) {
            if (stored.type() == type) {
                try {
                    result.add(status(stored.id()));
                } catch (UnknownJobException e) {
                    // inzwischen gelöscht
                }
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt));
        return result;
    }

    /**
     * Wie {@link #await}, wartet aber auch, bis die abgekoppelte Arbeit eines nach der Anhaltefrist für beendet
     * erklärten Laufs wirklich geendet hat; erst dann liest sie nichts mehr und der Eintrag lässt sich neu starten.
     *
     * @throws TimeoutException wenn der Auftrag oder seine abgekoppelte Arbeit nach {@code timeout} noch läuft
     */
    public JobStatus awaitSettled(String jobId, String owner, Duration timeout)
            throws java.util.concurrent.TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        JobStatus status = await(jobId, owner, timeout);
        synchronized (lock) {
            while (lingering.contains(jobId)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new java.util.concurrent.TimeoutException("Auftrag " + jobId + " beendet noch seinen hängenden Schritt");
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
        }
        return status;
    }

    /**
     * Wartet, bis der eigene Auftrag einen Endzustand erreicht hat ({@link JobState#terminal()}), ohne Abfrage im Takt
     * (ADR 0027): Ein Auftrag verlässt die offenen Aufträge genau mit seinem Ende, und jedes Verlassen weckt hier. Auch
     * {@link #close()} weckt, damit niemand nach dem Abbruch der wartenden Aufträge bis zur Frist hängt.
     *
     * @return der Endzustand
     * @throws UnknownJobException  wenn es keinen eigenen Auftrag mit dieser ID gibt
     * @throws TimeoutException     wenn der Auftrag nach {@code timeout} noch läuft oder wartet; er läuft weiter
     * @throws InterruptedException wenn der wartende Thread unterbrochen wird; der Auftrag läuft weiter
     */
    public JobStatus await(String jobId, String owner, Duration timeout)
            throws java.util.concurrent.TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JobStatus status = status(jobId, owner);
            if (status.state().terminal()) {
                return status;
            }
            synchronized (lock) {
                if (open.containsKey(jobId)) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new java.util.concurrent.TimeoutException("Auftrag " + jobId + " läuft nach " + timeout
                                + " noch");
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
            }
        }
    }

    /** Auftraggeber eines Auftrags für die Meldung einer Löschung; {@code null} ohne Auftraggeber. */
    /**
     * Eine Vormerkung: der Auftraggeber und, bei einer Löschung, der letzte Stand des Auftrags, den es danach nicht mehr
     * zu lesen gibt.
     */
    private record Mark(String owner, JobStatus gone) {
    }

    /** Merkt einen Auftrag zur Meldung vor; ohne Empfänger nichts. */
    private void changed(String jobId, String owner) {
        if (!listeners.isEmpty()) {
            notifier.mark(jobId, Set.of(new Mark(owner, null)));
        }
    }

    /** Auf dem Zustell-Thread: der jüngste Stand, oder die Löschung, wenn es den Auftrag nicht mehr gibt. */
    private void deliver(String jobId, Set<Mark> marks) {
        JobStatus status;
        try {
            // Liest wie eine Abfrage, koppelt also auch einen nach STOP_GRACE hängenden Schritt ab.
            status = status(jobId);
        } catch (UnknownJobException e) {
            for (Mark mark : marks) {
                if (mark.gone() != null) {
                    listeners.deliver(mark.owner(), listener -> listener.removed(mark.gone()));
                }
            }
            return;
        }
        listeners.deliver(status.owner(), listener -> listener.changed(status));
    }

    /**
     * Unter dem Sperrobjekt, sobald Abbruch oder Anhalten eines laufenden Auftrags angefordert ist: prüft nach
     * {@link #STOP_GRACE} selbst, ob sein Schritt hängt, statt auf eine Abfrage zu warten.
     */
    private void watchStop(Job job) {
        if (job.state == JobState.RUNNING) {
            notifier.markLater(job.id, Set.of(new Mark(job.owner, null)), stopWatch);
        }
    }

    /** Für Tests: die eigene Prüfung nach {@link #STOP_GRACE} früher, die Uhr des Tests entscheidet. */
    void stopWatch(Duration delay) {
        stopWatch = Objects.requireNonNull(delay, "delay");
    }

    /** @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt */
    public JobStatus status(String jobId) {
        boolean detached = false;
        JobStatus status = null;
        JobTimer restart = null;
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                detached = detachIfStuck(job);
                status = job.snapshot();
                if (detached && job.restartForRetry && job.state == JobState.PAUSED && recovery != null) {
                    // Auch wenn der Durchgang für „Nur Fehler neu versuchen“ abgekoppelt werden musste: gleich neu.
                    job.restartForRetry = false;
                    job.retryFailedRequested = false;
                    job.awaitingRetryRestart = true;
                    restart = timer();
                }
            }
        }
        if (detached) {
            dispatch();
        }
        if (restart != null) {
            JobStatus ended = status;
            restart.schedule(Duration.ZERO, () -> retryAfterPass(ended, 0));
        }
        return status != null ? status : store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
    }

    /**
     * Unter dem Sperrobjekt: beendet einen laufenden Auftrag, der nach angefordertem Abbruch oder Anhalten länger als
     * {@link #STOP_GRACE} keinen Prüfpunkt erreicht hat, und koppelt seine Arbeit ab.
     *
     * @return ob er beendet wurde
     */
    private boolean detachIfStuck(Job job) {
        Instant requested = job.stopRequestedAt;
        if (job.state != JobState.RUNNING || !job.cancelRequested || requested == null
                || clock.instant().isBefore(requested.plus(STOP_GRACE))) {
            return false;
        }
        detach(job, job.pauseRequested ? JobState.PAUSED : JobState.CANCELLED,
                "der laufende Schritt antwortete nicht und wurde abgekoppelt");
        return true;
    }

    /**
     * Unter dem Sperrobjekt: beendet einen laufenden Auftrag sofort mit {@code end}, ohne auf seine Arbeit zu warten,
     * und gibt seinen Platz frei. Die Arbeit läuft womöglich weiter, bis sie einen Prüfpunkt erreicht; ihr Eintrag
     * bleibt davon unberührt ({@link Job#detached}). Fortsetzen startet einen neuen Lauf desselben Eintrags,
     * sobald die alte Arbeit geendet hat ({@link #lingering}).
     */
    private void detach(Job job, JobState end, String why) {
        job.finish(end, null, clock.instant());
        release(job);
        open.remove(job.id);
        lock.notifyAll();
        JobStatus finished = job.snapshot();
        job.emit(true, JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, end.name(),
                (end == JobState.PAUSED ? "Auftrag angehalten: " : "Auftrag abgebrochen: ") + why, null, null, null,
                null);
        job.detach();
        lingering.add(job.id);
        store.save(finished);
    }

    /**
     * Wie {@link #status(String)}, aber nur für den eigenen Auftrag: ein Auftrag eines anderen Clients gilt als
     * unbekannt, damit fremde Job-IDs nicht einmal ihre Existenz verraten.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus status(String jobId, String owner) {
        return requireOwner(status(jobId), owner);
    }

    /**
     * Die eigenen Aufträge einer Art mit laufenden Zählern, neueste zuerst; so findet ein Client auch Aufträge, die
     * nicht er selbst eingeplant hat, etwa die Anreicherung nach seinem Abgleich ({@link JobType#ENRICHMENT}).
     */
    public List<JobStatus> list(String owner, JobType type) {
        List<JobStatus> result = new ArrayList<>();
        for (JobStatus stored : store.all()) {
            if (Objects.equals(stored.owner(), owner) && stored.type() == type) {
                try {
                    result.add(status(stored.id()));
                } catch (UnknownJobException e) {
                    // inzwischen gelöscht
                }
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return result;
    }

    /**
     * Wie {@link #cancel(String)}, aber nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus cancel(String jobId, String owner) {
        requireOwner(status(jobId), owner);
        return cancel(jobId);
    }

    /**
     * Hält einen eigenen Auftrag an (ADR 0025). Ein wartender ist danach {@link JobState#PAUSED}, ein laufender beendet
     * seinen Schritt am nächsten {@link JobContext#checkpoint()} und ist dann angehalten; was er bis dahin geladen
     * hat, bleibt. Für einen beendeten Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException       wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException wenn sich Aufträge dieser Art nicht fortsetzen lassen
     */
    public JobStatus pause(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht anhalten");
        }
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                if (job.cancelRequested && !job.pauseRequested) {
                    // Ein angeforderter Abbruch bleibt ein Abbruch.
                    return job.snapshot();
                }
                job.pauseRequested = true;
                job.pauseReason = PauseReason.USER;
                job.cancelRequested = true;
                // Der Benutzer gewinnt auch während des Beendens (Abnahme #1030).
                job.shutdownInterrupted = false;
                job.stopRequested();
                // Wer nach ↻ anhält, will anhalten: kein Neustart danach.
                job.restartForRetry = false;
                job.retryFailedRequested = false;
                if (job.stopRequestedAt == null) {
                    job.stopRequestedAt = clock.instant();
                    watchStop(job);
                }
                boolean wasQueued = job.state == JobState.QUEUED;
                if (wasQueued) {
                    queued.remove(job);
                    open.remove(jobId);
                    lock.notifyAll();
                    job.finish(JobState.PAUSED, null, clock.instant());
                    stopped.put(jobId, job);
                }
                job.emit(wasQueued, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                        wasQueued ? "PAUSED" : null, wasQueued ? "Auftrag vor dem Start angehalten"
                                : "Anhalten angefordert", null, null, null, null);
                JobStatus status = job.snapshot();
                store.save(status);
                return status;
            }
        }
        return current;
    }

    /**
     * Zieht einen eigenen wartenden Auftrag an den Anfang der Warteschlange („Jetzt starten“); er startet, sobald ein
     * Platz frei ist, die Grenze gleichzeitiger Aufträge gilt weiter. Abgleiche und Exporte starten weiterhin vor
     * Folgeaufträgen. Für einen laufenden oder beendeten Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus prioritize(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null && job.stage == JobStage.PREPARED && prepared.remove(job)) {
                // Vorbereitet: bekommt den nächsten freien Download-Platz.
                prepared.addFirst(job);
            } else if (job == null || job.state != JobState.QUEUED || !queued.remove(job)) {
                return current;
            } else {
                queued.addFirst(job);
            }
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag vorgezogen", null,
                    null, null, null);
        }
        dispatch();
        return status(jobId);
    }

    /**
     * Setzt einen eigenen angehaltenen, gescheiterten oder abgebrochenen Auftrag im selben Eintrag fort (ADR 0025): er
     * wartet wieder auf einen Platz und läuft dann von vorn, holt aber nur, was noch fehlt, weil bereits geladene
     * Seiten liegen bleiben. Die Zähler beginnen dabei neu, damit keine Seite doppelt zählt.
     *
     * @throws UnknownJobException          wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException    wenn die Art kein Fortsetzen kennt, der Auftrag noch läuft oder fertig ist,
     *                                      oder er aus der Zeit vor dem letzten Neustart des Dienstes stammt
     * @throws JobCapacityExceededException wenn zu viele Aufträge offen sind
     */
    public JobStatus resume(String jobId, String owner) {
        return resume(jobId, owner, null);
    }

    /**
     * Wie {@link #resume(String, String)}; {@code renew} ersetzt vorher die Arbeit des Auftrags, etwa um die
     * Berechtigungsprüfung des Auftraggebers mit seinem aktuellen Zugang statt dem vom Start zu führen.
     *
     * @param renew bekommt die bisherige Arbeit und liefert die neue, oder {@code null} für unverändert
     */
    public JobStatus resume(String jobId, String owner, UnaryOperator<JobTask> renew) {
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht fortsetzen");
        }
        if (!resumableState(current.state())) {
            throw new JobNotResumableException(jobId, current.state().terminal() ? "der Auftrag ist fertig"
                    : "der Auftrag wartet oder läuft noch");
        }
        JobStatus status;
        synchronized (lock) {
            requireNotLingering(jobId);
            Job job = stopped.get(jobId);
            if (job == null) {
                throw new JobNotResumableException(jobId, "der Auftrag stammt aus der Zeit vor dem letzten Neustart; "
                        + "ein neuer Start holt ebenfalls nur, was noch fehlt");
            }
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && job.type.background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            requireCapacity(job.type, job.owner);
            JobTask task = renew == null ? job.task : Objects.requireNonNull(renew.apply(job.task), "task");
            stopped.remove(jobId);
            job.task = task;
            job.reopen();
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag fortgesetzt", null,
                    null, null, null);
            open.put(job.id, job);
            predecessors.remove(job.id);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /**
     * Lässt einen eigenen beendeten Auftrag einer fortsetzbaren Art im selben Eintrag mit neuer Arbeit wieder laufen
     * („Fortsetzen“ nach einem Neustart, „Nur Fehler neu versuchen“, „Neu erzwingen“): kein neuer Auftrag, dieselbe
     * ID, dieselben Angaben ({@link JobStatus#parameters()}), die Diagnose läuft weiter. Anders als
     * {@link #resume(String, String, UnaryOperator)} geht das auch für fertige Aufträge und für Aufträge aus der Zeit
     * vor dem letzten Neustart; die Arbeit liefert der Aufrufer, meist aus den abgelegten Angaben. Die Zähler beginnen
     * neu.
     *
     * @param task die neue Arbeit des Auftrags
     * @throws UnknownJobException          wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException    wenn die Art kein Fortsetzen kennt oder der Auftrag noch wartet oder läuft
     * @throws JobCapacityExceededException wenn zu viele Aufträge offen sind
     */
    public JobStatus rerun(String jobId, String owner, JobTask task) {
        return rerun(jobId, owner, null, task);
    }

    /**
     * Wie {@link #rerun(String, String, JobTask)}; {@code parameters} ersetzt die abgelegten Angaben, damit ein
     * Fortsetzen nach einem Neustart dieselbe Arbeit wiederherstellt, etwa „Neu erzwingen“ statt eines gewöhnlichen
     * Laufs.
     *
     * @param parameters die neuen Angaben, oder {@code null} für unverändert
     */
    public JobStatus rerun(String jobId, String owner, Map<String, String> parameters, JobTask task) {
        Objects.requireNonNull(task, "task");
        Map<String, String> renewed = parameters == null ? null : Map.copyOf(parameters);
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht erneut starten");
        }
        JobStatus status;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && current.type().background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            if (open.containsKey(jobId)) {
                throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
            }
            requireNotLingering(jobId);
            Job job = stopped.get(jobId);
            if (job == null) {
                // Unter dem Sperrobjekt erneut gelesen: ein paralleles Löschen gewinnt.
                JobStatus stored = store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
                if (!stored.state().terminal()) {
                    throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
                }
                job = new Job(stored.id(), stored.type(), stored.sourceId(), stored.partitionKey(),
                        stored.rootPageId(), stored.owner(), stored.parameters(), task, stored.createdAt());
                job.rootTitle = stored.rootTitle();
                job.sequence.set(events.events(jobId).stream().mapToLong(JobEvent::sequence).max().orElse(0));
            }
            requireCapacity(job.type, job.owner);
            stopped.remove(jobId);
            job.task = task;
            if (renewed != null) {
                job.parameters = renewed;
            }
            job.reopen();
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag erneut gestartet",
                    null, null, null, null);
            open.put(job.id, job);
            predecessors.remove(job.id);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /**
     * Prüft ohne Wirkung, ob {@link #rerun} diesen Auftrag jetzt annähme: beendet, kein nachlaufender Schritt und Platz
     * frei. Für Aufrufer, die vor dem Neustart andere Aufträge anhalten und das nicht umsonst tun wollen.
     *
     * @throws UnknownJobException         wenn es keinen eigenen Auftrag mit dieser ID gibt
     * @throws JobNotResumableException    wenn er wartet, läuft, noch nachläuft oder sich nicht neu starten lässt
     * @throws JobCapacityExceededException wenn kein Platz frei ist
     */
    public void requireRerunnable(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht erneut starten");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (backgroundStopped && current.type().background()) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Hintergrundaufträge mehr an");
            }
            if (open.containsKey(jobId)) {
                throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
            }
            requireNotLingering(jobId);
            requireCapacity(current.type(), current.owner());
        }
    }

    /** Unter dem Sperrobjekt: kein neuer Lauf, solange die abgekoppelte Arbeit des Eintrags noch läuft. */
    private void requireNotLingering(String jobId) {
        if (lingering.contains(jobId)) {
            throw new JobNotResumableException(jobId, "der vorige Lauf beendet noch seinen hängenden Schritt; "
                    + "später erneut versuchen oder den Auftrag löschen");
        }
    }

    /** Ob ein Auftrag in diesem Zustand im selben Eintrag fortgesetzt werden kann, sofern seine Art es erlaubt. */
    public static boolean resumableState(JobState state) {
        return state == JobState.PAUSED || state == JobState.FAILED || state == JobState.CANCELLED;
    }

    /**
     * Ob sich dieser eigene Auftrag jetzt im selben Eintrag fortsetzen lässt ({@link #resume(String, String)}); sonst
     * startet ein Client ihn mit denselben Angaben neu.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public boolean resumable(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        synchronized (lock) {
            return current.type().resumable() && resumableState(current.state()) && stopped.containsKey(jobId);
        }
    }

    /**
     * Diagnoseereignisse eines Auftrags in ihrer Reihenfolge.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt
     */
    public List<JobEvent> events(String jobId) {
        status(jobId);
        return events.events(jobId);
    }

    /**
     * Wie {@link #events(String)}, aber nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public List<JobEvent> events(String jobId, String owner) {
        requireOwner(status(jobId), owner);
        return events.events(jobId);
    }

    /** Die jüngsten Diagnoseereignisse der Aufträge dieses Clients, neueste zuerst. */
    public List<JobEvent> recentEvents(String owner, int limit) {
        if (limit < 1) {
            return List.of();
        }
        // Je eigenem Auftrag lesen statt global und dann filtern: fremde Ereignisse dürfen eigene nicht verdrängen.
        Set<String> mine = new LinkedHashSet<>();
        synchronized (lock) {
            open.values().stream().filter(job -> Objects.equals(job.owner, owner)).forEach(job -> mine.add(job.id));
        }
        store.all().stream().filter(status -> Objects.equals(status.owner(), owner))
                .forEach(status -> mine.add(status.id()));
        return mine.stream().flatMap(id -> events.events(id).stream())
                .sorted(Comparator.comparing(JobEvent::at).thenComparingLong(JobEvent::sequence).reversed())
                .limit(limit).toList();
    }

    /**
     * Löscht einen eigenen Auftrag in jedem Zustand samt seinen Diagnoseereignissen (ADR 0025). Ein wartender wird
     * vorher abgebrochen, ein laufender sofort beendet und seine Arbeit abgekoppelt: sie endet an ihrem nächsten
     * Prüfpunkt und hinterlässt nichts mehr im Eintrag. Ein Exportartefakt im Exportverzeichnis gehört zum Auftrag und
     * wird mit ihm unerreichbar; das nächste {@code Exports.purge()} räumt es weg. Kopien, die der Benutzer anderswo
     * gespeichert hat, bleiben unberührt.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public void delete(String jobId, String owner) {
        boolean freed = false;
        try {
            // Unter dem Sperrobjekt, damit kein gleichzeitiges Fortsetzen einen gerade gelöschten Auftrag wieder öffnet.
            synchronized (lock) {
                Job open = this.open.get(jobId);
                JobStatus status = requireOwner(open != null ? open.snapshot()
                        : store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId)), owner);
                freed = stop(jobId);
                // Was auf diesen Auftrag wartet (Bilder und Anhänge eines Exports), verschwindet mit; auch, was nach
                // einem Neustart als unterbrochen gilt, ohne je gestartet zu sein.
                for (Job follower : List.copyOf(this.open.values())) {
                    // Ein Nachfolger mehrerer Vorgänger (submitAfter) hängt am ersten, etwa die Datei an ihrem Export,
                    // auch wenn er gerade auf dessen Bilder wartet; ein späterer Vorgänger hält ihn nur nicht mehr auf.
                    boolean anchored = follower.releaseWhenDone
                            ? jobId.equals(follower.parameters.get(AWAITS)) && follower.state == JobState.QUEUED
                            : jobId.equals(follower.awaiting);
                    if (anchored) {
                        stop(follower.id);
                        events.delete(follower.id);
                        store.delete(follower.id);
                    } else if (follower.releaseWhenDone && follower.state == JobState.QUEUED
                            && (jobId.equals(follower.awaiting) || follower.laterAwaited.contains(jobId))) {
                        follower.laterAwaited.remove(jobId);
                        if (jobId.equals(follower.awaiting)) {
                            releaseFollowerFrom(follower, jobId);
                            freed |= follower.awaiting == null;
                        }
                    }
                }
                for (JobStatus follower : store.all()) {
                    if (jobId.equals(follower.parameter(AWAITS)) && follower.state().terminal()
                            && follower.startedAt() == null && !this.open.containsKey(follower.id())) {
                        stopped.remove(follower.id());
                        events.delete(follower.id());
                        store.delete(follower.id());
                    }
                }
                // Ein gelöschter Vorgänger gibt seine Nachfolger frei (D1); sie starten mit einer Warnung.
                predecessors.remove(jobId);
                freed |= awaitedAsPredecessor(jobId);
                stopped.remove(status.id());
                reconnects.remove(status.id());
                reconnectAttempts.remove(status.id());
                // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der Fehler kommt beim Aufrufer an.
                events.delete(status.id());
                store.delete(status.id());
            }
        } finally {
            if (freed) {
                dispatch();
            }
        }
    }

    /**
     * Unter dem Sperrobjekt: beendet einen offenen Auftrag fürs Löschen, ohne Ereignis. Ein wartender verlässt die
     * Warteschlange, ein laufender wird abgekoppelt.
     *
     * @return ob ein Platz frei wurde
     */
    private boolean stop(String jobId) {
        Job job = open.get(jobId);
        if (job == null) {
            return false;
        }
        job.cancelRequested = true;
        job.stopRequested();
        job.pauseRequested = false;
        job.detach();
        open.remove(jobId);
        lock.notifyAll();
        boolean wasRunning = job.state != JobState.QUEUED;
        if (!wasRunning) {
            queued.remove(job);
        }
        job.finish(JobState.CANCELLED, null, clock.instant());
        // Erst den Endzustand ablegen: scheitert das Löschen danach, bleibt ein beendeter Eintrag statt eines laufenden.
        store.save(job.snapshot());
        if (!wasRunning) {
            return false;
        }
        lingering.add(jobId);
        release(job);
        return true;
    }

    /**
     * Löscht alle eigenen Aufträge in jedem Zustand samt ihren Ereignissen, wie {@link #delete(String, String)}.
     *
     * @param type nur Aufträge dieser Art oder {@code null} für alle
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteAll(String owner, JobType type) {
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && (type == null || status.type() == type)) {
                try {
                    delete(status.id(), owner);
                    removed++;
                } catch (UnknownJobException e) {
                    // inzwischen gelöscht
                }
            }
        }
        return removed;
    }

    /**
     * Löscht alle eigenen beendeten Aufträge (abgeschlossen, gescheitert, abgebrochen) samt ihren Ereignissen;
     * wartende und laufende bleiben.
     *
     * @param type nur Aufträge dieser Art oder {@code null} für alle
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteFinished(String owner, JobType type) {
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && (type == null || status.type() == type)
                    && deleteIfFinished(status.id(), false)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Löscht ein Diagnoseereignis eines eigenen beendeten Auftrags. Die Nummern der übrigen Ereignisse bleiben, und
     * keine Nummer wird je wieder vergeben.
     *
     * @return {@code false}, wenn der Auftrag kein Ereignis mit dieser Nummer hat
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobStillRunningException wenn der Auftrag noch wartet oder läuft
     */
    public boolean deleteEvent(String jobId, long sequence, String owner) {
        requireFinished(requireOwner(status(jobId), owner));
        boolean present = events.events(jobId).stream().anyMatch(event -> event.sequence() == sequence);
        if (present) {
            events.delete(jobId, List.of(sequence));
        }
        return present;
    }

    /**
     * Löscht die Diagnoseereignisse eines eigenen beendeten Auftrags, die vor {@code before} entstanden sind.
     *
     * @return Anzahl gelöschter Ereignisse
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobStillRunningException wenn der Auftrag noch wartet oder läuft
     */
    public int deleteEventsBefore(String jobId, Instant before, String owner) {
        Objects.requireNonNull(before, "before");
        requireFinished(requireOwner(status(jobId), owner));
        return deleteBefore(jobId, before);
    }

    /**
     * Löscht die Diagnoseereignisse aller eigenen beendeten Aufträge, die vor {@code before} entstanden sind; die
     * Aufträge selbst bleiben. Ereignisse wartender und laufender Aufträge bleiben.
     *
     * @return Anzahl gelöschter Ereignisse
     */
    public int deleteEventsBefore(Instant before, String owner) {
        Objects.requireNonNull(before, "before");
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && status.state().terminal()) {
                removed += deleteBefore(status.id(), before);
            }
        }
        return removed;
    }

    private int deleteBefore(String jobId, Instant before) {
        List<Long> old = events.events(jobId).stream().filter(event -> event.at().isBefore(before))
                .map(JobEvent::sequence).toList();
        if (!old.isEmpty()) {
            events.delete(jobId, old);
        }
        return old.size();
    }

    private static JobStatus requireFinished(JobStatus status) {
        if (!status.state().terminal()) {
            throw new JobStillRunningException(status.id());
        }
        return status;
    }

    private static JobStatus requireOwner(JobStatus status, String owner) {
        if (!Objects.equals(status.owner(), owner)) {
            throw new UnknownJobException(status.id());
        }
        return status;
    }

    /**
     * Bricht alle offenen Aufträge einer Quelle ab, etwa wenn sie entfernt wird (#188), und wartet, bis keiner mehr
     * läuft. Laufende Aufträge enden an ihrem nächsten {@link JobContext#checkpoint()}.
     *
     * @return {@code true}, wenn innerhalb von {@code timeout} keiner der Quelle mehr offen ist
     */
    /**
     * Quelle eines Auftrags über alle Quellen, etwa des Knowledge-Graph-Exports; {@link #cancelSource} beendet ihn
     * für jede Quelle mit.
     */
    public static final String ALL_SOURCES = "*";

    public boolean cancelSource(String sourceId, Duration timeout) throws InterruptedException {
        return cancelSource(sourceId, null, timeout);
    }

    /**
     * Wie {@link #cancelSource(String, Duration)}, lässt aber den Auftrag {@code exceptJobId} laufen, etwa den
     * Bereinigungsauftrag, der die übrigen Aufträge seiner Quelle beendet.
     *
     * @param exceptJobId Auftrag, der weiterläuft, oder {@code null}
     */
    public boolean cancelSource(String sourceId, String exceptJobId, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(timeout, "timeout");
        // Quellenübergreifende Aufträge (ALL_SOURCES) können Daten jeder Quelle enthalten: sie enden mit.
        java.util.function.Predicate<Job> affected = job -> (job.sourceId.equals(sourceId)
                || ALL_SOURCES.equals(job.sourceId)) && !job.id.equals(exceptJobId);
        List<String> ids;
        synchronized (lock) {
            ids = open.values().stream().filter(affected).map(job -> job.id).toList();
            // Ohne Quelle gibt es nichts fortzusetzen.
            stopped.values().removeIf(affected);
        }
        for (String id : ids) {
            try {
                cancel(id);
            } catch (UnknownJobException e) {
                // inzwischen beendet und gelöscht
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (open.values().stream().anyMatch(affected)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                lock.wait(left);
            }
        }
        return true;
    }

    /**
     * Bricht den eigenen Auftrag nur ab, solange er noch wartet ({@link JobState#QUEUED}), mit einem gelben Grund statt
     * des einfachen „vor dem Start abgebrochen“. Hat er inzwischen begonnen, bleibt er unberührt; die Prüfung und der
     * Abbruch geschehen unter derselben Sperre, ein laufender Auftrag wird so nie unterbrochen.
     *
     * @return der abgebrochene Auftrag, leer wenn er nicht mehr wartete
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public Optional<JobStatus> cancelIfWaiting(String jobId, String owner, String reason) {
        requireOwner(status(jobId), owner);
        return Optional.ofNullable(cancel(jobId, Objects.requireNonNull(reason, "reason"), true));
    }

    /** Code des Info-Ereignisses von {@link #retire}. */
    public static final String RETIRED = "RETIRED";

    /**
     * Schließt einen eigenen beendeten Auftrag, der sich noch fortsetzen ließe ({@link JobState#PAUSED} oder
     * {@link JobState#FAILED}), als {@link JobState#CANCELLED} ab, mit einem Info-Ereignis: Er setzt danach weder nach
     * einem Neustart noch nach einer wiedergefundenen Verbindung von selbst fort. Für einen Teil, dessen Arbeit ein
     * jüngerer mitnimmt (Nachprüfung #1052). Wartet oder läuft er, läuft seine abgekoppelte Arbeit noch nach oder ist
     * er schon abgebrochen oder fertig, ändert sich nichts.
     *
     * @param reason Text des Info-Ereignisses, etwa „durch jüngeren Teil ersetzt“
     * @return der abgeschlossene Auftrag, leer wenn sich nichts geändert hat
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public Optional<JobStatus> retire(String jobId, String owner, String reason) {
        Objects.requireNonNull(reason, "reason");
        synchronized (lock) {
            Job running = open.get(jobId);
            JobStatus current = requireOwner(running != null ? running.snapshot()
                    : store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId)), owner);
            if (running != null || lingering.contains(jobId)
                    || current.state() != JobState.PAUSED && current.state() != JobState.FAILED) {
                return Optional.empty();
            }
            stopped.remove(jobId);
            reconnects.remove(jobId);
            reconnectAttempts.remove(jobId);
            Instant now = clock.instant();
            long sequence = events.events(jobId).stream().mapToLong(JobEvent::sequence).max().orElse(0) + 1;
            appendQuietly(new JobEvent(jobId, sequence, now, current.type(), current.sourceId(),
                    current.partitionKey(), JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, RETIRED,
                    reason, null, null, null, null));
            JobStatus retired = new JobStatus(current.id(), current.type(), current.sourceId(),
                    current.partitionKey(), current.owner(), JobState.CANCELLED, current.progress(), null, false,
                    current.createdAt(), current.startedAt(), now, current.rootPageId(), current.rootTitle(),
                    current.parameters(), current.incomplete(), false, null, null);
            store.save(retired);
            return Optional.of(retired);
        }
    }

    /**
     * Fordert den Abbruch an. Ein wartender Auftrag ist danach {@link JobState#CANCELLED}, ein laufender trägt
     * {@link JobStatus#cancelRequested()} bis zu seinem nächsten {@link JobContext#checkpoint()}. Für einen beendeten
     * Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt
     */
    public JobStatus cancel(String jobId) {
        return cancel(jobId, null, false);
    }

    /**
     * Der eine Abbruchweg für {@link #cancel(String)} und {@link #cancelIfWaiting}: mit {@code onlyIfWaiting} nur,
     * solange der Auftrag unter der Sperre noch {@link JobState#QUEUED} ist (sonst {@code null}); {@code reason} ersetzt
     * den Text des gelben Ereignisses für einen wartenden Auftrag. Ein abgebrochener wartender Vorgänger gibt danach
     * seinen Nachfolger frei (Abnahme D1).
     */
    private JobStatus cancel(String jobId, String reason, boolean onlyIfWaiting) {
        boolean[] release = {false};
        try {
            return cancel(jobId, reason, onlyIfWaiting, release);
        } finally {
            if (release[0]) {
                dispatch();
            }
        }
    }

    private JobStatus cancel(String jobId, String reason, boolean onlyIfWaiting, boolean[] release) {
        synchronized (lock) {
            Job job = open.get(jobId);
            if (onlyIfWaiting && (job == null || job.state != JobState.QUEUED)) {
                return null;
            }
            if (job != null) {
                job.cancelRequested = true;
                // Der Benutzer gewinnt auch während des Beendens: kein „unterbrochen“, kein Fortsetzen danach.
                job.shutdownInterrupted = false;
                job.stopRequested();
                if (job.stopRequestedAt == null) {
                    job.stopRequestedAt = clock.instant();
                    watchStop(job);
                }
                // Abbrechen geht vor Anhalten und vor einem Neustart für „Nur Fehler neu versuchen“.
                job.pauseRequested = false;
                job.restartForRetry = false;
                boolean wasQueued = job.state == JobState.QUEUED;
                if (wasQueued) {
                    queued.remove(job);
                    open.remove(jobId);
                    lock.notifyAll();
                    job.finish(JobState.CANCELLED, null, clock.instant());
                    keepResumable(job);
                    release[0] = awaitedAsPredecessor(jobId);
                }
                // Erst das Ereignis, dann der Zustand: Ein beendeter Auftrag bekommt danach nie mehr ein Ereignis, sonst
                // könnte eine Löschung dazwischen eines verwaist zurücklassen.
                String note = job.parameters == null ? null : job.parameters.get(CANCEL_NOTE);
                job.emit(wasQueued, wasQueued ? JobEvent.Severity.WARNING : JobEvent.Severity.INFO,
                        JobEvent.Category.JOB, COMPONENT, wasQueued ? "CANCELLED" : null,
                        (wasQueued ? (reason == null ? "Auftrag vor dem Start abgebrochen" : reason)
                                : "Abbruch angefordert") + (note == null || note.isBlank() ? "" : "; " + note),
                        null, null, null, null);
                JobStatus status = job.snapshot();
                store.save(status);
                return status;
            }
        }
        return store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
    }

    /**
     * Nimmt keine Aufträge mehr an, bricht wartende ab und fordert den Abbruch der laufenden an. Läuft ein Auftrag über
     * das Ende des Dienstes hinaus, gilt er beim nächsten Start als {@code INTERRUPTED}.
     */
    /**
     * Erster Schritt beim Beenden des Dienstes, vor dem Schließen der übrigen Bausteine (Abnahme #1030): laufende
     * Hintergrundaufträge, die der Benutzer weder angehalten noch abgebrochen hat, gelten ab jetzt als unterbrochen.
     * Scheitern sie danach, weil etwa der Planer ihre Abrufe abwirft, enden sie trotzdem als {@code INTERRUPTED} und
     * setzen nach dem Neustart fort.
     */
    public void interruptBackground() {
        synchronized (lock) {
            // Ab jetzt startet kein Hintergrundteil mehr und keiner wird angenommen (Abnahme #1030).
            backgroundStopped = true;
            for (Job job : open.values()) {
                if (survivesShutdown(job) && !job.cancelRequested && !job.pauseRequested) {
                    job.shutdownInterrupted = true;
                }
            }
        }
    }

    @Override
    public void close() {
        interruptBackground();
        synchronized (lock) {
            closed = true;
            Instant now = clock.instant();
            for (Job job : queued) {
                open.remove(job.id);
                job.cancelRequested = true;
                job.stopRequested();
                if (survivesShutdown(job)) {
                    // Hintergrund: unterbrochen statt abgebrochen, damit er nach dem Neustart von selbst fortsetzt.
                    job.finish(JobState.FAILED, new JobError(INTERRUPTED, SHUTDOWN_INTERRUPTED), now);
                } else {
                    job.finish(JobState.CANCELLED, null, now);
                }
                store.save(job.snapshot());
            }
            queued.clear();
            // Weckt, wer auf das Ende eines wartenden Auftrags wartet ({@link #await}).
            lock.notifyAll();
            for (Job job : open.values()) {
                if (survivesShutdown(job)) {
                    // Abgelegt wird, was der Benutzer wollte: so erkennt der nächste Start ein Anhalten oder einen
                    // Abbruch des Benutzers ({@link #markInterrupted}) und setzt nur Unterbrochenes fort.
                    store.save(job.snapshot());
                    job.cancelRequested = true;
                    job.stopRequested();
                    continue;
                }
                job.cancelRequested = true;
                job.stopRequested();
                store.save(job.snapshot());
            }
            reconnects.clear();
            if (ownTimer != null) {
                ownTimer.shutdownNow();
            }
        }
        notifier.close();
    }

    /**
     * Entfernt beendete Aufträge, deren Ende länger als {@link JobLimits#retention()} zurückliegt.
     *
     * @return Anzahl entfernter Aufträge
     */
    public int purgeExpired() {
        Instant limit = clock.instant().minus(limits.retention());
        int removed = 0;
        List<JobStatus> all = store.all();
        // Angehaltene Vorgänger, auf die ein noch offener oder fortsetzbarer Nachfolger wartet, halten und bleiben
        // (Abnahme D1), auch vor dem Fortsetzen nach einem Neustart.
        java.util.Set<String> awaited = new java.util.HashSet<>();
        for (JobStatus follower : all) {
            String after = follower.parameter(AFTER);
            if (after != null && (!follower.state().terminal() || resumableState(follower.state()))) {
                awaited.addAll(java.util.Arrays.asList(after.split(",")));
            }
        }
        for (JobStatus status : all) {
            if (status.state().terminal() && status.finishedAt() != null && status.finishedAt().isBefore(limit)
                    && !(status.state() == JobState.PAUSED && awaited.contains(status.id()))
                    && deleteIfFinished(status.id(), true)) {
                removed++;
            }
        }
        return removed;
    }

    private void markInterrupted() {
        Instant now = clock.instant();
        for (JobStatus status : store.all()) {
            if (!status.state().terminal() && status.type().background()
                    && (status.pauseRequested() || status.cancelRequested())) {
                // Angehalten oder abgebrochen kurz vor dem Ende oder Absturz: bleibt beim Wunsch des Benutzers, nie
                // fortsetzen (Abnahme #1030).
                store.save(status.stoppedBeforeEnd(now));
                continue;
            }
            if (!status.state().terminal()) {
                store.save(status.interrupted(new JobError(INTERRUPTED, "Der Dienst wurde während des Auftrags "
                        + "beendet" + (status.type().resumable() ? "; Fortsetzen macht dort weiter, wo er stand"
                        : "; der Auftrag muss neu gestartet werden")), now));
                // Nach der höchsten Nummer, nicht nach der Anzahl: gelöschte Ereignisse hinterlassen Lücken.
                long next = events.events(status.id()).stream().mapToLong(JobEvent::sequence).max().orElse(0) + 1;
                appendQuietly(new JobEvent(status.id(), next, now, status.type(),
                        status.sourceId(), status.partitionKey(), JobEvent.Severity.ERROR, JobEvent.Category.JOB,
                        COMPONENT, null, INTERRUPTED, "Der Dienst wurde während des Auftrags beendet", null, null,
                        null, null));
            }
        }
    }

    /**
     * Löscht einen beendeten Auftrag samt Ereignissen, geprüft und gelöscht unter dem Sperrobjekt, damit ein
     * gleichzeitiges {@link #resume} ihn nicht dazwischen wieder öffnet.
     *
     * @param quietly Fehler beim Löschen der Ereignisse nur protokollieren; der Auftrag bleibt dann stehen
     * @return ob gelöscht wurde
     */
    private boolean deleteIfFinished(String jobId, boolean quietly) {
        boolean release = false;
        try {
            synchronized (lock) {
                boolean deleted = deleteIfFinishedLocked(jobId, quietly);
                if (deleted) {
                    predecessors.remove(jobId);
                    release = awaitedAsPredecessor(jobId);
                }
                return deleted;
            }
        } finally {
            if (release) {
                // Ein gelöschter Vorgänger gibt seinen Nachfolger frei (Abnahme D1).
                dispatch();
            }
        }
    }

    private boolean deleteIfFinishedLocked(String jobId, boolean quietly) {
        synchronized (lock) {
            if (open.containsKey(jobId)) {
                return false;
            }
            Optional<JobStatus> stored = store.find(jobId);
            if (stored.isEmpty() || !stored.get().state().terminal()) {
                return false;
            }
            // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der nächste Lauf versucht es erneut.
            if (quietly) {
                if (!deleteEventsQuietly(jobId)) {
                    return false;
                }
            } else {
                events.delete(jobId);
            }
            stopped.remove(jobId);
            store.delete(jobId);
            return true;
        }
    }

    private void appendQuietly(JobEvent event) {
        try {
            events.append(event);
        } catch (RuntimeException e) {
            // Diagnose darf keinen Auftrag scheitern lassen.
            LOG.log(System.Logger.Level.WARNING, "Diagnoseereignis nicht abgelegt ({0})", e.getClass().getName());
        }
    }

    private boolean deleteEventsQuietly(String jobId) {
        try {
            events.delete(jobId);
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Diagnoseereignisse nicht entfernt ({0})", e.getClass().getName());
            return false;
        }
    }

    private void dispatch() {
        List<Job> toStart = new ArrayList<>();
        synchronized (lock) {
            Job job;
            while ((job = next()) != null) {
                if (job.type.download()) {
                    preparing++;
                    job.stage = JobStage.PREPARING;
                } else if (!ownLane(job)) {
                    running++;
                    if (job.type.followUp()) {
                        runningFollowUps++;
                    }
                    if (job.type.background()) {
                        runningBackground++;
                    }
                }
                job.state = JobState.RUNNING;
                job.startedAt = clock.instant();
                if (job.awaitingRetryRestart) {
                    // Neuer Lauf für „Nur Fehler neu versuchen“: ab jetzt RETRY_DEBOUNCE lang kein weiterer Neustart.
                    job.awaitingRetryRestart = false;
                    job.retryRestartedAt = job.startedAt;
                }
                // Bilder und Anhänge: die Laufzeitgrenze gilt erst ab dem Download-Platz (grantDownloads), nicht für
                // Vorbereitung und Warten.
                job.deadline = job.type.download() || ownLane(job) ? null : job.startedAt.plus(limits.maxRunTime());
                store.save(job.snapshot());
                toStart.add(job);
            }
            grantDownloads();
        }
        for (Job job : toStart) {
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag gestartet", null, null,
                    null, null);
            try {
                executor.execute(() -> run(job));
            } catch (RejectedExecutionException e) {
                complete(job, new JobFailedException(SHUTDOWN, "Der Auftrag konnte nicht gestartet werden, "
                        + "weil der Dienst beendet wird"));
            }
        }
    }

    /**
     * Der nächste startbare Auftrag, aus der Warteschlange genommen: Downloads ({@link JobType#download()}) haben eigene
     * Plätze für die Vorbereitung ({@link #parallelPreparations()}); sonst Abgleiche und Exporte vor Folgeaufträgen,
     * damit die Anreicherung nie einen Export aufhält; Folgeaufträge belegen höchstens alle Plätze bis auf einen.
     * Hintergrundaufträge ({@link JobType#background()}) kommen zuletzt: nur wenn kein anderer Auftrag wartet, und
     * zusammen mit den Folgeaufträgen höchstens alle Plätze bis auf einen. Unter dem Sperrobjekt.
     */
    private Job next() {
        // #944 (Angelo 17.11.2026): keine festen Auftragsplätze mehr. Abgleiche, Seitenexporte und Folgeaufträge
        // laufen nach derselben Einstellung „Vorbereitung“ wie die Downloads, sofort wirksam.
        boolean general = running < parallelPreparations;
        int spare = Math.max(1, parallelPreparations - 1);
        // Folge- und Hintergrundaufträge zusammen nie auf allen Plätzen, egal wer zuerst kam.
        boolean followUps = general && runningFollowUps + runningBackground < spare;
        // Ein Klick (INTERACTIVE) wartet nie hinter Hintergrund: laufende Hintergrundteile zählen für ihn nicht.
        boolean interactive = general && runningFollowUps < spare;
        // Hintergrund (Angelo 21.11.2026): zusammen mit den Folgeaufträgen nie alle Plätze, damit ein Auftrag des
        // Benutzers immer sofort starten kann.
        // Abnahme #1030: Obergrenze ohne Mindestplatz; bei nur einem Platz läuft kein Hintergrund (wartet gelb).
        boolean background = general && runningFollowUps + runningBackground < parallelPreparations - 1;
        boolean preparations = preparing < parallelPreparations;
        Job pick = null;
        Job backgroundPick = null;
        boolean othersWaiting = false;
        for (Job job : queued) {
            if (job.awaiting != null || job.starting) {
                continue;
            }
            if (job.type.background()) {
                if (ownLane(job) && parallelPreparations > 1 && !backgroundStopped && !held(job)) {
                    // Eigene Spur: ohne Platz, sofort; sie hält keinen anderen Auftrag auf.
                    pick = job;
                    break;
                }
                // Gesperrt zählt nicht als wartend und hält keinen anderen auf (Abnahme D1); geprüft wird nur, solange
                // der Hintergrund starten darf und noch kein Kandidat gefunden ist.
                if (backgroundPick == null && background && !backgroundStopped && !ownLane(job) && !held(job)) {
                    backgroundPick = job;
                }
                if (parallelPreparations <= 1 && !job.singleSlotNoted) {
                    job.singleSlotNoted = true;
                    job.emit(JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, SINGLE_SLOT,
                            SINGLE_SLOT_TEXT, null, null, null, null);
                }
                continue;
            }
            othersWaiting = true;
            if (job.type.download() ? preparations : general && !job.type.followUp()) {
                pick = job;
                break;
            }
            if (pick == null && !job.type.download() && (followUps || interactive && interactive(job))) {
                pick = job;
            }
        }
        // Nachrangig: ein Hintergrundauftrag startet nur, wenn kein anderer Auftrag auf einen Platz wartet.
        if (pick == null && !othersWaiting) {
            pick = backgroundPick;
        }
        if (pick != null) {
            queued.remove(pick);
        }
        return pick;
    }

    /** Setzt, wann ein Vorgänger seinen Nachfolger hält ({@link #AFTER}); die Hintergrundaufträge setzen das. */
    public void predecessorGate(PredecessorGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
        synchronized (lock) {
            lock.notifyAll();
        }
        dispatch();
    }

    /** Unter dem Sperrobjekt: ob ein Vorgänger den Auftrag noch hält; meldet „wartet auf …“ einmal je Sperre. */
    private boolean held(Job job) {
        String after = job.parameters == null ? null : job.parameters.get(AFTER);
        if (after == null || after.isBlank()) {
            return false;
        }
        boolean held = false;
        JobStatus follower = null;
        for (String id : after.split(",")) {
            if (id.isBlank()) {
                continue;
            }
            Job open = this.open.get(id);
            JobStatus predecessor = open != null ? open.snapshot()
                    : predecessors.computeIfAbsent(id, store::find).orElse(null);
            if (follower == null) {
                follower = job.snapshot();
            }
            if (gate.holds(predecessor, follower)) {
                held = true;
                break;
            }
        }
        if (held && !job.waitingNoted) {
            job.waitingNoted = true;
            String titles = job.parameters.get(AFTER_TITLES);
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, WAITING_FOR,
                    "wartet auf " + (titles == null || titles.isBlank() ? "die Vorstufe" : titles), null, null, null,
                    null);
        } else if (!held) {
            job.waitingNoted = false;
        }
        return held;
    }

    /** Unter dem Sperrobjekt: ob ein wartender Auftrag auf diesen als Vorgänger ({@link #AFTER}) wartet. */
    private boolean awaitedAsPredecessor(String jobId) {
        for (Job follower : queued) {
            String after = follower.parameters == null ? null : follower.parameters.get(AFTER);
            if (after != null && java.util.Arrays.asList(after.split(",")).contains(jobId)) {
                return true;
            }
        }
        return false;
    }

    /** Ob ein Klick des Benutzers den Auftrag angestoßen hat ({@link #submitInteractive}). */
    private static boolean interactive(Job job) {
        return job.parameters != null && "true".equals(job.parameters.get(INTERACTIVE));
    }

    /** Ob der Auftrag auf eigener Spur läuft ({@value #OWN_LANE}). */
    private static boolean survivesShutdown(Job job) {
        return job.type.background()
                || job.parameters != null && "true".equals(job.parameters.get(SURVIVES_SHUTDOWN));
    }

    private static boolean ownLane(Job job) {
        return job.type.background() && job.parameters != null && "true".equals(job.parameters.get(OWN_LANE));
    }

    /** Unter dem Sperrobjekt: gibt den Platz eines beendeten Auftrags frei. */
    private void release(Job job) {
        if (ownLane(job)) {
            return;
        }
        if (!job.type.download()) {
            running--;
            if (job.type.followUp()) {
                runningFollowUps--;
            }
            if (job.type.background()) {
                runningBackground--;
            }
            return;
        }
        JobStage stage = job.stage;
        if (stage == JobStage.PREPARING) {
            preparing--;
        } else if (stage == JobStage.PREPARED) {
            prepared.remove(job);
        } else if (stage == JobStage.DOWNLOADING) {
            downloading--;
        }
        job.stage = null;
    }

    /** Unter dem Sperrobjekt: vergibt freie Download-Plätze an vorbereitete Downloads, der Reihe nach. */
    private void grantDownloads() {
        while (downloading < parallelDownloads && !prepared.isEmpty()) {
            Job job = prepared.pollFirst();
            downloading++;
            job.stage = JobStage.DOWNLOADING;
            // Die Laufzeitgrenze gilt ab dem Download: Warten auf einen Platz zählt nicht.
            job.deadline = clock.instant().plus(limits.maxRunTime());
            store.save(job.snapshot());
            job.slot.complete(null);
        }
    }

    /** Höchstens gleichzeitig vorbereitende Downloads ({@code jobs.parallel-preparations}). */
    public int parallelPreparations() {
        synchronized (lock) {
            return parallelPreparations;
        }
    }

    /** Ändert {@link #parallelPreparations()} sofort; laufende Vorbereitungen laufen zu Ende. */
    public void parallelPreparations(int value) {
        JobLimits.requirePreparations(value);
        synchronized (lock) {
            parallelPreparations = value;
        }
        dispatch();
    }

    /** Höchstens gleichzeitig ladende Downloads ({@code jobs.parallel-downloads}); 0 bereitet nur vor. */
    public int parallelDownloads() {
        synchronized (lock) {
            return parallelDownloads;
        }
    }

    /**
     * Wie viele Downloads ({@link JobType#download()}) gerade laden (höchstens {@link #parallelDownloads()}, bis auf
     * Restläufer).
     */
    public int downloadingNow() {
        synchronized (lock) {
            return downloading;
        }
    }

    /** Ändert {@link #parallelDownloads()} sofort; laufende Downloads laufen zu Ende. */
    public void parallelDownloads(int value) {
        JobLimits.requireDownloads(value);
        synchronized (lock) {
            parallelDownloads = value;
        }
        dispatch();
    }

    /**
     * Der Auslöser, unter dem ein Auftrag in den Cache schreibt (ADR 0030): die Angabe {@link ChangeTrigger#PARAMETER}
     * am Auftrag, sonst nach seiner Art; {@link ChangeTrigger#UNKNOWN}, wenn beides nichts sagt.
     */
    static ChangeTrigger trigger(JobType type, Map<String, String> parameters) {
        String given = parameters == null ? null : parameters.get(ChangeTrigger.PARAMETER);
        if (given != null) {
            return ChangeTrigger.parse(given);
        }
        return switch (type) {
            case EXPORT, ASSETS, ATTACHMENTS, CONVERSION, METADATA_EXPORT, ANALYTICS_EXPORT, KNOWLEDGE_GRAPH_EXPORT ->
                    ChangeTrigger.EXPORT;
            case HARVEST, IMPORT -> ChangeTrigger.MANUAL;
            default -> ChangeTrigger.UNKNOWN;
        };
    }

    private void run(Job job) {
        CompletionStage<Void> stage;
        try {
            // ADR 0030: Was der Auftrag in den Cache schreibt, trägt seinen Auslöser; blockierende Arbeit läuft hier.
            stage = trigger(job.type, job.parameters).call(() -> job.task.start(job));
            if (stage == null) {
                stage = CompletableFuture.failedFuture(new IllegalStateException("JobTask lieferte null"));
            }
        } catch (Throwable e) {
            stage = CompletableFuture.failedFuture(e);
        }
        stage.whenComplete((ignored, failure) -> complete(job, failure));
    }

    private void complete(Job job, Throwable failure) {
        Throwable cause = unwrap(failure);
        JobStatus finished;
        int reconnectAttempt;
        boolean retryFailed;
        JobTimer later;
        synchronized (lock) {
            if (job.state.terminal()) {
                if (job.detached && lingering.remove(job.id)) {
                    lock.notifyAll();
                }
                return;
            }
            Instant now = clock.instant();
            if (cause == null) {
                job.finish(JobState.COMPLETED, null, now);
            } else if (job.shutdownInterrupted) {
                // Hintergrund beim Beenden des Dienstes: unterbrochen, nicht abgebrochen; setzt nach dem Neustart fort.
                job.finish(JobState.FAILED, new JobError(INTERRUPTED, SHUTDOWN_INTERRUPTED), now);
            } else if (cause instanceof JobCancelledException || job.cancelRequested) {
                // Auch wenn der Schritt nach dem angeforderten Abbruch anders scheitert, etwa eine abgebrochene
                // Modellanfrage: Der Benutzer hat abgebrochen oder angehalten, ein Fehler war es nicht.
                job.finish(job.pauseRequested ? JobState.PAUSED : JobState.CANCELLED, null, now);
            } else {
                job.finish(JobState.FAILED, errorOf(cause), now);
            }
            keepResumable(job);
            release(job);
            open.remove(job.id);
            lock.notifyAll();
            finished = job.snapshot();
            if (cause != null && !(cause instanceof JobCancelledException) && finished.state() != JobState.FAILED) {
                // Nach Abbruch oder Anhalten anders gescheitert: nur die Klasse als Hinweis, kein Fehler am Auftrag.
                job.emit(true, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                        "Schritt endete nach dem Abbruch mit " + cause.getClass().getName(), null, null, null, null);
            }
            // Erst das Endereignis, dann der Endzustand: Wer den Endzustand sieht, findet auch die Diagnose.
            recordEnd(job, finished, cause);
            store.save(finished);
            if (finished.state() == JobState.COMPLETED) {
                releaseFollowers(job);
            }
            reconnectAttempt = followUp(job, finished);
            // Für ↻ angehalten: gleich weiter im selben Eintrag, auch ohne neue Fehler; sonst nur mit Fehlern.
            boolean pressed = job.restartForRetry;
            boolean restart = pressed && finished.state() == JobState.PAUSED;
            job.restartForRetry = false;
            retryFailed = job.retryFailedRequested && (restart || hasFailures(finished)) && recovery != null;
            // Die Sperre gegen Mehrfachdrücken beginnt mit dem neuen Lauf (dispatch), nicht schon beim Drücken.
            job.awaitingRetryRestart = pressed && retryFailed;
            if (retryFailed || finished.state() != JobState.PAUSED) {
                job.retryFailedRequested = false;
            }
            later = retryFailed ? timer() : null;
        }
        dispatch();
        if (reconnectAttempt >= 0) {
            scheduleReconnect(job.id, reconnectAttempt);
        }
        if (retryFailed) {
            JobStatus ended = finished;
            later.schedule(Duration.ZERO, () -> retryAfterPass(ended, 0));
        }
    }

    /**
     * Das während des Laufs angeforderte „Nur Fehler neu versuchen“. Lehnt der Anwendungsfall es gerade ab, etwa weil
     * Bilder oder Anhänge des Exports noch laufen, versucht es der Auftrag mit wachsender Wartezeit erneut, solange er
     * unverändert so beendet ist, höchstens {@link #RETRY_AFTER_PASS_ATTEMPTS} Mal.
     */
    private void retryAfterPass(JobStatus ended, int attempt) {
        JobStatus now;
        try {
            now = status(ended.id());
        } catch (UnknownJobException e) {
            return;
        }
        if (now.state() != ended.state() || !Objects.equals(now.finishedAt(), ended.finishedAt())) {
            // Inzwischen fortgesetzt, neu gestartet oder gelöscht: dann entscheidet der Benutzer.
            return;
        }
        if (perform(ended, JobAction.RETRY_FAILED)) {
            return;
        }
        if (attempt + 1 >= RETRY_AFTER_PASS_ATTEMPTS) {
            LOG.log(System.Logger.Level.INFO, "Nur Fehler neu versuchen für Auftrag {0} aufgegeben", ended.id());
            return;
        }
        Duration delay;
        JobTimer current;
        synchronized (lock) {
            if (closed || recovery == null) {
                return;
            }
            delay = limits.retry().resumeDelay(attempt);
            current = timer();
        }
        current.schedule(delay, () -> retryAfterPass(ended, attempt + 1));
    }

    /**
     * Unter dem Sperrobjekt: die Nummer des nächsten Wiederanlaufs, wenn sich der Auftrag wegen fehlender Verbindung
     * angehalten hat, sonst -1. Hielt der Lauf {@link #RECONNECT_RESET_AFTER}, war die Verbindung wieder da, und die
     * Wartezeit beginnt von vorn.
     */
    private int followUp(Job job, JobStatus finished) {
        if (finished.state() != JobState.PAUSED || finished.pauseReason() != PauseReason.CONNECTION_LOST
                || recovery == null || closed) {
            reconnectAttempts.remove(job.id);
            reconnects.remove(job.id);
            return -1;
        }
        boolean held = finished.startedAt() != null && finished.finishedAt() != null
                && !Duration.between(finished.startedAt(), finished.finishedAt()).minus(RECONNECT_RESET_AFTER)
                .isNegative();
        return held ? 0 : reconnectAttempts.getOrDefault(job.id, 0);
    }

    private static boolean hasFailures(JobStatus status) {
        return (status.state() == JobState.COMPLETED || status.state() == JobState.FAILED)
                && (status.progress().failed() > 0 || status.incomplete()
                || status.state() == JobState.FAILED);
    }

    /**
     * Verbindet Wiederanlauf und nachgeholtes „Nur Fehler neu versuchen“ mit den Anwendungsfällen (ADR 0025 Punkt 17).
     * Aufträge, die beim letzten Ende des Dienstes wegen fehlender Verbindung angehalten waren, laufen nach der ersten
     * Wartezeit wieder an.
     */
    public void recovery(JobRecovery recovery) {
        recovery(recovery, null);
    }

    /** Wie {@link #recovery(JobRecovery)}, mit eigener Zeitsteuerung, etwa in Tests; {@code null} für die eigene. */
    public void recovery(JobRecovery recovery, JobTimer timer) {
        Objects.requireNonNull(recovery, "recovery");
        synchronized (lock) {
            this.recovery = recovery;
            this.timer = timer;
        }
        for (JobStatus status : store.all()) {
            if (status.state() == JobState.PAUSED && status.pauseReason() == PauseReason.CONNECTION_LOST) {
                scheduleReconnect(status.id(), 0);
            }
        }
    }

    /** Wann ein wegen fehlender Verbindung angehaltener Auftrag das nächste Mal von selbst anläuft, sonst leer. */
    public Optional<Instant> reconnectAt(String jobId) {
        synchronized (lock) {
            Reconnect pending = reconnects.get(jobId);
            return pending == null ? Optional.empty() : Optional.of(pending.at());
        }
    }

    private void scheduleReconnect(String jobId, int attempt) {
        Duration delay;
        Reconnect pending;
        JobTimer current;
        synchronized (lock) {
            if (closed || recovery == null) {
                return;
            }
            delay = limits.retry().resumeDelay(attempt);
            pending = new Reconnect(new Object(), clock.instant().plus(delay));
            reconnects.put(jobId, pending);
            reconnectAttempts.put(jobId, attempt + 1);
            current = timer();
        }
        current.schedule(delay, () -> reconnect(jobId, pending.token()));
    }

    /** Ein geplanter Wiederanlauf; nur, wenn er noch gilt und der Auftrag noch so angehalten ist. */
    private void reconnect(String jobId, Object token) {
        synchronized (lock) {
            Reconnect pending = reconnects.get(jobId);
            if (closed || pending == null || pending.token() != token) {
                return;
            }
            reconnects.remove(jobId);
        }
        JobStatus status;
        try {
            status = status(jobId);
        } catch (UnknownJobException e) {
            forgetReconnect(jobId);
            return;
        }
        if (status.state() != JobState.PAUSED || status.pauseReason() != PauseReason.CONNECTION_LOST) {
            // Inzwischen fortgesetzt, angehalten oder beendet: dann entscheidet der Benutzer.
            return;
        }
        if (!perform(status, JobAction.RESUME)) {
            int attempt;
            synchronized (lock) {
                attempt = reconnectAttempts.getOrDefault(jobId, 0);
            }
            scheduleReconnect(jobId, attempt);
        }
    }

    private void forgetReconnect(String jobId) {
        synchronized (lock) {
            reconnects.remove(jobId);
            reconnectAttempts.remove(jobId);
        }
    }

    /** @return ob die Aktion ausgeführt wurde */
    private boolean perform(JobStatus status, JobAction action) {
        JobRecovery current = recovery;
        if (current == null) {
            return false;
        }
        try {
            current.perform(status, action);
            return true;
        } catch (UnknownJobException e) {
            forgetReconnect(status.id());
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "{0} für Auftrag {1} nicht ausgeführt ({2})", action, status.id(),
                    e.getClass().getName());
            return false;
        }
    }

    /** Unter dem Sperrobjekt: die Zeitsteuerung, ohne eigene ein Daemon-Thread. */
    private JobTimer timer() {
        if (timer != null) {
            return timer;
        }
        if (ownTimer == null) {
            ownTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "harvester-job-reconnect");
                thread.setDaemon(true);
                return thread;
            });
        }
        java.util.concurrent.ScheduledExecutorService scheduler = ownTimer;
        return (delay, task) -> {
            try {
                scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Der Dienst wird beendet.
            }
        };
    }

    /**
     * „Nur Fehler neu versuchen“ für einen eigenen laufenden Auftrag (Angelo 11.11.2026: ↻ jederzeit; 12.11.2026:
     * sofort, nicht erst nach dem Durchgang). Lässt sich die Art anhalten, hält der Durchgang am nächsten Prüfpunkt an
     * und läuft gleich danach im selben Eintrag neu ({@link JobAction#RETRY_FAILED}); was schon abgelegt oder geladen
     * ist, bleibt. Weiteres Drücken, solange der Neustart aussteht, und bis {@link #RETRY_DEBOUNCE} nach dem Start des
     * neuen Laufs, löst keinen weiteren aus; bis zum Neustart lässt er sich noch anhalten (dann ohne Neustart). Ohne
     * Anhalten (oder ohne angebundene Wiederaufnahme) folgt es wie bisher direkt nach dem Durchgang.
     * Ein angeforderter Abbruch bleibt ein Abbruch; ein angefordertes Anhalten wird zum Neustart.
     *
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException wenn der Auftrag nicht mehr läuft
     */
    public JobStatus requestRetryFailed(String jobId, String owner) {
        requireOwner(status(jobId), owner);
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job == null) {
                throw new JobNotResumableException(jobId, "der Auftrag läuft nicht mehr");
            }
            Instant now = clock.instant();
            if (job.restartForRetry || job.retryRestartedAt != null
                    && now.isBefore(job.retryRestartedAt.plus(RETRY_DEBOUNCE))) {
                // Mehrfach gedrückt: ein Neustart genügt.
                return job.snapshot();
            }
            if (job.cancelRequested && !job.pauseRequested) {
                return job.snapshot();
            }
            if (job.type.resumable() && job.state == JobState.RUNNING && recovery != null) {
                job.retryFailedRequested = true;
                job.restartForRetry = true;
                job.pauseRequested = true;
                job.pauseReason = PauseReason.RETRY;
                job.cancelRequested = true;
                job.stopRequested();
                if (job.stopRequestedAt == null) {
                    job.stopRequestedAt = now;
                    watchStop(job);
                }
                job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                        "Nur Fehler neu versuchen: Durchgang hält an und startet sofort neu", null, null, null, null);
                JobStatus status = job.snapshot();
                store.save(status);
                return status;
            }
            if (!job.retryFailedRequested) {
                job.retryFailedRequested = true;
                job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                        "Nur Fehler neu versuchen angefordert: läuft direkt nach diesem Durchgang", null, null, null,
                        null);
            }
            return job.snapshot();
        }
    }

    /** Unter dem Sperrobjekt: hält einen beendeten Auftrag zum Fortsetzen bereit, wenn Art und Zustand es erlauben. */
    private void keepResumable(Job job) {
        if (!job.detached && job.type.resumable() && resumableState(job.state)) {
            stopped.put(job.id, job);
        }
    }

    private void recordEnd(Job job, JobStatus finished, Throwable cause) {
        JobProgress progress = finished.progress();
        switch (finished.state()) {
            case COMPLETED -> job.emit(true, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                    "Auftrag abgeschlossen: " + progress.processed() + " Seiten verarbeitet, " + progress.written()
                            + " geschrieben, " + progress.failed() + " gescheitert", null, null, null, null);
            case CANCELLED -> job.emit(true, JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, "CANCELLED",
                    "Auftrag abgebrochen", null, null, null, null);
            case PAUSED -> {
                PauseReason reason = finished.pauseReason();
                String why = switch (reason) {
                    case SIGN_IN_REQUIRED -> "Auftrag angehalten, weil die Anmeldung fehlt: ";
                    case CONNECTION_LOST -> "Auftrag angehalten, weil die Quelle nicht erreichbar ist: ";
                    case RESUME_BY_HAND -> "Auftrag nach dem Neustart angehalten, bitte von Hand fortsetzen: ";
                    case USER, RETRY -> job.restartForRetry ? "Durchgang für „Nur Fehler neu versuchen“ angehalten: "
                            : "Auftrag angehalten: ";
                };
                boolean user = reason == PauseReason.USER || reason == PauseReason.RETRY;
                job.emit(true, user ? JobEvent.Severity.INFO : JobEvent.Severity.WARNING,
                        JobEvent.Category.JOB, COMPONENT, user ? "PAUSED" : reason.name(),
                        why + progress.processed() + " Seiten verarbeitet; " + (job.restartForRetry
                                ? "startet sofort neu und holt nur, was noch fehlt"
                                : "Fortsetzen holt nur, was noch fehlt"), null, null, null, null);
            }
            default -> {
                JobError error = finished.error();
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(cause, error.code());
                String route = findings.route();
                if (route == null && (findings.category() == JobEvent.Category.NETWORK
                        || findings.category() == JobEvent.Category.SOURCE)) {
                    try {
                        route = routes.routeOf(job.sourceId).orElse(null);
                    } catch (RuntimeException e) {
                        route = "nicht bestimmbar (" + e.getClass().getSimpleName() + ")";
                    }
                }
                // Gewollte Abbrüche ohne Ursache brauchen keinen Stacktrace; alles andere bekommt die ganze Kette.
                boolean plain = cause instanceof JobFailedException && cause.getCause() == null;
                job.emit(true, JobEvent.Severity.ERROR, findings.category(),
                        findings.component() == null ? job.component : findings.component(), error.code(),
                        error.message(), findings.httpStatus(), route, findings.tlsFinding(),
                        plain ? null : findings.detail());
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Nachrichten unbekannter Ausnahmen können URLs oder Zugangsdaten enthalten und erscheinen deshalb nie. */
    private static JobError errorOf(Throwable cause) {
        if (cause instanceof JobFailedException failed) {
            return new JobError(failed.code(), failed.getMessage());
        }
        if (cause instanceof SourceAccessException source) {
            // Fester Text: Meldungen des Transports können interne URLs, Hosts und Proxy-Routen enthalten.
            return new JobError("SOURCE_" + source.reason().name(),
                    "Die Quelle ist nicht erreichbar oder hat den Abruf abgelehnt (" + source.reason().name() + ")");
        }
        return new JobError(UNEXPECTED, "Unerwarteter Fehler im Auftrag (" + cause.getClass().getSimpleName() + ")");
    }

    /** Veränderlicher Zustand eines offenen Auftrags; Felder außer den Zählern nur unter {@code lock} ändern. */
    private final class Job implements JobContext {
        /** Hintergrund, beim Beenden des Dienstes noch am Laufen: endet als unterbrochen, nicht abgebrochen. */
        volatile boolean shutdownInterrupted;
        /** Ob „wartet, nur ein Platz eingestellt“ schon gemeldet ist. */
        boolean singleSlotNoted;
        /** Ob „wartet auf …“ für die laufende Sperre schon gemeldet ist. */
        boolean waitingNoted;


        final String id;
        final JobType type;
        final String sourceId;
        final String partitionKey;
        final String rootPageId;
        final String owner;
        /** Nur unter dem Sperrobjekt neu gesetzt, bei {@link #rerun(String, String, Map, JobTask)}. */
        volatile Map<String, String> parameters;
        /** Ergebnis unvollständig ({@link JobContext#incomplete()}). */
        volatile boolean incomplete;
        /** Nur unter dem Sperrobjekt neu gesetzt, beim Fortsetzen ({@link #resume(String, String, UnaryOperator)}). */
        volatile JobTask task;
        final Instant createdAt;
        final AtomicLong discovered = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong written = new AtomicLong();
        final AtomicLong failedPages = new AtomicLong();
        volatile JobState state = JobState.QUEUED;
        volatile boolean cancelRequested;
        /** Mit {@link #cancelRequested}: der Benutzer hält an, statt abzubrechen. */
        volatile boolean pauseRequested;
        /** Wann Abbruch oder Anhalten zuerst angefordert wurde, für {@link #STOP_GRACE}. */
        volatile Instant stopRequestedAt;
        /**
         * Die Arbeit ist abgekoppelt: der Eintrag ist beendet oder gelöscht, ihre Ereignisse und ihr Ende zählen nicht
         * mehr. Wird nie zurückgesetzt; Fortsetzen legt dafür einen neuen Lauf an.
         */
        volatile boolean detached;
        /** Warum angehalten wird; {@code null} heißt durch den Benutzer. */
        volatile PauseReason pauseReason;
        /** „Nur Fehler neu versuchen“ ist während des Laufs angefordert und folgt nach dem Durchgang. */
        volatile boolean retryFailedRequested;
        /** Der Durchgang hält für „Nur Fehler neu versuchen“ an und läuft sofort neu ({@link #requestRetryFailed}). */
        volatile boolean restartForRetry;
        /** Der Durchgang hat für „Nur Fehler neu versuchen“ angehalten, der neue Lauf hat noch nicht begonnen. */
        volatile boolean awaitingRetryRestart;
        /** Wann zuletzt ein Lauf für „Nur Fehler neu versuchen“ begann; bleibt über Läufe, gegen Mehrfachdrücken. */
        volatile Instant retryRestartedAt;
        /** Abschnitt eines laufenden Downloads ({@link JobType#download()}), sonst {@code null}. */
        volatile JobStage stage;
        /** Der Auftrag, auf den dieser wartet ({@link Jobs#submitAwaiting}), bis {@link Jobs#releaseAwaiting}. */
        volatile String awaiting;
        /** Wird frei, sobald {@link #awaiting} erfolgreich endet ({@link Jobs#submitAfter}). */
        volatile boolean releaseWhenDone;
        /** Weitere Vorgänger nach {@link #awaiting}, der Reihe nach ({@link Jobs#submitAfter}); nur unter der Sperre. */
        final java.util.ArrayDeque<String> laterAwaited = new java.util.ArrayDeque<>();
        /** Noch nicht startbereit: die Vorbereitung beim Annehmen läuft noch. */
        volatile boolean starting;
        /** Erfüllt, sobald ein vorbereiteter Download seinen Platz hat ({@link #grantDownloads()}). */
        CompletableFuture<Void> slot = new CompletableFuture<>();
        volatile Instant startedAt;
        volatile Instant deadline;
        Instant finishedAt;
        JobError error;
        final AtomicLong sequence = new AtomicLong();
        volatile String component = COMPONENT;
        volatile String phase;
        volatile String rootTitle;

        Job(String id, JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
            Map<String, String> parameters, JobTask task, Instant createdAt) {
            this.parameters = parameters;
            this.id = id;
            this.type = type;
            this.sourceId = sourceId;
            this.partitionKey = partitionKey;
            this.rootPageId = rootPageId;
            this.owner = owner;
            this.task = task;
            this.createdAt = createdAt;
        }

        void finish(JobState end, JobError failure, Instant at) {
            if (pauseReason == PauseReason.RETRY) {
                // Angehalten und abgelegt ist es ein gewöhnliches Anhalten: bleibt der Neustart aus, setzt ▶ fort.
                pauseReason = PauseReason.USER;
            }
            state = end;
            error = failure;
            finishedAt = at;
        }

        /** Unter dem Sperrobjekt: wieder wartend wie neu angenommen, mit leeren Zählern (ADR 0025). */
        void reopen() {
            state = JobState.QUEUED;
            error = null;
            finishedAt = null;
            startedAt = null;
            deadline = null;
            cancelRequested = false;
            pauseRequested = false;
            stopRequestedAt = null;
            pauseReason = null;
            restartForRetry = false;
            stage = null;
            incomplete = false;
            singleSlotNoted = false;
            waitingNoted = false;
            discovered.set(0);
            processed.set(0);
            written.set(0);
            failedPages.set(0);
        }

        JobStatus snapshot() {
            return new JobStatus(id, type, sourceId, partitionKey, owner, state,
                    new JobProgress(discovered.get(), processed.get(), written.get(), failedPages.get()), error,
                    cancelRequested && !state.terminal(), createdAt, startedAt, finishedAt, rootPageId,
                    rootPageId == null ? null : rootTitle, parameters, incomplete && state == JobState.COMPLETED,
                    pauseRequested && cancelRequested && !state.terminal(), pauseReason,
                    state == JobState.QUEUED && awaiting != null ? JobStage.AWAITING : stage);
        }

        @Override
        public void awaitDownloadSlot() {
            CompletableFuture<Void> granted;
            synchronized (lock) {
                if (stage != JobStage.PREPARING || detached) {
                    return;
                }
                checkpoint();
                preparing--;
                stage = JobStage.PREPARED;
                granted = new CompletableFuture<>();
                slot = granted;
                prepared.addLast(this);
                lock.notifyAll();
                store.save(snapshot());
            }
            emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, "Vorbereitet",
                    "Vorbereitet: " + discovered.get() + " Verweise; wartet auf einen Download-Platz", null, null,
                    null, null);
            // Der Platz für Vorbereitungen ist frei; vielleicht gibt es auch schon einen Download-Platz.
            dispatch();
            while (true) {
                if (cancelRequested) {
                    throw new JobCancelledException(id);
                }
                try {
                    granted.get(250, TimeUnit.MILLISECONDS);
                    return;
                } catch (java.util.concurrent.TimeoutException e) {
                    // weiter warten und auf Abbruch prüfen
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobCancelledException(id);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
        }

        @Override
        public void pause(PauseReason reason) {
            synchronized (lock) {
                // Ein angeforderter Abbruch bleibt ein Abbruch.
                if (!cancelRequested || pauseRequested) {
                    pauseReason = reason == null ? PauseReason.USER : reason;
                    pauseRequested = true;
                    cancelRequested = true;
                    stopRequested();
                    // Hält er selbst an, etwa ohne Anmeldung oder Verbindung, gilt das statt eines Neustarts für ↻.
                    if (restartForRetry) {
                        restartForRetry = false;
                        retryFailedRequested = false;
                    }
                }
            }
            throw new JobCancelledException(id);
        }

        @Override
        public void incomplete() {
            this.incomplete = true;
        }

        @Override
        public void rootTitle(String title) {
            this.rootTitle = title;
        }

        @Override
        public String jobId() {
            return id;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public void checkpoint() {
            if (cancelRequested) {
                throw new JobCancelledException(id);
            }
            Instant limit = deadline;
            if (limit != null && clock.instant().isAfter(limit)) {
                throw new JobFailedException(TIMEOUT, "Laufzeitgrenze von " + limits.maxRunTime()
                        + " überschritten");
            }
        }

        @Override
        public boolean cancelRequested() {
            return cancelRequested;
        }

        @Override
        public void discovered(long pages) {
            discovered.addAndGet(pages);
            changed(id, owner);
        }

        @Override
        public void processed(long pages) {
            processed.addAndGet(pages);
            changed(id, owner);
        }

        @Override
        public void written(long pages) {
            written.addAndGet(pages);
            changed(id, owner);
        }

        @Override
        public void failed(long pages) {
            failedPages.addAndGet(pages);
            changed(id, owner);
        }

        @Override
        public void phase(String component, String phase) {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(phase, "phase");
            boolean changed = !phase.equals(this.phase) || !component.equals(this.component);
            this.component = component;
            this.phase = phase;
            if (changed) {
                changed(id, owner);
                emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, component, null, "Phase: " + phase, null, null,
                        null, null);
            }
        }

        @Override
        public void record(JobEvent.Draft event) {
            Objects.requireNonNull(event, "event");
            String detail = null;
            Integer httpStatus = event.httpStatus();
            String tls = event.tlsFinding();
            String route = null;
            if (event.cause() != null) {
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(event.cause(), event.code());
                detail = findings.detail();
                httpStatus = httpStatus == null ? findings.httpStatus() : httpStatus;
                tls = tls == null ? findings.tlsFinding() : tls;
                route = findings.route();
            }
            emit(event.severity(), event.category(), event.component(), event.code(), event.message(), httpStatus,
                    route, tls, detail);
        }

        void emit(JobEvent.Severity severity, JobEvent.Category category, String eventComponent, String code,
                  String message, Integer httpStatus, String proxyRoute, String tlsFinding, String detail) {
            emit(false, severity, category, eventComponent, code, message, httpStatus, proxyRoute, tlsFinding,
                    detail);
        }

        /**
         * @param end das Endereignis: es bekommt immer den letzten freien Platz der Ablage, alle anderen nur bis
         *            {@link JobEventStore#MAX_EVENTS_PER_JOB} − 1
         */
        void emit(boolean end, JobEvent.Severity severity, JobEvent.Category category, String eventComponent,
                  String code, String message, Integer httpStatus, String proxyRoute, String tlsFinding,
                  String detail) {
            // Unter dem Monitor des Auftrags: ein gleichzeitiges Abkoppeln oder Löschen ({@link #detach()}) wartet,
            // bis das Ereignis abgelegt ist, und danach legt keins mehr eins ab.
            synchronized (this) {
                if (detached) {
                    return;
                }
                long current;
                do {
                    current = sequence.get();
                    if (!end && current >= JobEventStore.MAX_EVENTS_PER_JOB - 1) {
                        return;
                    }
                } while (!sequence.compareAndSet(current, current + 1));
                try {
                    JobEvent event = new JobEvent(id, current + 1, clock.instant(), type, sourceId,
                            partitionKey, severity, category, eventComponent, phase, code, message, httpStatus,
                            proxyRoute, tlsFinding, detail);
                    appendQuietly(event);
                    if (!listeners.isEmpty()) {
                        notifier.execute(() -> listeners.deliver(owner, listener -> listener.event(event)));
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Diagnoseereignis nicht erzeugt ({0})",
                            e.getClass().getName());
                }
            }
        }

        /** Koppelt die Arbeit ab; wartet auf ein gerade entstehendes Ereignis ({@link #emit}). */
        synchronized void detach() {
            detached = true;
            stopRequested();
        }

        /** Wer an einer Sperre wartet und bei Abbruch, Anhalten oder Abkoppeln geweckt werden will. */
        private final java.util.List<Runnable> stopListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Weckt die Wartenden ({@link JobContext#whenStopRequested}); jeder Zuhörer läuft höchstens einmal. */
        void stopRequested() {
            for (Runnable listener : stopListeners) {
                if (stopListeners.remove(listener)) {
                    listener.run();
                }
            }
        }

        @Override
        public AutoCloseable whenStopRequested(Runnable listener) {
            java.util.Objects.requireNonNull(listener, "listener");
            stopListeners.add(listener);
            if ((cancelRequested || detached) && stopListeners.remove(listener)) {
                listener.run();
            }
            return () -> stopListeners.remove(listener);
        }
    }

    /** Die Auftragsablage, die jedes Speichern und Löschen zur Meldung vormerkt (ADR 0027). */
    private final class ObservedStore implements JobStore {

        private final JobStore delegate;

        ObservedStore(JobStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(JobStatus status) {
            delegate.save(status);
            changed(status.id(), status.owner());
        }

        @Override
        public Optional<JobStatus> find(String jobId) {
            return delegate.find(jobId);
        }

        @Override
        public List<JobStatus> all() {
            return delegate.all();
        }

        @Override
        public void delete(String jobId) {
            Optional<JobStatus> gone = listeners.isEmpty() ? Optional.empty() : delegate.find(jobId);
            delegate.delete(jobId);
            gone.ifPresent(status -> notifier.mark(jobId, Set.of(new Mark(status.owner(), status))));
        }
    }
}
