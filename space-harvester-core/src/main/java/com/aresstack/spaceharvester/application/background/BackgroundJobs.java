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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Hintergrundaufträge (Angelo 21.11.2026, ADR 0025 Nachtrag „Hintergrundaufträge“): was die App von sich aus anstößt,
 * startet hier als {@link JobType#BACKGROUND}, als Gruppe (der „Oberauftrag“) mit einem Unterauftrag je Space. Jeder
 * Unterauftrag ist ein eigener Auftrag mit eigenem Platz und damit eigenem Kontingent, etwa {@code ai.parallel}; die
 * Gruppe selbst ist nur die gemeinsame Angabe {@value #GROUP} und belegt nichts. {@link Jobs} startet
 * Hintergrundaufträge erst, wenn kein anderer Auftrag wartet, und nie auf allen Plätzen.
 *
 * <p>Die Arbeit eines Unterauftrags baut seine {@link BackgroundKind} allein aus seinen Angaben. Darum setzt
 * {@link #resumeInterrupted()} nach einem Neustart jeden Unterauftrag, den das Beenden des Dienstes oder eine verlorene
 * Verbindung unterbrochen hat, von selbst im selben Eintrag fort. Was der Benutzer angehalten oder abgebrochen hat,
 * läuft nie von selbst weiter.
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
    /** Fehlercode eines vom Beenden des Dienstes unterbrochenen Auftrags ({@code JobLifecycleState.INTERRUPTED}). */
    static final String INTERRUPTED = com.aresstack.spaceharvester.application.jobs.JobLifecycleState.INTERRUPTED;

    private static final System.Logger LOG = System.getLogger(BackgroundJobs.class.getName());

    private final Jobs jobs;
    private final String owner;
    private final Map<String, BackgroundKind> kinds = new ConcurrentHashMap<>();

    /**
     * @param owner Auftraggeber aller Hintergrundaufträge, in der Desktop-App {@code Harvester.OWNER}, damit die
     *              Auftragsliste sie zeigt
     */
    public BackgroundJobs(Jobs jobs, String owner) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.owner = owner;
    }

    /** Meldet eine Art an; eine zweite mit derselben Kennung ersetzt die erste. */
    public void register(BackgroundKind kind) {
        Objects.requireNonNull(kind, "kind");
        kinds.put(kind.id(), kind);
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
                started.add(jobs.submit(JobType.BACKGROUND, draft.sourceId(), draft.partitionKey(), null, owner,
                        parameters, task(false)));
            }
        } catch (RuntimeException e) {
            // Ganz oder gar nicht (wie GlossaryClassification): keine Teile ohne ihre Gruppe.
            for (JobStatus accepted : started) {
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

    /** Eine neue Gruppen-ID für {@link #start(String, String, String, List)}. */
    public static String newGroupId() {
        return UUID.randomUUID().toString();
    }

    /** Ein wartender oder laufender Unterauftrag dieser Art für Quelle und Space, gegen doppelte Anläufe. */
    public Optional<JobStatus> running(String kind, String sourceId, String partitionKey) {
        return all().stream()
                .filter(status -> !status.state().terminal() && kind.equals(status.parameter(KIND))
                        && sourceId.equals(status.sourceId())
                        && Objects.equals(partitionKey, status.partitionKey()))
                .findFirst();
    }

    /** Die Gruppe, solange noch einer ihrer Unteraufträge in der Liste steht. */
    public Optional<BackgroundGroup> group(String groupId) {
        List<JobStatus> parts = all().stream().filter(status -> groupId.equals(status.parameter(GROUP)))
                .sorted(Comparator.comparing(JobStatus::createdAt)).toList();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        JobStatus first = parts.get(0);
        return Optional.of(new BackgroundGroup(groupId, first.parameter(KIND), first.parameter(TITLE), parts));
    }

    /** Alle Gruppen, die jüngste zuerst. */
    public List<BackgroundGroup> groups() {
        Map<String, List<JobStatus>> byGroup = new LinkedHashMap<>();
        all().stream().sorted(Comparator.comparing(JobStatus::createdAt))
                .forEach(status -> byGroup.computeIfAbsent(groupOf(status), key -> new ArrayList<>()).add(status));
        List<BackgroundGroup> result = new ArrayList<>();
        byGroup.forEach((groupId, parts) -> result.add(0, new BackgroundGroup(groupId,
                parts.get(0).parameter(KIND), parts.get(0).parameter(TITLE), parts)));
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
        group(groupId).ifPresent(group -> group.parts().stream()
                .filter(status -> Jobs.resumableState(status.state()))
                .forEach(status -> {
                    try {
                        resumeJob(status.id());
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "Hintergrundauftrag {0} nicht fortgesetzt ({1})",
                                status.id(), e.getClass().getName());
                    }
                }));
        return group(groupId);
    }

    /**
     * Setzt einen Unterauftrag im selben Eintrag fort, mit frisch gebauter Arbeit ({@link BackgroundPart#resumed()}),
     * auch nach einem Neustart; der Weg für „Fortsetzen“ in der Auftragsliste.
     *
     * @throws UnknownJobException      wenn es den Auftrag nicht gibt
     * @throws JobNotResumableException wenn er kein Hintergrundauftrag ist, seine Art fehlt, er wartet, läuft oder
     *                                  fertig ist
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
        if (kind == null || !kinds.containsKey(kind)) {
            throw new JobNotResumableException(jobId, "die Art „" + kind + "“ ist nicht angemeldet");
        }
        return jobs.rerun(jobId, owner, task(true));
    }

    /**
     * Setzt nach dem Start des Dienstes jeden Unterauftrag fort, den das Beenden des Dienstes ({@value #INTERRUPTED})
     * oder eine verlorene Verbindung unterbrochen hat, sofern seine Art angemeldet ist und
     * {@link BackgroundKind#autoResume()} zustimmt. Vom Benutzer Angehaltenes oder Abgebrochenes bleibt stehen.
     *
     * @return die fortgesetzten Aufträge
     */
    public List<JobStatus> resumeInterrupted() {
        return resumeInterrupted(null);
    }

    /**
     * Wie {@link #resumeInterrupted()}, nur für Unteraufträge der Art {@code kind}: für eine Art, die erst nach dem Start
     * des Dienstes arbeiten kann (etwa wenn die App ihre Videos liefert), ohne Teile anderer Arten anzustoßen, die beim
     * Start bewusst liegen geblieben sind.
     *
     * @param kind die Art; {@code null} für alle
     * @return die fortgesetzten Aufträge
     */
    public List<JobStatus> resumeInterrupted(String kind) {
        List<JobStatus> resumed = new ArrayList<>();
        for (JobStatus status : all()) {
            if (!interrupted(status) || kind != null && !kind.equals(status.parameter(KIND))) {
                continue;
            }
            BackgroundKind registered = kinds.get(status.parameter(KIND));
            if (registered == null || !registered.autoResume().getAsBoolean()) {
                continue;
            }
            try {
                resumed.add(jobs.rerun(status.id(), owner, task(true)));
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

    /** Vom Beenden des Dienstes oder von einer verlorenen Verbindung unterbrochen, nicht vom Benutzer. */
    static boolean interrupted(JobStatus status) {
        if (status.state() == JobState.FAILED) {
            return status.error() != null && INTERRUPTED.equals(status.error().code());
        }
        return status.state() == JobState.PAUSED && (status.pauseReason() == PauseReason.CONNECTION_LOST
                || status.pauseReason() == PauseReason.RETRY);
    }

    /**
     * Meldet jede Änderung der Gruppe (Zustand, Zähler, Löschung eines Unterauftrags), höchstens so oft wie die
     * Auftragsmeldungen; am Ende ist {@link BackgroundGroup#finished()} wahr. Ohne Abfragen (ADR 0027).
     */
    public Subscription watch(String groupId, Consumer<BackgroundGroup> listener) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(listener, "listener");
        return jobs.subscribe(owner, new JobListener() {
            @Override
            public void changed(JobStatus status) {
                notify(status);
            }

            @Override
            public void removed(JobStatus last) {
                notify(last);
            }

            private void notify(JobStatus status) {
                if (status != null && groupId.equals(status.parameter(GROUP))) {
                    listener.accept(group(groupId).orElse(new BackgroundGroup(groupId, status.parameter(KIND),
                            status.parameter(TITLE), List.of())));
                }
            }
        });
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

    private static String groupOf(JobStatus status) {
        String group = status.parameter(GROUP);
        return group != null ? group : status.id();
    }

    private BackgroundKind requireKind(String kind) {
        BackgroundKind registered = kind == null ? null : kinds.get(kind);
        if (registered == null) {
            throw new IllegalArgumentException("Hintergrundart „" + kind + "“ ist nicht angemeldet");
        }
        return registered;
    }

    /** Die Arbeit eines Unterauftrags, beim Start aus seinen abgelegten Angaben gebaut. */
    private JobTask task(boolean resumed) {
        return context -> {
            try {
                JobStatus status = jobs.status(context.jobId(), owner);
                BackgroundKind kind = requireKind(status.parameter(KIND));
                Map<String, String> own = new HashMap<>(status.parameters());
                own.keySet().removeIf(key -> key.startsWith(PREFIX));
                BackgroundPart part = new BackgroundPart(status.id(), groupOf(status), kind.id(), status.sourceId(),
                        status.partitionKey(), own, resumed);
                return kind.work().apply(part).start(context);
            } catch (RuntimeException e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        };
    }
}
