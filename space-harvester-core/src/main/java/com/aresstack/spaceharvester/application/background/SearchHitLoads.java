package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * „{@value #TITLE}“ als Hintergrundart {@value #KIND} (Fundstelle 13, Angelo 21.11.2026: nichts läuft unsichtbar):
 * Treffer einer Schlagwortsuche, die nach ihrer Rückkehr weiterladen, je Quelle und Space ein Teil.
 *
 * <ul>
 *   <li>Die Abrufe der Suche bleiben, wie sie sind (Vordergrund, Platz im Planer); der Teil übernimmt sie, wartet über
 *       ihre Rückrufe, ohne Thread (ADR 0027), und zählt sie. Er rechnet selbst nichts, darum läuft er auf eigener
 *       Spur ({@link BackgroundKind#ownLane()}) und belegt keinen Platz unter „Vorbereitung“.</li>
 *   <li>{@link #offer} kehrt sofort zurück: Die Teile legt ein eigener Arbeiter an ({@link Coalescer}). Seiten, die
 *       ein offener Teil schon zählt, fallen heraus; die offenen Teile führt eine Karte aus den Auftragsmeldungen, nie
 *       ein Durchlauf über den Auftragsspeicher.</li>
 *   <li>Vorübergehend gescheiterte Seiten fordert der Planer selbst erneut an, ohne Netz wartet er (der Teil steht
 *       dann als „Wartet auf Netzverbindung“, {@link #network}). Was danach noch scheitert, zählt rot mit Grund in
 *       Worten ({@link PageFailures}); ↻ ({@link BackgroundJobs.Retry}) lädt nur diese Seiten im selben Eintrag
 *       erneut (Angelo 26.11.2026). Die übrigen laden weiter. Übernommene Abrufe bricht nur der
 *       Benutzer ab (Anhalten oder Abbrechen des Teils, Abbrechen in der Trefferliste). Scheitert das Anlegen, laden sie
 *       unverändert weiter.</li>
 *   <li>Nach einem Neustart oder Fortsetzen fordert der Teil an, was noch fehlt, nachrangig im Vordergrund des Planers
 *       mit dem Anlass {@code SEARCH}, höchstens {@value #WINDOW} zugleich.</li>
 * </ul>
 *
 * Delegierte Benutzer laden nur während ihres Aufrufs; bei nur einem Platz bleibt es beim bisherigen Weg.
 */
public final class SearchHitLoads implements AutoCloseable {

    /** Kennung der Art. */
    public static final String KIND = "search-hits";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String TITLE = "Suchtreffer laden";
    /** Angabe: die Seiten-IDs des Teils, durch Zeilenumbruch getrennt. */
    public static final String PAGES = "pages";
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = PageLoads.PAGES_MISSING;
    /** So viele Abrufe fordert ein fortgesetzter Teil höchstens zugleich an. */
    static final int WINDOW = 32;
    private static final String COMPONENT = "search-hits";
    private static final int REMEMBERED = 256;

    /** Fordert eine Seite an; austauschbar für Tests. */
    @FunctionalInterface
    interface Request {
        AcquisitionScheduler.Ticket request(AccessContext caller, PageKey key);
    }

    private final BackgroundJobs background;
    private final AcquisitionScheduler scheduler;
    private final AccessContext technical;
    private final Function<PageKey, Optional<String>> space;
    private final Request request;
    private final Object monitor = new Object();
    /** Noch nicht angelegte Übergaben je Stapel; unter {@link #monitor}. */
    private final Map<String, Map<PageKey, AcquisitionScheduler.Ticket>> pending = new HashMap<>();
    /** Übernommene Abrufe je Gruppe, bis ihr Teil sie nimmt; unter {@link #monitor}. */
    private final Map<String, Map<PageKey, AcquisitionScheduler.Ticket>> adopted = new HashMap<>();
    /** Seiten wartender oder laufender Teile, mit ihrem Teil; unter {@link #monitor}. */
    private final Map<PageKey, String> loading = new HashMap<>();
    /** Seiten je Teil, für {@link #loading}; unter {@link #monitor}. */
    private final Map<String, List<PageKey>> pagesOf = new HashMap<>();
    /** Stapel, deren Gruppe der Arbeiter gerade anlegt; ein Abbrechen in dieser Zeit merkt er sich in {@link #cancelled}. */
    private final java.util.Set<String> starting = new java.util.HashSet<>();
    /** Während des Anlegens abgebrochene Stapel: Ihre Gruppe wird gleich nach dem Start abgebrochen. */
    private final java.util.Set<String> cancelled = new java.util.HashSet<>();
    /** Gruppen je Stapel, für Abbrechen in der Trefferliste; unter {@link #monitor}. */
    private final Map<String, List<String>> groupsOf = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > REMEMBERED;
        }
    };
    private final Coalescer<String, Object> worker;
    private final Subscription parts;
    /** Gescheiterte Seiten je Teil, für ↻; unter {@link #monitor}. */
    private final Map<String, Set<PageKey>> failedOf = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<PageKey>> eldest) {
            return size() > REMEMBERED;
        }
    };
    /** Von ↻ bereitgelegte Seiten je Teil, bis sein erneuter Lauf sie nimmt; unter {@link #monitor}. */
    private final Map<String, Set<PageKey>> retrying = new HashMap<>();
    /** Laufende Teile, für die Anzeige „Wartet auf Netzverbindung“; unter {@link #monitor}. */
    private final Set<Run> running = new java.util.HashSet<>();
    private volatile com.aresstack.spaceharvester.application.network.NetworkState network;
    private volatile Subscription networkWatch = Subscription.NONE;

    /**
     * @param technical technischer Aufrufer für Teile, die nach einem Neustart fortsetzen
     * @param space     der Space einer Seite, soweit bekannt; sonst zählt sie zum Teil ohne Space
     */
    public SearchHitLoads(BackgroundJobs background, AcquisitionScheduler scheduler, AccessContext technical,
                          Function<PageKey, Optional<String>> space) {
        this(background, scheduler, technical, space, (caller, key) -> scheduler.request(caller, key,
                AcquisitionScheduler.Priority.FOREGROUND,
                new AcquisitionScheduler.Mode(false, true, ChangeTrigger.SEARCH)));
    }

    SearchHitLoads(BackgroundJobs background, AcquisitionScheduler scheduler, AccessContext technical,
                   Function<PageKey, Optional<String>> space, Request request) {
        this.background = Objects.requireNonNull(background, "background");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.technical = Objects.requireNonNull(technical, "technical");
        this.space = Objects.requireNonNull(space, "space");
        this.request = Objects.requireNonNull(request, "request");
        background.registerRetry(KIND, new RetryFailed());
        background.register(new BackgroundKind(KIND, this::work, () -> true, false, true));
        this.worker = new Coalescer<>("search-hits", (token, ignored) -> flush(token));
        this.parts = background.subscribe(KIND, this::changed, this::ended);
    }

    /**
     * Hängt den Netzzustand an: Solange das Netz weg ist, warten die Abrufe im Planer, und jeder laufende Teil steht
     * als „Wartet auf Netzverbindung“ mit dem Grund in der Diagnose; nichts zählt als gescheitert.
     */
    public void network(com.aresstack.spaceharvester.application.network.NetworkState value) {
        this.network = value;
        Subscription previous = networkWatch;
        networkWatch = value == null ? Subscription.NONE : value.subscribe(this::networkChanged);
        previous.close();
    }

    private void networkChanged(com.aresstack.spaceharvester.application.network.NetworkState.Status status) {
        List<Run> runs;
        synchronized (monitor) {
            runs = new ArrayList<>(running);
        }
        runs.forEach(run -> run.network(status));
    }

    /** ↻: Nur die gescheiterten Seiten eines Teils laufen im selben Eintrag erneut. */
    private final class RetryFailed implements BackgroundJobs.Retry {

        @Override
        public boolean prepare(BackgroundPart part) {
            synchronized (monitor) {
                Set<PageKey> failed = failedOf.remove(part.jobId());
                if (failed != null && !failed.isEmpty()) {
                    retrying.put(part.jobId(), failed);
                    return true;
                }
            }
            // Nach einem Neustart weiß der Teil nicht mehr, welche scheiterten: Er lädt alle seine Seiten erneut, die
            // schon geladenen kommen dabei aus dem Cache.
            return !keys(part.sourceId(), part.parameter(PAGES)).isEmpty();
        }

        @Override
        public void abandoned(BackgroundPart part) {
            synchronized (monitor) {
                Set<PageKey> failed = retrying.remove(part.jobId());
                if (failed != null) {
                    failedOf.put(part.jobId(), failed);
                }
            }
        }
    }

    /**
     * Übergibt die Abrufe eines Stapels, die nach der Rückkehr der Suche weiterladen; kehrt sofort zurück. Nur für
     * technische Aufrufer ohne Benutzeranmeldung, nur wenn Hintergrundteile laufen können.
     *
     * @param token Kennung des Stapels, für {@link #cancel}
     */
    public void offer(String token, AccessContext caller, Map<PageKey, AcquisitionScheduler.Ticket> tickets) {
        Objects.requireNonNull(token, "token");
        if (caller == null || caller.credential() != null || tickets == null || tickets.isEmpty()
                || !background.canRun()) {
            return;
        }
        synchronized (monitor) {
            pending.computeIfAbsent(token, ignored -> new LinkedHashMap<>()).putAll(tickets);
        }
        worker.mark(token);
    }

    /** Abbrechen in der Trefferliste: Was noch nicht angelegt ist, entfällt; angelegte Gruppen werden abgebrochen. */
    public void cancel(String token) {
        List<String> groups;
        synchronized (monitor) {
            pending.remove(token);
            groups = new ArrayList<>(groupsOf.getOrDefault(token, List.of()));
            // Legt der Arbeiter die Gruppe gerade an, kennt groupsOf sie noch nicht: Er bricht sie nach dem Start ab.
            if (starting.contains(token)) {
                cancelled.add(token);
            }
        }
        for (String groupId : groups) {
            try {
                background.cancel(groupId);
            } catch (RuntimeException e) {
                // Schon beendet: bleibt, wie er ist.
            }
        }
    }

    /** Auf dem Thread des Arbeiters: legt die Übergabe eines Stapels als Gruppe an, je Quelle und Space ein Teil. */
    private void flush(String token) {
        Map<PageKey, AcquisitionScheduler.Ticket> tickets;
        synchronized (monitor) {
            tickets = pending.remove(token);
            if (tickets != null) {
                starting.add(token);
            }
        }
        if (tickets == null) {
            return;
        }
        String groupId = null;
        try {
            groupId = start(token, tickets);
        } finally {
            boolean cancelNow;
            synchronized (monitor) {
                starting.remove(token);
                cancelNow = cancelled.remove(token);
            }
            if (cancelNow && groupId != null) {
                try {
                    background.cancel(groupId);
                } catch (RuntimeException e) {
                    // Schon beendet: bleibt, wie er ist.
                }
            }
        }
    }

    /** Legt die Gruppe für {@code tickets} an; ihre Kennung oder {@code null}, wenn keine entstand. */
    private String start(String token, Map<PageKey, AcquisitionScheduler.Ticket> tickets) {
        Map<List<String>, List<PageKey>> bySpace = new LinkedHashMap<>();
        Map<PageKey, AcquisitionScheduler.Ticket> taken = new LinkedHashMap<>();
        for (Map.Entry<PageKey, AcquisitionScheduler.Ticket> entry : tickets.entrySet()) {
            if (entry.getValue().done().isDone()) {
                continue;
            }
            String spaceKey;
            try {
                spaceKey = space.apply(entry.getKey()).orElse(null);
            } catch (RuntimeException e) {
                spaceKey = null;
            }
            synchronized (monitor) {
                // Zählt schon ein offener Teil die Seite, bleibt es dabei; der Abruf lädt ohnehin.
                if (loading.containsKey(entry.getKey())) {
                    continue;
                }
            }
            bySpace.computeIfAbsent(Arrays.asList(entry.getKey().sourceId(), spaceKey), ignored -> new ArrayList<>())
                    .add(entry.getKey());
            taken.put(entry.getKey(), entry.getValue());
        }
        if (bySpace.isEmpty()) {
            return null;
        }
        String groupId = BackgroundJobs.newGroupId();
        List<BackgroundPart.Draft> drafts = new ArrayList<>();
        bySpace.forEach((where, keys) -> drafts.add(new BackgroundPart.Draft(where.get(0), where.get(1),
                Map.of(PAGES, String.join("\n", keys.stream().map(PageKey::pageId).toList())))));
        synchronized (monitor) {
            // Vor dem Start: Ein Teil auf eigener Spur kann schon während start() laufen.
            adopted.put(groupId, taken);
            taken.keySet().forEach(key -> loading.put(key, groupId));
        }
        BackgroundGroup started;
        try {
            started = background.start(groupId, KIND, TITLE, drafts);
        } catch (RuntimeException e) {
            // Abnahme #1085: nur den Eintrag entfernen; die Abrufe der Suche laden unverändert weiter.
            synchronized (monitor) {
                adopted.remove(groupId);
                taken.keySet().forEach(key -> loading.remove(key, groupId));
            }
            return null;
        }
        synchronized (monitor) {
            groupsOf.computeIfAbsent(token, ignored -> new ArrayList<>()).add(groupId);
            for (JobStatus part : started.parts()) {
                List<PageKey> keys = keys(part);
                pagesOf.put(part.id(), keys);
                keys.forEach(key -> loading.put(key, part.id()));
            }
        }
        return groupId;
    }

    private static List<PageKey> keys(JobStatus part) {
        return new ArrayList<>(keys(part.sourceId(), part.parameter(PAGES)));
    }

    /** Die Seiten eines Teils aus seiner Angabe {@value #PAGES}, ohne Doppelte, in ihrer Reihenfolge. */
    private static List<PageKey> keys(String sourceId, String pages) {
        Set<PageKey> keys = new java.util.LinkedHashSet<>();
        if (pages != null && sourceId != null) {
            for (String pageId : pages.split("\n")) {
                if (!pageId.isBlank()) {
                    keys.add(new PageKey(sourceId, pageId));
                }
            }
        }
        return List.copyOf(keys);
    }

    /** Auftragsmeldung: Wartende und laufende Teile zählen ihre Seiten, alle übrigen nicht. */
    private void changed(JobStatus part) {
        boolean open = part.state() == JobState.QUEUED || part.state() == JobState.RUNNING;
        List<AcquisitionScheduler.Ticket> withdrawn = new ArrayList<>();
        if (part.state().terminal()) {
            synchronized (monitor) {
                // Endete der Teil, bevor er seine Abrufe nahm: Nur der Benutzer (Abbrechen, Anhalten) zieht sie zurück;
                // Fortsetzen fordert sie neu an.
                Map<PageKey, AcquisitionScheduler.Ticket> group = adopted.get(part.parameter(BackgroundJobs.GROUP));
                if (group != null) {
                    for (PageKey key : keys(part)) {
                        AcquisitionScheduler.Ticket ticket = group.remove(key);
                        if (ticket != null && byUser(part.state())) {
                            withdrawn.add(ticket);
                        }
                    }
                    if (group.isEmpty()) {
                        adopted.remove(part.parameter(BackgroundJobs.GROUP));
                    }
                }
            }
        }
        withdrawn.forEach(AcquisitionScheduler.Ticket::cancel);
        synchronized (monitor) {
            List<PageKey> keys = pagesOf.computeIfAbsent(part.id(), ignored -> keys(part));
            if (open) {
                keys.forEach(key -> loading.putIfAbsent(key, part.id()));
            } else {
                keys.forEach(key -> loading.remove(key, part.id()));
                if (part.state().terminal()) {
                    pagesOf.remove(part.id());
                }
            }
        }
    }

    private static boolean byUser(JobState state) {
        return state == JobState.CANCELLED || state == JobState.PAUSED;
    }

    private void ended(JobStatus last) {
        synchronized (monitor) {
            List<PageKey> keys = pagesOf.remove(last.id());
            if (keys != null) {
                keys.forEach(key -> loading.remove(key, last.id()));
            }
        }
    }

    /** Ein laufender Teil: wartet über Rückrufe, ohne Thread. */
    private final class Run {

        final JobContext context;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final List<AcquisitionScheduler.Ticket> own = new ArrayList<>();
        /** Selbst angeforderte Abrufe, die noch laufen; sie zählen gegen {@value #WINDOW}. */
        final Set<AcquisitionScheduler.Ticket> requestedTickets = new java.util.HashSet<>();
        final Deque<PageKey> toRequest = new ArrayDeque<>();
        final AccessContext caller;
        final Map<AcquisitionScheduler.Ticket, PageKey> keyOf = new HashMap<>();
        final Set<PageKey> failedKeys = new LinkedHashSet<>();
        boolean offline;
        int remaining;
        int requested;
        long failed;
        boolean stopping;
        Subscription full;

        Run(JobContext context, AccessContext caller) {
            this.context = context;
            this.caller = caller;
        }

        void attach(PageKey key, AcquisitionScheduler.Ticket ticket) {
            boolean stopped;
            synchronized (this) {
                stopped = stopping;
                if (!stopped) {
                    own.add(ticket);
                }
            }
            if (!stopped) {
                synchronized (this) {
                    keyOf.put(ticket, key);
                }
            }
            if (stopped) {
                // Schon angehalten oder abgebrochen (stop() lief vor dem Übernehmen): sonst lüde der Abruf weiter,
                // obwohl der Benutzer ihn zurückgezogen hat (sporadisch roter SearchHitLoadsTest, 23.11.2026).
                ticket.cancel();
                return;
            }
            ticket.done().whenComplete((stored, failure) -> finished(ticket, stored, failure));
        }

        void finished(AcquisitionScheduler.Ticket ticket, Boolean stored, Throwable failure) {
            boolean wasRequested;
            synchronized (this) {
                if (stopping) {
                    return;
                }
                wasRequested = requestedTickets.remove(ticket);
                if (wasRequested) {
                    requested--;
                }
            }
            // Erst zählen, dann „letzte Seite“ feststellen: Sonst endet der Teil, bevor eine gleichzeitig
            // fertige Seite gezählt ist.
            context.processed(1);
            if (Boolean.TRUE.equals(stored)) {
                context.written(1);
            } else {
                synchronized (this) {
                    failed++;
                    PageKey key = keyOf.get(ticket);
                    if (key != null) {
                        failedKeys.add(key);
                    }
                }
                context.failed(1);
                // Rot mit Grund in Worten; die übrigen Seiten laden weiter (Abnahme #1085).
                context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE, COMPONENT,
                        PAGES_MISSING, "Ein Suchtreffer ließ sich nicht laden: " + reason(ticket, failure), null,
                        null, failure));
            }
            if (wasRequested) {
                requestMore();
            }
            boolean last;
            synchronized (this) {
                last = !stopping && --remaining == 0;
            }
            if (last) {
                complete();
            }
        }

        /** Fordert an, was noch fehlt, bis {@value #WINDOW} zugleich; ist der Planer voll, weckt seine Meldung. */
        void requestMore() {
            while (true) {
                PageKey key;
                synchronized (this) {
                    if (stopping || toRequest.isEmpty() || requested >= WINDOW) {
                        return;
                    }
                    key = toRequest.pollFirst();
                }
                AcquisitionScheduler.Ticket ticket;
                try {
                    ticket = request.request(caller, key);
                } catch (RejectedExecutionException e) {
                    synchronized (this) {
                        toRequest.addFirst(key);
                        if (full == null) {
                            full = scheduler.subscribe(status -> requestMore());
                        }
                    }
                    return;
                }
                synchronized (this) {
                    requested++;
                    requestedTickets.add(ticket);
                }
                attach(key, ticket);
            }
        }

        void complete() {
            Subscription waiting;
            synchronized (this) {
                waiting = full;
                full = null;
            }
            if (waiting != null) {
                waiting.close();
            }
            long failures;
            Set<PageKey> keys;
            synchronized (this) {
                failures = failed;
                keys = new LinkedHashSet<>(failedKeys);
            }
            leave(keys);
            if (failures > 0) {
                done.completeExceptionally(new JobFailedException(PAGES_MISSING, failures
                        + (failures == 1 ? " Suchtreffer ließ" : " Suchtreffer ließen")
                        + " sich auch nach den Wiederholungen nicht laden. ↻ lädt nur "
                        + (failures == 1 ? "ihn" : "diese") + " erneut; die Gründe stehen unter „Fehler diagnostizieren“"));
            } else {
                done.complete(null);
            }
        }

        /** Der Teil endet: aus der Anzeige des Netzes, die gescheiterten Seiten bleiben für ↻. */
        void leave(Set<PageKey> keys) {
            boolean wasOffline;
            synchronized (monitor) {
                running.remove(this);
                if (!keys.isEmpty()) {
                    failedOf.put(context.jobId(), keys);
                }
            }
            synchronized (this) {
                wasOffline = offline;
                offline = false;
            }
            if (wasOffline) {
                context.awaitingNetwork(false);
            }
        }

        /** Netz weg: „Wartet auf Netzverbindung“ mit Grund, einmal je Ausfall; zurück: läuft wieder sichtbar. */
        void network(com.aresstack.spaceharvester.application.network.NetworkState.Status status) {
            boolean change;
            synchronized (this) {
                change = !stopping && offline == status.online();
                if (change) {
                    offline = !status.online();
                }
            }
            if (!change) {
                return;
            }
            context.awaitingNetwork(!status.online());
            if (status.online()) {
                context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.NETWORK, COMPONENT,
                        com.aresstack.spaceharvester.application.network.NetworkWait.ONLINE,
                        "Netzverbindung zurück; die Suchtreffer laden hier weiter", null, null, null));
            } else {
                context.record(new JobEvent.Draft(JobEvent.Severity.WARNING, JobEvent.Category.NETWORK, COMPONENT,
                        com.aresstack.spaceharvester.application.network.NetworkWait.OFFLINE, "Keine Netzverbindung ("
                        + (status.reason() == null ? "Netz nicht erreichbar" : status.reason())
                        + "): Die Suchtreffer warten, bis sie zurück ist, und laden dann von selbst hier weiter; "
                        + "nichts zählt als gescheitert", null, null, null));
            }
        }

        /** Anhalten oder Abbrechen durch den Benutzer: Die Abrufe dieses Teils werden zurückgezogen. */
        void stop() {
            List<AcquisitionScheduler.Ticket> tickets;
            Subscription waiting;
            synchronized (this) {
                stopping = true;
                tickets = new ArrayList<>(own);
                waiting = full;
                full = null;
            }
            if (waiting != null) {
                waiting.close();
            }
            tickets.forEach(AcquisitionScheduler.Ticket::cancel);
            Set<PageKey> keys;
            synchronized (this) {
                keys = new LinkedHashSet<>(failedKeys);
            }
            leave(keys);
            try {
                context.checkpoint();
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }
    }

    private static String reason(AcquisitionScheduler.Ticket ticket, Throwable failure) {
        return PageFailures.reason(ticket.failure().map(Throwable.class::cast).orElse(failure));
    }

    private JobTask work(BackgroundPart part) {
        return context -> {
            List<PageKey> keys;
            synchronized (monitor) {
                // ↻: nur die gescheiterten Seiten; sonst (erster Lauf, Fortsetzen, Neustart) alle des Teils.
                Set<PageKey> again = retrying.remove(part.jobId());
                keys = again != null ? List.copyOf(again) : keys(part.sourceId(), part.parameter(PAGES));
                failedOf.remove(part.jobId());
            }
            Run run = new Run(context, technical);
            synchronized (monitor) {
                running.add(run);
            }
            com.aresstack.spaceharvester.application.network.NetworkState net = network;
            if (net != null && !net.online()) {
                run.network(net.status());
            }
            Map<PageKey, AcquisitionScheduler.Ticket> mine = new LinkedHashMap<>();
            synchronized (monitor) {
                Map<PageKey, AcquisitionScheduler.Ticket> group = adopted.get(part.groupId());
                if (group != null) {
                    keys.forEach(key -> {
                        AcquisitionScheduler.Ticket ticket = group.remove(key);
                        if (ticket != null) {
                            mine.put(key, ticket);
                        }
                    });
                    if (group.isEmpty()) {
                        adopted.remove(part.groupId());
                    }
                }
            }
            context.discovered(keys.size());
            synchronized (run) {
                run.remaining = keys.size();
                keys.stream().filter(key -> !mine.containsKey(key)).forEach(run.toRequest::add);
            }
            if (keys.isEmpty()) {
                context.whenStopRequested(run::stop);
                run.complete();
                return run.done;
            }
            // Erst die übernommenen Abrufe an den Lauf hängen, dann auf Anhalten hören: Kam das Abbrechen schon an,
            // läuft stop() sofort und zieht sie zurück. Umgekehrt fand stop() eine leere Liste, und die Abrufe luden
            // weiter (Wettlauf zwischen Abbrechen in der Trefferliste und dem Start des Teils).
            mine.forEach(run::attach);
            context.whenStopRequested(run::stop);
            run.requestMore();
            return run.done;
        };
    }

    @Override
    public void close() {
        networkWatch.close();
        worker.close();
        parts.close();
    }
}
