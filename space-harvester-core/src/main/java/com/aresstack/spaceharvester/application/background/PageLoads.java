package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * „Seiten nachladen“ als Hintergrundauftrag ({@value #KIND}): Seiten, die die App von sich aus braucht, etwa fehlende
 * Nachbarseiten eines Suchtreffers (R2), gehen je Space als Unterauftrag an den {@link AcquisitionScheduler}, auf dem
 * Weg des Hintergrunds ({@link AcquisitionScheduler.Priority#BACKGROUND}); der legt sie ab, Volltextindex und
 * Anreicherungen folgen von selbst. Was schon im Cache liegt, wird übersprungen, darum holt ein Fortsetzen nur, was noch
 * fehlt. Der Hintergrund lädt nie mit der Anmeldung eines Benutzers; nach einem Neustart lädt der technische Aufrufer des
 * Dienstes weiter.
 */
public final class PageLoads {

    /** Kennung der Art. */
    public static final String KIND = "pages";
    /** Angabe: die Seiten-IDs des Unterauftrags, durch {@value #SEPARATOR} getrennt. */
    public static final String PAGES = "pages";
    static final String SEPARATOR = "\n";
    /** So viele Abrufe hat ein Unterauftrag höchstens zugleich beim Planer. */
    static final int WINDOW = 32;
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = "PAGES_MISSING";
    /** Angabe: {@code true}, wenn der Teil auch Seiten im Cache lädt, etwa veraltete Treffer (Fundstelle 10). */
    public static final String REFRESH = "refresh";
    /**
     * Angabe eines Teils mit der Anmeldung eines Benutzers: Kennung seines Aufrufers in diesem Prozess (wie
     * {@code StatementAnalysisJobs#CALLER}). Den Aufrufer selbst hält nur der Speicher.
     */
    public static final String CALLER = "caller";
    /** Angabe dazu: Eigentümer ({@link AccessContext#jobOwner()}) des Aufrufers. */
    public static final String OWNER = "owner";
    /** Code des gelben Hinweises: Nach dem Neustart fehlt die Anmeldung des Benutzers, der Teil endet. */
    public static final String NOT_RESUMED = "NOT_RESUMED";
    private static final String COMPONENT = "background-pages";

    private final BackgroundJobs background;
    private final AcquisitionScheduler scheduler;
    private final AccessContext technical;
    private final Predicate<PageKey> cached;
    private final Function<PageKey, Optional<String>> space;
    /**
     * Aufrufer der jüngsten Gruppen, älteste zuerst verdrängt; danach und nach einem Neustart gilt {@link #technical}.
     */
    private final Map<String, AccessContext> callers = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AccessContext> eldest) {
                    return size() > MAX_CALLERS;
                }
            });
    static final int MAX_CALLERS = 256;

    /**
     * @param technical der technische Aufrufer des Dienstes, für fortgesetzte Unteraufträge nach einem Neustart
     * @param cached    ob eine Seite schon vollständig im Cache liegt
     * @param space     der Space einer Seite, soweit bekannt; sonst zählt sie zum Unterauftrag ohne Space
     */
    public PageLoads(BackgroundJobs background, AcquisitionScheduler scheduler, AccessContext technical,
                     Predicate<PageKey> cached, Function<PageKey, Optional<String>> space) {
        this.background = Objects.requireNonNull(background, "background");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.technical = Objects.requireNonNull(technical, "technical");
        this.cached = Objects.requireNonNull(cached, "cached");
        this.space = Objects.requireNonNull(space, "space");
        background.register(new BackgroundKind(KIND, this::work));
    }

    /**
     * Lädt die Seiten im Hintergrund, je Quelle und Space ein Unterauftrag; schon gespeicherte fallen vorher heraus,
     * ebenso Seiten, die schon ein wartender oder laufender Unterauftrag dieser Art lädt (keine doppelten Gruppen, etwa
     * wenn dieselbe Suche zweimal läuft).
     *
     * @param caller technischer Aufrufer (ohne Benutzeranmeldung), dessen Rechte gelten
     * @param title  Titel der Gruppe in der Auftragsliste, etwa „Nachbarseiten laden“
     * @return die Gruppe, oder leer, wenn nichts fehlt
     * @throws IllegalArgumentException wenn der Aufrufer eine Benutzeranmeldung durchreicht
     */
    public Optional<BackgroundGroup> load(AccessContext caller, String title, Collection<PageKey> keys) {
        return join(caller, title, keys).started();
    }

    /**
     * Wie {@link #load}, nennt dazu die Gruppen, die übersprungene Seiten schon laden: Wer auf die Seiten wartet,
     * wartet auf alle beteiligten Gruppen.
     */
    public synchronized Joined join(AccessContext caller, String title, Collection<PageKey> keys) {
        Objects.requireNonNull(caller, "caller");
        if (caller.credential() != null) {
            throw new IllegalArgumentException("Der Hintergrund lädt nie mit der Anmeldung eines Benutzers");
        }
        return join(caller, title, keys, false);
    }

    /**
     * Wie {@link #join(AccessContext, String, Collection)}, auch für einen Benutzer mit Anmeldung und auf Wunsch für
     * Seiten im Cache (Fundstelle 10, Abnahme PR 4): Mit {@code refresh} lädt der Teil auch Seiten, die schon gespeichert
     * sind, etwa weil die Suche sie als veraltet meldet. Die Anmeldung eines Benutzers hält nur der Speicher
     * ({@value #CALLER}); seine Abrufe gehen über den Vordergrund des Planers, der den Hintergrund nie mit einer
     * Anmeldung lädt. Nach einem Neustart endet so ein Teil gelb ({@value #NOT_RESUMED}), statt mit fremden Rechten
     * weiterzuladen; die nächste Suche holt nach.
     */
    public synchronized Joined join(AccessContext caller, String title, Collection<PageKey> keys, boolean refresh) {
        Objects.requireNonNull(caller, "caller");
        // Ein Teil ohne refresh überspränge eine veraltete Seite im Cache: Nachladen schließt sich nur seinesgleichen an.
        Map<PageKey, String> queued = queued(refresh);
        Set<String> joined = new LinkedHashSet<>();
        Map<List<String>, Set<String>> bySpace = new LinkedHashMap<>();
        for (PageKey key : keys) {
            String loading = queued.get(key);
            if (loading != null) {
                joined.add(loading);
                continue;
            }
            if (!refresh && cached.test(key)) {
                continue;
            }
            String spaceKey = space.apply(key).orElse(null);
            bySpace.computeIfAbsent(Arrays.asList(key.sourceId(), spaceKey), ignored -> new LinkedHashSet<>())
                    .add(key.pageId());
        }
        if (bySpace.isEmpty()) {
            return new Joined(Optional.empty(), joined);
        }
        Map<String, String> common = new LinkedHashMap<>();
        if (refresh) {
            common.put(REFRESH, "true");
        }
        // Den Aufrufer vor dem Start ablegen: ein Unterauftrag kann schon während start() laufen.
        String groupId = BackgroundJobs.newGroupId();
        callers.put(groupId, caller);
        if (caller.credential() != null) {
            String token = java.util.UUID.randomUUID().toString();
            callers.put(token, caller);
            common.put(CALLER, token);
            common.put(OWNER, caller.jobOwner());
        }
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        bySpace.forEach((where, pageIds) -> {
            Map<String, String> parameters = new LinkedHashMap<>(common);
            parameters.put(PAGES, String.join(SEPARATOR, pageIds));
            parts.add(new BackgroundPart.Draft(where.get(0), where.get(1), parameters));
        });
        return new Joined(Optional.of(background.start(groupId, KIND, title, parts)), joined);
    }

    /**
     * Ausgang von {@link #join}.
     *
     * @param started die neue Gruppe für Seiten, die noch niemand lädt, oder leer
     * @param joined  Gruppen, die übersprungene Seiten schon laden (auch angehaltene)
     */
    public record Joined(Optional<BackgroundGroup> started, Set<String> joined) {

        public Joined {
            Objects.requireNonNull(started, "started");
            joined = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(joined));
        }

        /** Alle Gruppen, auf die zu warten ist, die neue zuerst. */
        public Set<String> groups() {
            Set<String> all = new LinkedHashSet<>();
            started.ifPresent(group -> all.add(group.groupId()));
            all.addAll(joined);
            return java.util.Collections.unmodifiableSet(all);
        }
    }

    /**
     * Seiten, die ein wartender, laufender oder angehaltener Unterauftrag dieser Art schon lädt, mit seiner Gruppe.
     * Angehalten zählt mit: Er lädt weiter, sobald er fortgesetzt wird.
     *
     * @param refreshing nur Teile, die auch Seiten im Cache laden ({@value #REFRESH})
     */
    private Map<PageKey, String> queued(boolean refreshing) {
        Map<PageKey, String> queued = new java.util.HashMap<>();
        for (BackgroundGroup group : background.groups()) {
            if (!KIND.equals(group.kind())) {
                continue;
            }
            for (com.aresstack.spaceharvester.application.jobs.JobStatus part : group.parts()) {
                String pages = part.parameter(PAGES);
                if (!loading(part.state()) || pages == null || part.sourceId() == null
                        || refreshing && !"true".equals(part.parameter(REFRESH))) {
                    continue;
                }
                for (String pageId : pages.split(SEPARATOR)) {
                    if (!pageId.isBlank()) {
                        queued.putIfAbsent(new PageKey(part.sourceId(), pageId), group.groupId());
                    }
                }
            }
        }
        return queued;
    }

    /** Ob ein Unterauftrag in diesem Zustand noch lädt oder weiterladen wird (angehalten). */
    public static boolean loading(com.aresstack.spaceharvester.application.jobs.JobState state) {
        return !state.terminal() || state == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED;
    }

    private JobTask work(BackgroundPart part) {
        String token = part.parameter(CALLER);
        boolean refresh = "true".equals(part.parameter(REFRESH));
        AccessContext caller = token != null ? callers.get(token) : callers.getOrDefault(part.groupId(), technical);
        if (caller == null) {
            // Abnahme PR 4: nie mit technischen Rechten für einen Benutzer weiterladen.
            return JobTask.blocking(context -> {
                context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NOT_RESUMED,
                        "Nach dem Neustart nicht fortgesetzt: Die Anmeldung des Benutzers lag nur im Speicher; die "
                                + "nächste Suche lädt nach"));
                context.incomplete();
            });
        }
        List<PageKey> keys = new ArrayList<>();
        String pages = part.parameter(PAGES);
        if (pages != null) {
            for (String pageId : pages.split(SEPARATOR)) {
                if (!pageId.isBlank()) {
                    keys.add(new PageKey(part.sourceId(), pageId));
                }
            }
        }
        return JobTask.blocking(context -> run(context, caller, keys, refresh));
    }

    private void run(JobContext context, AccessContext caller, List<PageKey> keys, boolean refresh)
            throws InterruptedException {
        List<PageKey> missing = refresh ? keys : keys.stream().filter(key -> !cached.test(key)).toList();
        context.discovered(keys.size());
        context.processed(keys.size() - missing.size());
        java.util.ArrayDeque<AcquisitionScheduler.Ticket> open = new java.util.ArrayDeque<>();
        long failed = 0;
        // Ohne Takt (ADR 0027): geweckt wird nur durch das Ende eines Abrufs, eine Anforderung zum Anhalten oder
        // Abbrechen und, solange der Planer voll ist, durch seine Standmeldung.
        Wake wake = new Wake();
        com.aresstack.spaceharvester.application.observe.Subscription full = null;
        AutoCloseable stop = context.whenStopRequested(wake::ring);
        try {
            for (PageKey key : missing) {
                // Höchstens WINDOW Abrufe zugleich, und ein voller Planer heißt warten statt scheitern.
                while (true) {
                    context.checkpoint();
                    if (open.size() >= WINDOW) {
                        failed += finish(context, open.pollFirst(), wake);
                        continue;
                    }
                    long seen = wake.mark();
                    try {
                        open.addLast(scheduler.request(caller, key, priority(caller), refresh));
                        break;
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        if (!open.isEmpty()) {
                            failed += finish(context, open.pollFirst(), wake);
                        } else {
                            if (full == null) {
                                // Erst anmelden, dann noch einmal versuchen: eine Meldung dazwischen weckt sofort.
                                full = scheduler.subscribe(status -> wake.ring());
                                continue;
                            }
                            wake.awaitAfter(seen);
                        }
                    }
                }
            }
            while (!open.isEmpty()) {
                failed += finish(context, open.pollFirst(), wake);
            }
        } finally {
            if (full != null) {
                full.close();
            }
            try {
                stop.close();
            } catch (Exception ignored) {
                // nur eine Abmeldung
            }
            // Abgebrochen oder angehalten: noch wartende Abrufe entfallen, ein laufender endet regulär.
            open.forEach(AcquisitionScheduler.Ticket::cancel);
        }
        if (failed > 0) {
            // Gescheitert statt fertig: „Fortsetzen“ lädt dann im selben Eintrag nur, was noch fehlt.
            throw new com.aresstack.spaceharvester.application.jobs.JobFailedException(PAGES_MISSING,
                    failed + (failed == 1 ? " Seite ließ" : " Seiten ließen") + " sich nicht laden; Fortsetzen lädt "
                            + "nur, was noch fehlt");
        }
    }

    /**
     * Wartet auf eine Seite und zählt sie; 1, wenn sie sich nicht laden ließ. Fehlt die Anmeldung (auch ein offener
     * Einmalcode) oder ist die Quelle nicht erreichbar, hält sich der Unterauftrag an (gelb), statt die übrigen Seiten
     * als Fehler zu zählen; Fortsetzen lädt dann nur, was noch fehlt.
     */
    private static long finish(JobContext context, AcquisitionScheduler.Ticket ticket, Wake wake)
            throws InterruptedException {
        boolean loaded = await(context, ticket.done(), wake);
        if (!loaded) {
            PauseReason reason = ticket.failure().map(PageLoads::pauseReason).orElse(null);
            if (reason != null) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, reason.name(),
                        reason == PauseReason.SIGN_IN_REQUIRED
                                ? "Die Quelle verlangt eine Anmeldung; der Unterauftrag hält sich an"
                                : "Die Quelle antwortet nicht; der Unterauftrag hält sich an"));
                context.pause(reason);
            }
        }
        context.processed(1);
        if (loaded) {
            context.written(1);
            return 0;
        }
        context.failed(1);
        return 1;
    }

    /** Anmeldung oder Verbindung als Grund zum Anhalten, sonst {@code null} (ein Fehler der Seite). */
    static PauseReason pauseReason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SignInRequiredException || current instanceof SignInPendingException) {
                return PauseReason.SIGN_IN_REQUIRED;
            }
            if (current instanceof SourceAccessException access
                    && (access.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || access.reason() == SourceAccessException.Reason.RATE_LIMITED)) {
                return PauseReason.CONNECTION_LOST;
            }
            if (current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.http.HttpConnectTimeoutException) {
                return PauseReason.CONNECTION_LOST;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * Der Weg im Planer: der Hintergrund, wenn er Plätze hat; sonst der Vordergrund, denn ohne Hintergrundfüllung ist
     * dessen Weg abgeschaltet. Nachrangig ist der Auftrag schon in der Auftragsliste.
     */
    private AcquisitionScheduler.Priority priority(AccessContext caller) {
        // Mit der Anmeldung eines Benutzers nie über den Hintergrund des Planers (der lehnt sie ab).
        return caller.credential() == null && scheduler.limits().background() > 0
                ? AcquisitionScheduler.Priority.BACKGROUND : AcquisitionScheduler.Priority.FOREGROUND;
    }

    /**
     * Wartet auf eine Seite ohne Takt: geweckt wird nur durch ihr Ende oder eine Anforderung zum Anhalten oder
     * Abbrechen ({@link JobContext#whenStopRequested}, in {@code wake}); dann prüft {@link JobContext#checkpoint()}.
     */
    static boolean await(JobContext context, java.util.concurrent.CompletableFuture<Boolean> done, Wake wake)
            throws InterruptedException {
        long seen = wake.mark();
        done.whenComplete((loaded, failure) -> wake.ring());
        while (true) {
            context.checkpoint();
            if (done.isDone()) {
                try {
                    return Boolean.TRUE.equals(done.getNow(false));
                } catch (java.util.concurrent.CompletionException | CancellationException e) {
                    return false;
                }
            }
            wake.awaitAfter(seen);
            seen = wake.mark();
        }
    }

    /** Weckt den wartenden Unterauftrag; jede Meldung zählt eins hoch, damit keine verloren geht. */
    static final class Wake {

        private long generation;

        synchronized void ring() {
            generation++;
            notifyAll();
        }

        synchronized long mark() {
            return generation;
        }

        /** Wartet, bis nach {@code seen} geläutet wurde; ohne Zeitgrenze. */
        synchronized void awaitAfter(long seen) throws InterruptedException {
            while (generation == seen) {
                wait();
            }
        }
    }
}
