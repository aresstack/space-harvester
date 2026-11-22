package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hintergrundaufträge (Angelo 21.11.2026, ADR 0025 Nachtrag „Hintergrundaufträge“): was die App von sich aus anstößt,
 * startet hier als {@link JobType#BACKGROUND}, als Gruppe (der „Oberauftrag“) mit einem Unterauftrag je Space. Jeder
 * Unterauftrag ist ein eigener Auftrag mit eigenem Platz und damit eigenem Kontingent, etwa {@code ai.parallel}; die
 * Gruppe selbst ist nur die gemeinsame Angabe {@value #GROUP} und belegt nichts. {@link Jobs} startet
 * Hintergrundaufträge erst, wenn kein anderer Auftrag wartet, und nie auf allen Plätzen.
 *
 * <p>Die Arbeit eines Unterauftrags baut seine {@link BackgroundKind} allein aus seinen Angaben. Darum setzt
 * {@link #resumeInterrupted()} nach einem Neustart jeden Unterauftrag, den das Beenden des Dienstes oder eine verlorene
 * Verbindung unterbrochen hat, von selbst im selben Eintrag fort, für eine Art mit höchstens einem offenen Teil je
 * Quelle und Space ({@link BackgroundKind#exclusive()}) nur den jüngsten. Was der Benutzer angehalten oder abgebrochen
 * hat, läuft nie von selbst weiter.
 *
 * <p>Beobachter ({@link #watch}) erfahren jede Änderung einer Gruppe über die Auftragsmeldungen (ADR 0027), ohne
 * Abfragen.
 */
public final class BackgroundJobs {

    /** Gemeinsamer Anfang der Angaben dieser Klasse; Arten dürfen ihn nicht verwenden. */
    public static final String PREFIX = "background.";
    /** Angabe: Kennung der {@link BackgroundKind}. */
    public static final String KIND = PREFIX + "kind";
    /** Angabe: Kennung der Gruppe. */
    public static final String GROUP = PREFIX + "group";
    /** Angabe: Titel der Gruppe, wie ihn die Auftragsliste zeigt. */
    public static final String TITLE = PREFIX + "title";
    /** Angabe einer gemischten Gruppe ({@link #startSteps}): die Art des Ziels, nach der die Gruppe heißt. */
    public static final String GROUP_KIND = PREFIX + "groupKind";
    /** Angabe: Titel der Stufe eines Unterauftrags, etwa „Bilder beschreiben“. */
    public static final String STEP_TITLE = PREFIX + "stepTitle";
    /** Code der Warnung: eine Vorstufe ist gescheitert, abgebrochen oder gelöscht; der Teil läuft über das Vorhandene. */
    public static final String PREDECESSOR_MISSING = "PREDECESSOR_MISSING";
    /**
     * Angabe ({@code "true"}) an den Teilen einer Art mit {@link Retry}: Sie wiederholt Gescheitertes gezielt (↻,
     * {@link #retryFailed}); {@code JobLifecycleState} bietet die Aktion dann an.
     */
    public static final String RETRYABLE = com.aresstack.spaceharvester.application.jobs.JobLifecycleState.RETRYABLE;
    /** Fehlercode eines vom Beenden des Dienstes unterbrochenen Auftrags ({@code JobLifecycleState.INTERRUPTED}). */
    static final String INTERRUPTED = com.aresstack.spaceharvester.application.jobs.JobLifecycleState.INTERRUPTED;

    private static final System.Logger LOG = System.getLogger(BackgroundJobs.class.getName());

    private final Jobs jobs;
    private final String owner;
    private final Map<String, BackgroundKind> kinds = new ConcurrentHashMap<>();
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    /** Je Art ein Riegel, damit zwei gleichzeitige Anlässe nicht beide einen Teil starten. */
    private final Map<String, Object> startLocks = new ConcurrentHashMap<>();

    /**
     * @param owner Auftraggeber aller Hintergrundaufträge, in der Desktop-App {@code Harvester.OWNER}, damit die
     *              Auftragsliste sie zeigt
     */
    public BackgroundJobs(Jobs jobs, String owner) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.owner = owner;
        jobs.predecessorGate(new Jobs.PredecessorGate() {
            @Override
            public boolean holds(JobStatus predecessor) {
                return holdsAfter(predecessor);
            }

            @Override
            public boolean holds(JobStatus predecessor, JobStatus follower) {
                // Abnahme #1077: Wartet ein Teil nur der Reihe wegen, hält ihn ein angehaltener Vorgänger einer anderen
                // Quelle nicht auf, denn Anhalten von A darf B nicht stoppen.
                if (predecessor != null && predecessor.state() == JobState.PAUSED && follower != null
                        && "true".equals(follower.parameter(ORDER_ONLY))
                        && !Objects.equals(predecessor.sourceId(), follower.sourceId())) {
                    return false;
                }
                return holdsAfter(predecessor);
            }
        });
    }

    /** D1: ein unterbrochener Vorgänger hält, solange er sich fortsetzen lässt; sonst gibt er frei. */
    private boolean holdsAfter(JobStatus predecessor) {
        if (Jobs.PredecessorGate.DEFAULT.holds(predecessor)) {
            return true;
        }
        if (predecessor == null || predecessor.state() != JobState.FAILED || predecessor.error() == null
                || !INTERRUPTED.equals(predecessor.error().code())) {
            return false;
        }
        BackgroundKind kind = predecessor.type() == JobType.BACKGROUND ? kindOfPart(predecessor) : null;
        return kind != null && kind.autoResume().getAsBoolean();
    }

    /**
     * Ob Hintergrundteile überhaupt laufen können: erst ab zwei Plätzen unter „Vorbereitung“; mit einem wartet jeder
     * gelb ({@link Jobs#SINGLE_SLOT}). Wer sonst einen eigenen Weg hat, nimmt dann den (Abnahme #1077).
     */
    public boolean canRun() {
        return jobs.parallelPreparations() > 1;
    }

    /**
     * Schließt alle Teile dieser Art ab, die sich noch fortsetzen ließen (angehalten oder gescheitert), als abgebrochen
     * mit Info {@code reason} ({@link Jobs#retire}): für Arten, deren Arbeit nur im Speicher liegt und nach dem Neustart
     * fehlt (Abnahme #1077). Wartende und laufende bleiben.
     *
     * @return die abgeschlossenen Teile
     */
    public List<JobStatus> retireResumable(String kind, String reason) {
        return retireResumable(kind, reason, part -> false);
    }

    /**
     * Wie oben, nur ohne die Teile, für die {@code keep} zustimmt: etwa die, deren Arbeit eine Art über den Neustart
     * hinweg abgelegt hat.
     */
    public List<JobStatus> retireResumable(String kind, String reason, java.util.function.Predicate<JobStatus> keep) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(keep, "keep");
        List<JobStatus> retired = new ArrayList<>();
        for (JobStatus part : parts(kind)) {
            if (part.state() != JobState.PAUSED && part.state() != JobState.FAILED || keep.test(part)) {
                continue;
            }
            try {
                jobs.retire(part.id(), owner, reason).ifPresent(retired::add);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Hintergrundauftrag {0} nicht abgeschlossen ({1})", part.id(),
                        e.getClass().getName());
            }
        }
        return retired;
    }

    /** Meldet eine Art an; eine zweite mit derselben Kennung ersetzt die erste. */
    public void register(BackgroundKind kind) {
        Objects.requireNonNull(kind, "kind");
        kinds.put(kind.id(), kind);
    }

    /**
     * Wie eine Art ihre gescheiterte Arbeit wiederholt (↻, Angelo 22.11.2026), im selben Eintrag wie „Fortsetzen“.
     * Die Wiederholung läuft über die gewöhnliche Arbeit der Art ({@link BackgroundKind#work()}), mit allen ihren
     * Prüfungen (etwa der KI-Freigabe je Seite) und Einstellungen.
     */
    public interface Retry {

        /**
         * Legt die gescheiterte Arbeit des Teils als seine neue Arbeit bereit.
         *
         * @return {@code false}, wenn nichts zu wiederholen ist
         */
        boolean prepare(BackgroundPart part);

        /** Der erneute Lauf ist angenommen. */
        default void started(BackgroundPart part) {
        }

        /** Der erneute Lauf wurde nicht angenommen: Die bereitgelegte Arbeit gilt wieder als gescheitert. */
        void abandoned(BackgroundPart part);
    }

    /** Meldet die Wiederholung einer Art an; vor ihren ersten Teilen, die erst damit {@link #RETRYABLE} tragen. */
    public void registerRetry(String kind, Retry retry) {
        Objects.requireNonNull(kind, "kind");
        retries.put(kind, Objects.requireNonNull(retry, "retry"));
    }

    /**
     * „Nur Fehler neu versuchen“ (↻) an einem beendeten oder angehaltenen Teil einer Art mit {@link Retry}: Die Art legt
     * ihre gescheiterte Arbeit bereit, der Teil läuft damit im selben Eintrag neu, die Zähler beginnen neu.
     *
     * @throws NothingToRetryException  wenn die Art nichts mehr zu wiederholen hat
     * @throws JobNotResumableException wenn die Art nichts wiederholt oder der Teil noch wartet oder läuft
     */
    public JobStatus retryFailed(String jobId) {
        JobStatus current = jobs.status(jobId, owner);
        if (current.type() != JobType.BACKGROUND) {
            throw new JobNotResumableException(jobId, "kein Hintergrundauftrag");
        }
        String kind = current.parameter(KIND);
        Retry retry = kind == null ? null : retries.get(kind);
        BackgroundKind registered = kind == null ? null : kinds.get(kind);
        if (retry == null || registered == null) {
            throw new JobNotResumableException(jobId, "diese Art wiederholt Gescheitertes nicht");
        }
        if (!current.state().terminal()) {
            throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
        }
        Map<String, String> own = new HashMap<>(current.parameters());
        own.keySet().removeIf(key -> key.startsWith(PREFIX));
        BackgroundPart part = new BackgroundPart(current.id(), groupOf(current), registered.id(), current.sourceId(),
                current.partitionKey(), own, true, false);
        synchronized (startLock(kind)) {
            if (!retry.prepare(part)) {
                throw new NothingToRetryException(jobId);
            }
            JobStatus rerun;
            try {
                // Hinter die offenen Teile der Art, wie beim ersten Lauf (startBehindOpen, Codex #1109); nie hinter einen,
                // der selbst auf diesen wartet, sonst warteten beide aufeinander.
                List<String> before = openBefore(kind, jobId);
                Map<String, String> parameters = new HashMap<>(current.parameters());
                parameters.remove(Jobs.AFTER);
                parameters.remove(Jobs.AFTER_TITLES);
                if (!before.isEmpty()) {
                    parameters.put(Jobs.AFTER, String.join(",", before));
                    String title = current.parameter(TITLE);
                    if (title != null) {
                        parameters.put(Jobs.AFTER_TITLES, title);
                    }
                }
                rerun = jobs.rerun(jobId, owner, parameters, task(true, false));
            } catch (RuntimeException e) {
                retry.abandoned(part);
                throw e;
            }
            retry.started(part);
            return rerun;
        }
    }

    /** Die offenen Teile der Art außer {@code jobId} und denen, die (auch über andere) auf ihn warten. */
    private List<String> openBefore(String kind, String jobId) {
        List<JobStatus> open = openParts(kind).filter(part -> !part.id().equals(jobId)).toList();
        Set<String> waiting = new HashSet<>();
        waiting.add(jobId);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JobStatus part : open) {
                String after = part.parameter(Jobs.AFTER);
                if (after != null && !waiting.contains(part.id())
                        && java.util.Arrays.stream(after.split(",")).anyMatch(waiting::contains)) {
                    waiting.add(part.id());
                    grew = true;
                }
            }
        }
        return open.stream().map(JobStatus::id).filter(id -> !waiting.contains(id)).toList();
    }

    /** Ob eine Art angemeldet ist. */
    public boolean registered(String kind) {
        return kinds.containsKey(kind);
    }

    /**
     * Startet eine Gruppe mit einem Unterauftrag je Teil, ganz oder gar nicht: Lehnt {@link Jobs} einen Teil ab (voll
     * oder beendet), werden die schon angenommenen abgebrochen und die Ablehnung geht an den Aufrufer.
     *
     * @param title Titel der Gruppe für die Auftragsliste, etwa „Seiten nachladen“
     * @throws IllegalArgumentException wenn die Art nicht angemeldet ist oder keine Teile da sind
     */
    public BackgroundGroup start(String kind, String title, List<BackgroundPart.Draft> parts) {
        return start(UUID.randomUUID().toString(), kind, title, parts);
    }

    /**
     * Wie {@link #start(String, String, List)}, mit vorher vergebener Gruppen-ID ({@link #newGroupId()}): für Arten, die
     * vor dem ersten Lauf etwas zur Gruppe ablegen müssen, denn ein Unterauftrag kann schon während des Starts laufen.
     */
    public BackgroundGroup start(String groupId, String kind, String title, List<BackgroundPart.Draft> parts) {
        Objects.requireNonNull(groupId, "groupId");
        BackgroundKind registered = requireKind(kind);
        Objects.requireNonNull(title, "title");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Teil");
        }
        List<JobStatus> started = new ArrayList<>();
        try {
            for (BackgroundPart.Draft draft : parts) {
                Map<String, String> parameters = new HashMap<>(draft.parameters());
                parameters.put(KIND, registered.id());
                parameters.put(GROUP, groupId);
                parameters.put(TITLE, title);
                if (retries.containsKey(registered.id())) {
                    parameters.put(RETRYABLE, "true");
                }
                if (registered.ownLane()) {
                    parameters.put(Jobs.OWN_LANE, "true");
                }
                started.add(jobs.submit(JobType.BACKGROUND, draft.sourceId(), draft.partitionKey(), null, owner,
                        parameters, task(false, false)));
            }
        } catch (RuntimeException e) {
            // Ganz oder gar nicht (wie GlossaryClassification): keine Teile ohne ihre Gruppe.
            // Nachfolger zuerst, damit keiner dazwischen frei wird und startet (Abnahme D1).
            for (JobStatus accepted : started.reversed()) {
                try {
                    jobs.cancel(accepted.id(), owner);
                } catch (RuntimeException ignored) {
                    // Schon beendet: bleibt, wie er ist.
                }
            }
            throw e;
        }
        LOG.log(System.Logger.Level.INFO, "Hintergrund „{0}“ gestartet: {1} Teile", kind, started.size());
        return new BackgroundGroup(groupId, registered.id(), title, started);
    }

    /**
     * Ein Unterauftrag einer gemischten Gruppe ({@link #startSteps}).
     *
     * @param kind        Art dieses Teils
     * @param stepTitle   Titel der Stufe, etwa „Bilder beschreiben“
     * @param draft       Quelle, Space und Angaben
     * @param afterJobs   IDs schon bestehender Vorgänger, etwa eines wiederverwendeten laufenden Teils
     * @param afterSteps  Stellen früherer Teile dieser Liste, auf die er wartet
     * @param afterTitles Titel der Vorstufen für „wartet auf …“
     */
    public record Step(String kind, String stepTitle, BackgroundPart.Draft draft, List<String> afterJobs,
                       List<Integer> afterSteps, List<String> afterTitles) {

        public Step {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stepTitle, "stepTitle");
            Objects.requireNonNull(draft, "draft");
            afterJobs = List.copyOf(afterJobs);
            afterSteps = List.copyOf(afterSteps);
            afterTitles = List.copyOf(afterTitles);
        }
    }

    /**
     * Startet EINE Gruppe aus Teilen verschiedener Arten (D1, Dienst-Planer), ganz oder gar nicht. Ein Teil startet
     * erst, wenn seine Vorgänger frei sind ({@link Jobs#AFTER}); laufen Teile anderer Spaces, warten sie nicht mit.
     *
     * @param groupKind die Art des Ziels, nach der die Gruppe heißt
     * @throws IllegalArgumentException wenn eine Art nicht angemeldet ist, keine Teile da sind oder ein Teil auf einen
     *                                  späteren wartet
     */
    public BackgroundGroup startSteps(String groupId, String groupKind, String title, List<Step> steps) {
        return startSteps(groupId, groupKind, title, steps, false);
    }

    /** Wie {@link #startSteps(String, String, String, List)}; mit {@code orderOnly} warten die Teile nur der Reihe wegen. */
    private BackgroundGroup startSteps(String groupId, String groupKind, String title, List<Step> steps,
                                       boolean orderOnly) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(groupKind, "groupKind");
        Objects.requireNonNull(title, "title");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Teil");
        }
        for (int i = 0; i < steps.size(); i++) {
            requireKind(steps.get(i).kind());
            for (int before : steps.get(i).afterSteps()) {
                if (before < 0 || before >= i) {
                    throw new IllegalArgumentException("Teil " + i + " wartet auf Teil " + before);
                }
            }
        }
        List<JobStatus> started = new ArrayList<>();
        try {
            for (Step step : steps) {
                Map<String, String> parameters = new HashMap<>(step.draft().parameters());
                parameters.put(KIND, step.kind());
                parameters.put(GROUP, groupId);
                if (retries.containsKey(step.kind())) {
                    parameters.put(RETRYABLE, "true");
                }
                parameters.put(GROUP_KIND, groupKind);
                parameters.put(TITLE, title);
                parameters.put(STEP_TITLE, step.stepTitle());
                if (orderOnly) {
                    parameters.put(ORDER_ONLY, "true");
                }
                List<String> after = new ArrayList<>(step.afterJobs());
                step.afterSteps().forEach(index -> after.add(started.get(index).id()));
                if (!after.isEmpty()) {
                    parameters.put(Jobs.AFTER, String.join(",", after));
                    parameters.put(Jobs.AFTER_TITLES, step.afterTitles().stream().distinct()
                            .map(name -> "„" + name + "“").collect(java.util.stream.Collectors.joining(", ")));
                }
                started.add(jobs.submit(JobType.BACKGROUND, step.draft().sourceId(), step.draft().partitionKey(),
                        null, owner, parameters, task(false, false)));
            }
        } catch (RuntimeException e) {
            // Nachfolger zuerst, damit keiner dazwischen frei wird und startet (Abnahme D1).
            for (JobStatus accepted : started.reversed()) {
                try {
                    jobs.cancel(accepted.id(), owner);
                } catch (RuntimeException ignored) {
                    // Schon beendet: bleibt, wie er ist.
                }
            }
            throw e;
        }
        LOG.log(System.Logger.Level.INFO, "Hintergrund „{0}“ gestartet: {1} Teile", groupKind, started.size());
        java.util.Set<String> outside = outsidePredecessors(started);
        List<JobStatus> predecessors = outside.isEmpty() ? List.of() : closure(started, byId(all()));
        return new BackgroundGroup(groupId, groupKind, title, started, predecessors);
    }

    /** Eine neue Gruppen-ID für {@link #start(String, String, String, List)}. */
    public static String newGroupId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Ein wartender oder laufender Unterauftrag dieser Art für Quelle und Space, gegen doppelte Anläufe. Ein angehaltener
     * zählt nicht: Der Planer ({@code ServicePlanner}) nimmt einen solchen Teil als Vorgänger, und ein angehaltener
     * Teil einer fremden Gruppe ließe das neue Ziel warten, bis jemand jene fortsetzt (Nachprüfung #1052).
     */
    public Optional<JobStatus> running(String kind, String sourceId, String partitionKey) {
        return find(kind, sourceId, partitionKey, state -> !state.terminal());
    }

    /**
     * Ein wartender, angehaltener oder laufender Unterauftrag dieser Art für Quelle und Space ({@link #isOpen}): ein
     * Teil, der seine Arbeit noch tut oder beim Fortsetzen tun wird. Für {@link #startUnlessRunning} und Arten mit
     * höchstens einem offenen Teil ({@link BackgroundKind#exclusive()}), Abnahme #1052.
     */
    public Optional<JobStatus> open(String kind, String sourceId, String partitionKey) {
        return find(kind, sourceId, partitionKey, BackgroundJobs::isOpen);
    }

    /** Wartend, angehalten oder laufend: ein Teil, der seine Arbeit noch tut oder beim Fortsetzen tun wird. */
    private static boolean isOpen(JobState state) {
        return !state.terminal() || state == JobState.PAUSED;
    }

    /** Ein anderer offener Teil dieser Art für Quelle und Space als {@code jobId} ({@link #isOpen}). */
    private Optional<JobStatus> openOther(String kind, String sourceId, String partitionKey, String jobId) {
        return all().stream()
                .filter(status -> !status.id().equals(jobId) && isOpen(status.state())
                        && kind.equals(status.parameter(KIND)) && Objects.equals(sourceId, status.sourceId())
                        && Objects.equals(partitionKey, status.partitionKey()))
                .findFirst();
    }

    /**
     * Ein Unterauftrag dieser Art für Quelle und Space, der noch auf seinen Start wartet ({@link JobState#QUEUED}) oder
     * angehalten ist ({@link JobState#PAUSED}, Abnahme #1052): Ein angehaltener Teil hält neue Anlässe zurück, ihre
     * Arbeit läuft beim Fortsetzen, denn die Art baut sie dann frisch aus Angaben und Bestand. Ein laufender zählt
     * nicht, denn er hat seine Arbeit schon bestimmt.
     */
    public Optional<JobStatus> waiting(String kind, String sourceId, String partitionKey) {
        return find(kind, sourceId, partitionKey, state -> state == JobState.QUEUED || state == JobState.PAUSED);
    }

    private Optional<JobStatus> find(String kind, String sourceId, String partitionKey,
                                     Predicate<JobState> state) {
        return all().stream()
                .filter(status -> state.test(status.state()) && kind.equals(status.parameter(KIND))
                        && sourceId.equals(status.sourceId())
                        && Objects.equals(partitionKey, status.partitionKey()))
                .findFirst();
    }

    /**
     * Startet eine Gruppe mit einem Teil, außer es wartet schon ein Teil derselben Art für Quelle und Space oder ist
     * angehalten ({@link #waiting}): der nimmt dann mit, was der neue Anlass bringt. Für Arten, die ihre Arbeit erst
     * beim Start aus dem Bestand bestimmen, etwa „Synonyme erkennen“ je Quelle; ein laufender Teil hält einen neuen
     * Anlass nicht auf, denn was sich währenddessen ändert, sieht er nicht mehr.
     *
     * @return die neue Gruppe, oder leer, wenn schon ein Teil wartet oder angehalten ist
     */
    public Optional<BackgroundGroup> startUnlessWaiting(String kind, String title, BackgroundPart.Draft part) {
        Objects.requireNonNull(part, "part");
        synchronized (startLock(kind)) {
            if (waiting(kind, part.sourceId(), part.partitionKey()).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(start(kind, title, List.of(part)));
        }
    }

    /**
     * Startet eine Gruppe mit einem Teil, außer ein Teil derselben Art für Quelle und Space wartet, ist angehalten oder
     * läuft schon ({@link #open}): Für Arten, deren laufender Teil sich weitere Arbeit selbst abholt.
     *
     * @return die neue Gruppe, oder leer, wenn schon ein Teil wartet, angehalten ist oder läuft
     */
    public Optional<BackgroundGroup> startUnlessRunning(String kind, String title, BackgroundPart.Draft part) {
        Objects.requireNonNull(part, "part");
        synchronized (startLock(kind)) {
            if (open(kind, part.sourceId(), part.partitionKey()).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(start(kind, title, List.of(part)));
        }
    }

    /**
     * Wie {@link #startUnlessRunning(String, String, BackgroundPart.Draft)} für alle Teile eines Anlasses in einer
     * Gruppe, etwa die Abgleiche beim Start nach {@link #resumeInterrupted}: Teile, für deren Quelle und Space schon
     * einer wartet, angehalten ist oder läuft, fallen heraus.
     *
     * @return die neue Gruppe mit den übrigen Teilen, oder leer, wenn keiner übrig bleibt
     */
    public Optional<BackgroundGroup> startUnlessRunning(String kind, String title, List<BackgroundPart.Draft> parts) {
        Objects.requireNonNull(parts, "parts");
        synchronized (startLock(kind)) {
            List<BackgroundPart.Draft> missing = parts.stream()
                    .filter(part -> open(kind, part.sourceId(), part.partitionKey()).isEmpty()).toList();
            return missing.isEmpty() ? Optional.empty() : Optional.of(start(kind, title, missing));
        }
    }

    /**
     * Wie {@link #startUnlessRunning(String, String, List)}, nur ersetzt ein offener Teil einen neuen allein, wenn er
     * {@code covers} erfüllt (Abnahme #1073): Ein angehaltener Teil, der etwas anderes tut als der neue Anlass, etwa
     * „Freigabe erteilen“ statt „Freigabe prüfen“, hält ihn nicht auf.
     *
     * @param covers ob ein offener Teil derselben Art für Quelle und Space die Arbeit des neuen mitnimmt
     * @return die neue Gruppe mit den übrigen Teilen, oder leer, wenn keiner übrig bleibt
     */
    public Optional<BackgroundGroup> startUnlessRunning(String kind, String title, List<BackgroundPart.Draft> parts,
                                                        Predicate<JobStatus> covers) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(covers, "covers");
        synchronized (startLock(kind)) {
            List<BackgroundPart.Draft> missing = parts.stream()
                    .filter(part -> openParts(kind).noneMatch(status -> covers.test(status)
                            && Objects.equals(part.sourceId(), status.sourceId())
                            && Objects.equals(part.partitionKey(), status.partitionKey()))).toList();
            return missing.isEmpty() ? Optional.empty() : Optional.of(start(kind, title, missing));
        }
    }

    /**
     * Wie {@link #startUnlessRunning(String, String, List)}, die Teile aber nacheinander ({@link Jobs#AFTER}, Abnahme
     * #1073): Jeder wartet auf den vorigen, der erste auf jeden schon offenen Teil der Art, etwa einen nach dem Neustart
     * fortgesetzten. Für Arten, deren Teile sich sonst gegenseitig aufhielten und dabei Plätze belegten. Ein Teil gilt
     * hier nur als Glied der Reihe: Bricht der vorige ab, läuft er ohne gelben Hinweis ({@value #ORDER_ONLY}).
     *
     * @return die neue Gruppe mit den übrigen Teilen, oder leer, wenn keiner übrig bleibt
     */
    public Optional<BackgroundGroup> startInSequenceUnlessRunning(String kind, String title,
                                                                  List<BackgroundPart.Draft> parts) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(title, "title");
        synchronized (startLock(kind)) {
            List<BackgroundPart.Draft> missing = parts.stream()
                    .filter(part -> open(kind, part.sourceId(), part.partitionKey()).isEmpty()).toList();
            if (missing.isEmpty()) {
                return Optional.empty();
            }
            List<String> openIds = openParts(kind).map(JobStatus::id).toList();
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < missing.size(); i++) {
                BackgroundPart.Draft draft = missing.get(i);
                List<String> afterJobs = i == 0 ? openIds : List.of();
                List<Integer> afterSteps = i == 0 ? List.of() : List.of(i - 1);
                steps.add(new Step(kind, title, draft, afterJobs, afterSteps,
                        afterJobs.isEmpty() && afterSteps.isEmpty() ? List.of() : List.of(title)));
            }
            return Optional.of(startSteps(newGroupId(), kind, title, steps, true));
        }
    }

    /**
     * Startet eine Gruppe mit einem Teil hinter jedem schon offenen Teil der Art ({@link Jobs#AFTER}, nur der Reihe
     * wegen, {@value #ORDER_ONLY}): Für Arten, deren Teile eine Arbeit nacheinander abholen, die erst nach dem Start
     * dazukommt, etwa die KI-Anreicherung je Quelle. Ein offener Teil derselben Quelle verhindert ihn nicht; ob es einen
     * braucht, entscheidet der Aufrufer. Ein angehaltener Vorgänger hält nur Teile seiner eigenen Quelle auf.
     *
     * @param groupId vorher vergeben ({@link #newGroupId()}), denn der Teil kann schon während des Starts laufen
     */
    public BackgroundGroup startBehindOpen(String groupId, String kind, String title, BackgroundPart.Draft part) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(title, "title");
        synchronized (startLock(kind)) {
            List<String> openIds = openParts(kind).map(JobStatus::id).toList();
            return startSteps(groupId, kind, title, List.of(new Step(kind, title, part, openIds, List.of(),
                    openIds.isEmpty() ? List.of() : List.of(title))), true);
        }
    }

    /**
     * Angabe eines Teils, der nur der Reihenfolge wegen auf seinen Vorgänger wartet
     * ({@link #startInSequenceUnlessRunning}): Dessen Ergebnis braucht er nicht.
     */
    public static final String ORDER_ONLY = PREFIX + "orderOnly";

    /** Angabe eines Teils: sein Anlass, {@value #USER} für einen Lauf, den ein Benutzer startete. */
    public static final String TRIGGER = "trigger";
    /** Anlass eines Teils, den ein Benutzer startete. */
    public static final String USER = "user";
    /** Angaben eines Teils, die seinen Benutzer nennen: Eigentümer, Benutzer oder Start-Aufrufer. */
    private static final List<String> STARTER = List.of("owner", "principal", "caller");

    /**
     * Ob ein Benutzer diesen Teil gestartet hat (Abnahme #1073): Anlass {@value #USER} ({@value #TRIGGER}) oder ein
     * Eigentümer, Benutzer oder Start-Aufrufer in den Angaben ({@code owner}, {@code principal}, {@code caller}), wie
     * bei „Bilder beschreiben“, der Aussagenanalyse und jedem Teil des Dienst-Planers. Automatik und Abgleiche beim
     * Start tragen nichts davon. Wer die Laufzeit ersetzt, fragt nur für solche Teile nach, denn mit dem Aufrufer im
     * Speicher verlieren sie ihre Rechte und halten danach gelb „von Hand fortsetzen“ an.
     */
    public static boolean startedByUser(JobStatus part) {
        Objects.requireNonNull(part, "part");
        return part.type() == JobType.BACKGROUND && (USER.equals(part.parameter(TRIGGER))
                || STARTER.stream().anyMatch(key -> part.parameter(key) != null));
    }

    /** Ob ein Unterauftrag wartet, angehalten ist oder läuft, also seine Arbeit noch tun wird. */
    public static boolean open(JobStatus part) {
        return isOpen(part.state());
    }

    /** Alle Teile dieser Art, älteste zuerst, in jedem Zustand; etwa für Teile, die der Start zu Ende führt. */
    public List<JobStatus> parts(String kind) {
        Objects.requireNonNull(kind, "kind");
        return all().stream().filter(status -> kind.equals(status.parameter(KIND)))
                .sorted(Comparator.comparing(JobStatus::createdAt)).toList();
    }

    private java.util.stream.Stream<JobStatus> openParts(String kind) {
        return all().stream().filter(status -> isOpen(status.state()) && kind.equals(status.parameter(KIND)));
    }

    private Object startLock(String kind) {
        return startLocks.computeIfAbsent(Objects.requireNonNull(kind, "kind"), ignored -> new Object());
    }

    /**
     * Bricht die noch wartenden Unteraufträge dieser Art für Quelle und Space ab, mit gelbem Grund (etwa „durch Klick
     * ersetzt“); laufende bleiben unberührt ({@link Jobs#cancelIfWaiting}).
     *
     * @param partitionKey ein Space, oder {@code null} für alle Spaces der Quelle
     * @return die abgebrochenen Unteraufträge
     */
    public List<JobStatus> cancelWaiting(String kind, String sourceId, String partitionKey, String reason) {
        List<JobStatus> cancelled = new ArrayList<>();
        all().stream()
                .filter(status -> status.state() == JobState.QUEUED && kind.equals(status.parameter(KIND))
                        && sourceId.equals(status.sourceId())
                        && (partitionKey == null || partitionKey.equals(status.partitionKey())))
                .forEach(status -> {
                    try {
                        jobs.cancelIfWaiting(status.id(), owner, reason).ifPresent(cancelled::add);
                    } catch (UnknownJobException e) {
                        // inzwischen gelöscht
                    }
                });
        return cancelled;
    }

    /** Ein Unterauftrag dieses Hintergrunds; leer, wenn es ihn nicht (mehr) gibt oder er keiner ist. */
    public Optional<JobStatus> part(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        try {
            JobStatus status = jobs.status(jobId, owner);
            return status.type() == JobType.BACKGROUND ? Optional.of(status) : Optional.empty();
        } catch (UnknownJobException e) {
            return Optional.empty();
        }
    }

    /**
     * Bricht diesen Unterauftrag ab, solange er noch wartet, mit gelbem Grund ({@link Jobs#cancelIfWaiting}); ein
     * laufender bleibt unberührt.
     *
     * @return der abgebrochene Unterauftrag, leer wenn er nicht mehr wartete oder fehlt
     */
    public Optional<JobStatus> cancelIfWaiting(String jobId, String reason) {
        try {
            return jobs.cancelIfWaiting(jobId, owner, reason);
        } catch (UnknownJobException e) {
            return Optional.empty();
        }
    }

    /**
     * Die Gruppe, solange noch einer ihrer Unteraufträge in der Liste steht, mit den Aufträgen außerhalb, auf die einer
     * ihrer Teile wartet ({@link BackgroundGroup#predecessors()}).
     */
    public Optional<BackgroundGroup> group(String groupId) {
        List<JobStatus> all = all();
        List<JobStatus> parts = all.stream().filter(status -> groupId.equals(status.parameter(GROUP)))
                .sorted(Comparator.comparing(JobStatus::createdAt)).toList();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        JobStatus first = parts.get(0);
        return Optional.of(new BackgroundGroup(groupId, kindOf(first), first.parameter(TITLE), parts,
                closure(parts, byId(all))));
    }

    private static Map<String, JobStatus> byId(List<JobStatus> all) {
        Map<String, JobStatus> byId = new HashMap<>();
        all.forEach(status -> byId.put(status.id(), status));
        return byId;
    }

    /**
     * Die Vorgänger außerhalb von {@code parts}, auch mittelbar: Wartet ein wiederverwendeter Vorgänger selbst auf einen
     * Teil seiner Gruppe (etwa „Aussagenanalyse“ hinter „Bilder beschreiben“), gehört der dazu, denn hält man ihn an,
     * steht auch {@code parts} (Nacharbeit Codex #1081). Gelöschte fehlen.
     */
    private static List<JobStatus> closure(List<JobStatus> parts, Map<String, JobStatus> byId) {
        java.util.Set<String> own = new java.util.HashSet<>();
        parts.forEach(part -> own.add(part.id()));
        Map<String, JobStatus> found = new LinkedHashMap<>();
        java.util.Deque<JobStatus> todo = new java.util.ArrayDeque<>(parts);
        while (!todo.isEmpty()) {
            for (String id : afterIds(todo.removeFirst())) {
                JobStatus predecessor = byId.get(id);
                if (!own.contains(id) && predecessor != null && found.putIfAbsent(id, predecessor) == null) {
                    todo.addLast(predecessor);
                }
            }
        }
        return List.copyOf(found.values());
    }

    private static List<String> afterIds(JobStatus status) {
        String after = status.parameter(Jobs.AFTER);
        if (after == null || after.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(after.split(",")).filter(id -> !id.isBlank()).toList();
    }

    /** Die Kennungen der Vorgänger ({@link Jobs#AFTER}) von {@code parts}, die nicht selbst zu {@code parts} gehören. */
    private static java.util.Set<String> outsidePredecessors(List<JobStatus> parts) {
        java.util.Set<String> own = new java.util.HashSet<>();
        parts.forEach(part -> own.add(part.id()));
        java.util.Set<String> outside = new java.util.LinkedHashSet<>();
        for (JobStatus part : parts) {
            String after = part.parameter(Jobs.AFTER);
            if (after == null || after.isBlank()) {
                continue;
            }
            for (String id : after.split(",")) {
                if (!id.isBlank() && !own.contains(id)) {
                    outside.add(id);
                }
            }
        }
        return outside;
    }

    /** Alle Gruppen, die jüngste zuerst, je mit ihren Vorgängern außerhalb ({@link BackgroundGroup#predecessors()}). */
    public List<BackgroundGroup> groups() {
        List<JobStatus> all = all();
        Map<String, JobStatus> byId = byId(all);
        Map<String, List<JobStatus>> byGroup = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(JobStatus::createdAt))
                .forEach(status -> byGroup.computeIfAbsent(groupOf(status), key -> new ArrayList<>()).add(status));
        List<BackgroundGroup> result = new ArrayList<>();
        byGroup.forEach((groupId, parts) -> result.add(0, new BackgroundGroup(groupId,
                kindOf(parts.get(0)), parts.get(0).parameter(TITLE), parts, closure(parts, byId))));
        return result;
    }

    /** Hält jeden wartenden oder laufenden Unterauftrag der Gruppe an; Erledigtes bleibt. */
    public Optional<BackgroundGroup> pause(String groupId) {
        forEachOpen(groupId, status -> jobs.pause(status.id(), owner));
        return group(groupId);
    }

    /** Bricht jeden wartenden oder laufenden Unterauftrag der Gruppe ab; Erledigtes bleibt. */
    public Optional<BackgroundGroup> cancel(String groupId) {
        forEachOpen(groupId, status -> jobs.cancel(status.id(), owner));
        return group(groupId);
    }

    /** Setzt jeden angehaltenen, gescheiterten oder abgebrochenen Unterauftrag der Gruppe fort. */
    public Optional<BackgroundGroup> resume(String groupId) {
        // Älteste zuerst; lässt sich ein Vorgänger nicht fortsetzen, bleiben seine Nachfolger stehen, statt frei zu
        // werden (Abnahme D1).
        java.util.Set<String> notResumed = new java.util.HashSet<>();
        group(groupId).ifPresent(group -> group.parts().stream()
                .filter(status -> Jobs.resumableState(status.state()))
                .forEach(status -> {
                    String after = status.parameter(Jobs.AFTER);
                    if (after != null && java.util.Arrays.stream(after.split(",")).anyMatch(notResumed::contains)) {
                        notResumed.add(status.id());
                        return;
                    }
                    try {
                        resumeJob(status.id());
                    } catch (RuntimeException e) {
                        notResumed.add(status.id());
                        LOG.log(System.Logger.Level.WARNING, "Hintergrundauftrag {0} nicht fortgesetzt ({1})",
                                status.id(), e.getClass().getName());
                    }
                }));
        return group(groupId);
    }

    /**
     * Setzt einen Unterauftrag im selben Eintrag fort, mit frisch gebauter Arbeit ({@link BackgroundPart#resumed()}),
     * auch nach einem Neustart; der Weg für „Fortsetzen“ in der Auftragsliste. Für eine Art mit höchstens einem offenen
     * Teil je Quelle und Space ({@link BackgroundKind#exclusive()}) nur, solange kein anderer wartet, angehalten ist oder
     * läuft (Abnahme #1052), geprüft unter dem Startriegel der Art.
     *
     * @throws UnknownJobException          wenn es den Auftrag nicht gibt
     * @throws BackgroundPartOpenException  wenn für eine solche Art schon ein anderer Teil offen ist („läuft schon“)
     * @throws JobNotResumableException     wenn er kein Hintergrundauftrag ist, seine Art fehlt, er wartet, läuft oder
     *                                      fertig ist
     */
    public JobStatus resumeJob(String jobId) {
        JobStatus current = jobs.status(jobId, owner);
        if (current.type() != JobType.BACKGROUND) {
            throw new JobNotResumableException(jobId, "kein Hintergrundauftrag");
        }
        if (!Jobs.resumableState(current.state())) {
            throw new JobNotResumableException(jobId, current.state().terminal() ? "der Auftrag ist fertig"
                    : "der Auftrag wartet oder läuft noch");
        }
        String kind = current.parameter(KIND);
        BackgroundKind registered = kind == null ? null : kinds.get(kind);
        if (registered == null) {
            throw new JobNotResumableException(jobId, "die Art „" + kind + "“ ist nicht angemeldet");
        }
        if (!registered.exclusive()) {
            return jobs.rerun(jobId, owner, task(true, false));
        }
        synchronized (startLock(kind)) {
            Optional<JobStatus> open = openOther(kind, current.sourceId(), current.partitionKey(), jobId);
            if (open.isPresent()) {
                throw new BackgroundPartOpenException(jobId, open.get().id());
            }
            return jobs.rerun(jobId, owner, task(true, false));
        }
    }

    /** Art, Quelle und Space eines Teils, für den jüngsten unterbrochenen je Space. */
    private record PartKey(String kind, String sourceId, String partitionKey) {

        static PartKey of(JobStatus status) {
            return new PartKey(status.parameter(KIND), status.sourceId(), status.partitionKey());
        }
    }

    /** Info an einem älteren unterbrochenen Teil, den {@link #resumeInterrupted} abschließt. */
    public static final String SUPERSEDED_BY_YOUNGER = "Durch jüngeren Teil ersetzt: er nimmt die Arbeit mit";
    /** Info an unterbrochenen Teilen, deren Arbeit ein anderer offener Teil mitnimmt. */
    public static final String SUPERSEDED_BY_OPEN = "Durch einen anderen offenen Teil ersetzt: er nimmt die Arbeit mit";

    /**
     * Setzt nach dem Start des Dienstes jeden Unterauftrag fort, den das Beenden des Dienstes ({@value #INTERRUPTED})
     * oder eine verlorene Verbindung unterbrochen hat, sofern seine Art angemeldet ist und
     * {@link BackgroundKind#autoResume()} zustimmt. Vom Benutzer Angehaltenes oder Abgebrochenes bleibt stehen.
     * <p>
     * Für eine Art mit höchstens einem offenen Teil je Quelle und Space ({@link BackgroundKind#exclusive()}) nur den
     * jüngsten unterbrochenen (Abnahme #1052): Seine Arbeit kommt aus dem Bestand und deckt die der älteren mit ab. Die
     * älteren schließt er als abgebrochen ab ({@link Jobs#retire}, Info {@value #SUPERSEDED_BY_YOUNGER}), sonst liefen
     * sie nach einem späteren Neustart von selbst los, auch wenn der Benutzer den jüngeren inzwischen abgebrochen hat.
     * Ist schon ein anderer Teil offen, nimmt der die Arbeit mit; dann werden alle unterbrochenen so abgeschlossen
     * ({@value #SUPERSEDED_BY_OPEN}).
     *
     * @return die fortgesetzten Aufträge
     */
    public List<JobStatus> resumeInterrupted() {
        List<JobStatus> resumed = new ArrayList<>();
        // Älteste zuerst: Vorgänger einer Kette sind wieder offen, bevor ihre Nachfolger fortsetzen (D1).
        List<JobStatus> oldestFirst = all().stream().sorted(Comparator.comparing(JobStatus::createdAt)).toList();
        Map<PartKey, List<JobStatus>> exclusiveParts = new HashMap<>();
        for (JobStatus status : oldestFirst) {
            BackgroundKind kind = kindOfPart(status);
            if (interrupted(status) && kind != null && kind.exclusive()) {
                exclusiveParts.computeIfAbsent(PartKey.of(status), ignored -> new ArrayList<>()).add(status);
            }
        }
        for (JobStatus status : oldestFirst) {
            if (!interrupted(status)) {
                continue;
            }
            BackgroundKind kind = kindOfPart(status);
            if (kind == null || !kind.autoResume().getAsBoolean()) {
                continue;
            }
            try {
                if (!kind.exclusive()) {
                    resumed.add(jobs.rerun(status.id(), owner, task(true, true)));
                    continue;
                }
                List<JobStatus> siblings = exclusiveParts.get(PartKey.of(status));
                if (!status.id().equals(siblings.getLast().id())) {
                    // Der jüngste derselben Art, Quelle und Space entscheidet über die älteren.
                    continue;
                }
                resumeYoungest(kind, siblings).ifPresent(resumed::add);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Hintergrundauftrag {0} nach dem Neustart nicht fortgesetzt "
                        + "({1})", status.id(), e.getClass().getName());
            }
        }
        if (!resumed.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Hintergrund nach dem Neustart fortgesetzt: {0} Aufträge",
                    resumed.size());
        }
        return resumed;
    }

    /**
     * Für {@link #resumeInterrupted}: setzt den jüngsten der unterbrochenen Teile einer exklusiven Art für Quelle und
     * Space fort und schließt die älteren ab; ist ein anderer Teil offen, schließt es alle ab.
     *
     * @param siblings die unterbrochenen Teile, älteste zuerst
     */
    private Optional<JobStatus> resumeYoungest(BackgroundKind kind, List<JobStatus> siblings) {
        JobStatus youngest = siblings.getLast();
        List<JobStatus> older = siblings.subList(0, siblings.size() - 1);
        Set<String> interruptedIds = new HashSet<>();
        siblings.forEach(sibling -> interruptedIds.add(sibling.id()));
        synchronized (startLock(kind.id())) {
            Optional<JobStatus> open = all().stream()
                    .filter(other -> !interruptedIds.contains(other.id()) && isOpen(other.state())
                            && kind.id().equals(other.parameter(KIND))
                            && Objects.equals(youngest.sourceId(), other.sourceId())
                            && Objects.equals(youngest.partitionKey(), other.partitionKey()))
                    .findFirst();
            if (open.isPresent()) {
                LOG.log(System.Logger.Level.INFO, "Hintergrundaufträge {0} bleiben stehen: Teil {1} derselben Art für "
                        + "Quelle und Space ist offen", interruptedIds, open.get().id());
                siblings.forEach(sibling -> retire(sibling, SUPERSEDED_BY_OPEN));
                return Optional.empty();
            }
            JobStatus resumed = jobs.rerun(youngest.id(), owner, task(true, true));
            older.forEach(sibling -> retire(sibling, SUPERSEDED_BY_YOUNGER));
            return Optional.of(resumed);
        }
    }

    private void retire(JobStatus part, String reason) {
        try {
            jobs.retire(part.id(), owner, reason);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hintergrundauftrag {0} nicht abgeschlossen ({1})", part.id(),
                    e.getClass().getName());
        }
    }

    /** Vom Beenden des Dienstes oder von einer verlorenen Verbindung unterbrochen, nicht vom Benutzer. */
    public static boolean interrupted(JobStatus status) {
        if (status.state() == JobState.FAILED) {
            return status.error() != null && INTERRUPTED.equals(status.error().code());
        }
        return status.state() == JobState.PAUSED && (status.pauseReason() == PauseReason.CONNECTION_LOST
                || status.pauseReason() == PauseReason.RETRY);
    }

    /**
     * Meldet jede Änderung und Löschung eines Unterauftrags dieser Art (ADR 0027), ohne Abfragen: etwa für eine Karte
     * der offenen Teile, die nie den ganzen Auftragsspeicher liest.
     */
    public Subscription subscribe(String kind, Consumer<JobStatus> changed, Consumer<JobStatus> removed) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(changed, "changed");
        Objects.requireNonNull(removed, "removed");
        return jobs.subscribe(owner, new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (status != null && kind.equals(status.parameter(KIND))) {
                    changed.accept(status);
                }
            }

            @Override
            public void removed(JobStatus last) {
                if (last != null && kind.equals(last.parameter(KIND))) {
                    removed.accept(last);
                }
            }
        });
    }

    /**
     * Meldet jede Änderung der Gruppe (Zustand, Zähler, Löschung eines Unterauftrags), höchstens so oft wie die
     * Auftragsmeldungen; am Ende ist {@link BackgroundGroup#finished()} wahr. Ohne Abfragen (ADR 0027).
     */
    public Subscription watch(String groupId, Consumer<BackgroundGroup> listener) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(listener, "listener");
        // Auch ein Vorgänger außerhalb der Gruppe (eine wiederverwendete Vorstufe, Jobs.AFTER) ändert, worauf ein
        // wartender Teil steht, etwa wenn er angehalten wird: dann meldet sich die Gruppe ebenfalls. Welche das sind,
        // weiß jede Meldung der Gruppe neu; die erste Menge kommt beim Anmelden.
        java.util.Set<String> unknown = new java.util.HashSet<>();
        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> outside =
                new java.util.concurrent.atomic.AtomicReference<>(unknown);
        Subscription subscription = jobs.subscribe(owner, new JobListener() {
            @Override
            public void changed(JobStatus status) {
                notify(status);
            }

            @Override
            public void removed(JobStatus last) {
                notify(last);
            }

            private void notify(JobStatus status) {
                if (status != null && (groupId.equals(status.parameter(GROUP))
                        || outside.get().contains(status.id()))) {
                    BackgroundGroup group = group(groupId).orElse(new BackgroundGroup(groupId,
                            groupId.equals(status.parameter(GROUP)) ? status.parameter(KIND) : null,
                            groupId.equals(status.parameter(GROUP)) ? status.parameter(TITLE) : null, List.of()));
                    outside.set(predecessorIds(group));
                    listener.accept(group);
                }
            }
        });
        group(groupId).ifPresent(group -> outside.compareAndSet(unknown, predecessorIds(group)));
        return subscription;
    }

    /** Die Vorgänger außerhalb, auch mittelbare ({@link #closure}): Auch ihr Anhalten ändert, worauf die Gruppe steht. */
    private static java.util.Set<String> predecessorIds(BackgroundGroup group) {
        java.util.Set<String> ids = new java.util.HashSet<>(outsidePredecessors(group.parts()));
        group.predecessors().forEach(predecessor -> ids.add(predecessor.id()));
        return ids;
    }

    /**
     * Benachrichtigt einmal, sobald die Gruppe fertig ist (kein Unterauftrag wartet oder läuft mehr; Angelo
     * 21.11.2026): etwa um bis dahin den drehenden Ring zu zeigen. Ist sie schon fertig oder nicht mehr in der Liste,
     * kommt die Meldung sofort. Ohne Abfragen (ADR 0027); {@link Subscription#close()} zieht das Abonnement zurück.
     */
    public Subscription whenFinished(String groupId, Consumer<BackgroundGroup> listener) {
        Objects.requireNonNull(listener, "listener");
        java.util.concurrent.atomic.AtomicBoolean told = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Subscription> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        Consumer<BackgroundGroup> once = group -> {
            if (group.finished() && told.compareAndSet(false, true)) {
                Subscription subscription = holder.get();
                if (subscription != null) {
                    subscription.close();
                }
                listener.accept(group);
            }
        };
        Subscription subscription = watch(groupId, once);
        holder.set(subscription);
        // Nach dem Anmelden nachsehen: was schon fertig ist, meldet sich nicht mehr von selbst.
        once.accept(group(groupId).orElse(new BackgroundGroup(groupId, null, null, List.of())));
        if (told.get()) {
            subscription.close();
        }
        return subscription;
    }

    /** Alle Hintergrundaufträge des Auftraggebers. */
    private List<JobStatus> all() {
        return jobs.list(owner, JobType.BACKGROUND);
    }

    private void forEachOpen(String groupId, Consumer<JobStatus> action) {
        group(groupId).ifPresent(group -> group.parts().stream().filter(status -> !status.state().terminal())
                .forEach(status -> {
                    try {
                        action.accept(status);
                    } catch (UnknownJobException | JobNotResumableException e) {
                        // inzwischen beendet oder gelöscht
                    }
                }));
    }

    /** Die Art der Gruppe: bei einer gemischten die des Ziels. */
    private static String kindOf(JobStatus status) {
        String kind = status.parameter(GROUP_KIND);
        return kind != null ? kind : status.parameter(KIND);
    }

    private static String groupOf(JobStatus status) {
        String group = status.parameter(GROUP);
        return group != null ? group : status.id();
    }

    /** Die angemeldete Art eines Teils, {@code null} ohne Angabe oder für eine nicht angemeldete. */
    private BackgroundKind kindOfPart(JobStatus status) {
        String kind = status.parameter(KIND);
        return kind == null ? null : kinds.get(kind);
    }

    private BackgroundKind requireKind(String kind) {
        BackgroundKind registered = kind == null ? null : kinds.get(kind);
        if (registered == null) {
            throw new IllegalArgumentException("Hintergrundart „" + kind + "“ ist nicht angemeldet");
        }
        return registered;
    }

    /**
     * Meldet gelb, wenn eine Vorstufe gescheitert, abgebrochen, nicht fortsetzbar unterbrochen oder gelöscht ist: der
     * Teil läuft trotzdem, über das, was vorhanden ist (D1).
     */
    private void notePredecessors(JobStatus status, com.aresstack.spaceharvester.application.jobs.JobContext context) {
        String after = status.parameter(Jobs.AFTER);
        if (after == null || after.isBlank() || "true".equals(status.parameter(ORDER_ONLY))) {
            return;
        }
        for (String id : after.split(",")) {
            if (id.isBlank()) {
                continue;
            }
            String reason;
            String name;
            try {
                JobStatus predecessor = jobs.status(id, owner);
                name = predecessor.parameter(STEP_TITLE) != null ? predecessor.parameter(STEP_TITLE)
                        : predecessor.parameter(TITLE);
                reason = switch (predecessor.state()) {
                    case FAILED -> "gescheitert";
                    case CANCELLED -> "abgebrochen";
                    default -> null;
                };
            } catch (UnknownJobException e) {
                name = null;
                reason = "gelöscht, ihr Ergebnis ist unbekannt";
            }
            if (reason != null) {
                context.record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.warning(
                        com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, "background",
                        PREDECESSOR_MISSING, "Vorstufe " + (name == null ? "" : "„" + name + "“ ") + reason
                                + "; läuft über das Vorhandene"));
            }
        }
    }

    /**
     * Die Arbeit eines Unterauftrags, beim Start aus seinen abgelegten Angaben gebaut.
     *
     * @param automatic von {@link #resumeInterrupted()} fortgesetzt, nicht vom Benutzer
     */
    private JobTask task(boolean resumed, boolean automatic) {
        return context -> {
            try {
                JobStatus status = jobs.status(context.jobId(), owner);
                BackgroundKind kind = requireKind(status.parameter(KIND));
                Map<String, String> own = new HashMap<>(status.parameters());
                own.keySet().removeIf(key -> key.startsWith(PREFIX));
                BackgroundPart part = new BackgroundPart(status.id(), groupOf(status), kind.id(), status.sourceId(),
                        status.partitionKey(), own, resumed, automatic);
                notePredecessors(status, context);
                return kind.work().apply(part).start(context);
            } catch (RuntimeException e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        };
    }
}
