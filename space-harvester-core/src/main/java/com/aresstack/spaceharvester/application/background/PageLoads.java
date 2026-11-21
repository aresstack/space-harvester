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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    /** So oft prüft der wartende Unterauftrag, ob er abgebrochen oder angehalten wurde. */
    static final long CHECK_MILLIS = 500;
    /** So viele Abrufe hat ein Unterauftrag höchstens zugleich beim Planer. */
    static final int WINDOW = 32;
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = "PAGES_MISSING";
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
    public synchronized Optional<BackgroundGroup> load(AccessContext caller, String title, Collection<PageKey> keys) {
        Objects.requireNonNull(caller, "caller");
        if (caller.credential() != null) {
            throw new IllegalArgumentException("Der Hintergrund lädt nie mit der Anmeldung eines Benutzers");
        }
        Set<PageKey> queued = queued();
        Map<List<String>, Set<String>> bySpace = new LinkedHashMap<>();
        for (PageKey key : keys) {
            if (queued.contains(key) || cached.test(key)) {
                continue;
            }
            String spaceKey = space.apply(key).orElse(null);
            bySpace.computeIfAbsent(Arrays.asList(key.sourceId(), spaceKey), ignored -> new LinkedHashSet<>())
                    .add(key.pageId());
        }
        if (bySpace.isEmpty()) {
            return Optional.empty();
        }
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        bySpace.forEach((where, pageIds) -> parts.add(new BackgroundPart.Draft(where.get(0), where.get(1),
                Map.of(PAGES, String.join(SEPARATOR, pageIds)))));
        // Den Aufrufer vor dem Start ablegen: ein Unterauftrag kann schon während start() laufen.
        String groupId = BackgroundJobs.newGroupId();
        callers.put(groupId, caller);
        return Optional.of(background.start(groupId, KIND, title, parts));
    }

    /** Seiten, die ein wartender oder laufender Unterauftrag dieser Art schon lädt. */
    private Set<PageKey> queued() {
        Set<PageKey> queued = new java.util.HashSet<>();
        for (BackgroundGroup group : background.groups()) {
            if (!KIND.equals(group.kind())) {
                continue;
            }
            for (com.aresstack.spaceharvester.application.jobs.JobStatus part : group.parts()) {
                String pages = part.parameter(PAGES);
                if (part.state().terminal() || pages == null || part.sourceId() == null) {
                    continue;
                }
                for (String pageId : pages.split(SEPARATOR)) {
                    if (!pageId.isBlank()) {
                        queued.add(new PageKey(part.sourceId(), pageId));
                    }
                }
            }
        }
        return queued;
    }

    private JobTask work(BackgroundPart part) {
        AccessContext caller = callers.getOrDefault(part.groupId(), technical);
        List<PageKey> keys = new ArrayList<>();
        String pages = part.parameter(PAGES);
        if (pages != null) {
            for (String pageId : pages.split(SEPARATOR)) {
                if (!pageId.isBlank()) {
                    keys.add(new PageKey(part.sourceId(), pageId));
                }
            }
        }
        return JobTask.blocking(context -> run(context, caller, keys));
    }

    private void run(JobContext context, AccessContext caller, List<PageKey> keys) throws InterruptedException {
        List<PageKey> missing = keys.stream().filter(key -> !cached.test(key)).toList();
        context.discovered(keys.size());
        context.processed(keys.size() - missing.size());
        java.util.ArrayDeque<AcquisitionScheduler.Ticket> open = new java.util.ArrayDeque<>();
        long failed = 0;
        try {
            for (PageKey key : missing) {
                // Höchstens WINDOW Abrufe zugleich, und ein voller Planer heißt warten statt scheitern.
                while (true) {
                    context.checkpoint();
                    if (open.size() >= WINDOW) {
                        failed += finish(context, open.pollFirst());
                        continue;
                    }
                    try {
                        open.addLast(scheduler.request(caller, key, priority()));
                        break;
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        if (!open.isEmpty()) {
                            failed += finish(context, open.pollFirst());
                        } else {
                            Thread.sleep(CHECK_MILLIS);
                        }
                    }
                }
            }
            while (!open.isEmpty()) {
                failed += finish(context, open.pollFirst());
            }
        } finally {
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
    private static long finish(JobContext context, AcquisitionScheduler.Ticket ticket) throws InterruptedException {
        boolean loaded = await(context, ticket);
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
    private AcquisitionScheduler.Priority priority() {
        return scheduler.limits().background() > 0 ? AcquisitionScheduler.Priority.BACKGROUND
                : AcquisitionScheduler.Priority.FOREGROUND;
    }

    /** Wartet auf eine Seite und prüft dabei, ob der Auftrag abgebrochen oder angehalten wurde. */
    private static boolean await(JobContext context, AcquisitionScheduler.Ticket ticket) throws InterruptedException {
        while (true) {
            context.checkpoint();
            try {
                return Boolean.TRUE.equals(ticket.done().get(CHECK_MILLIS, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                // weiter warten
            } catch (ExecutionException | CancellationException e) {
                return false;
            }
        }
    }
}
