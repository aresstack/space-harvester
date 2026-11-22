package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.lang.ref.SoftReference;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Anwendungsfall „Lese-Ansicht einer Seite“ (#176): die Seite so, wie die Quelle sie rendert, mit ausgeführten
 * Makros ({@code include}, {@code excerpt-include}, {@code children}, Jira), dazu Lesetext und Bilder der Ansicht.
 *
 * <p>Rechte und Version wie beim Lesen der Seite ({@link PageQueries#page}): erst danach wird die Ansicht gezeigt,
 * und nur zur geprüften Version. Die Ansicht liegt neben dem Raw-Snapshot ({@link ReadingViewStore}); der Snapshot
 * bleibt unverändert die Quelle der Wahrheit. Eine abgelegte Ansicht derselben Payload wird ohne neuen Abruf wieder
 * ausgeliefert.
 *
 * <p>Bindet die Seite Fremdes ein ({@link ForeignContent}), hat die Quelle diese Teile mit ihren eigenen Zugangsdaten
 * gerendert. Dann gilt:
 * <ul>
 *   <li>Die Ansicht erhält nur, wer alles lesen darf, was diese Zugangsdaten sehen
 *       ({@link AccessGuard#permitsSourceWide}); sonst {@link ReadingView.Status#RESTRICTED} ohne Inhalt.</li>
 *   <li>Eingebundenes ändert sich ohne neue Version der Seite; die Ansicht wird daher jedes Mal neu abgerufen und
 *       nur dann aus der Ablage geliefert, wenn die Quelle gerade nicht erreichbar ist.</li>
 * </ul>
 */
public class ReadingViews {

    private static final System.Logger LOG = System.getLogger(ReadingViews.class.getName());

    private final SourceCatalog catalog;
    private final PageQueries pages;
    private final AccessGuard access;
    private final ReadingViewStore store;
    private final Clock clock;
    /**
     * Besuchte und vorgeladene Ansichten im Speicher (Angelo 17.11.2026): weich gehalten, damit der Garbage Collector
     * sie bei Speicherbedarf einsammelt; nie aktiv entladen, damit Vor- und Zurückspringen sofort zeigt.
     */
    private final Map<PageKey, SoftReference<Remembered>> remembered = new ConcurrentHashMap<>();
    private final Set<PageKey> prefetching = ConcurrentHashMap.newKeySet();
    private final Semaphore prefetchSlots = new Semaphore(PREFETCH_PARALLEL);
    private final ExecutorService prefetcher = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("reading-prefetch-", 0).factory());

    /** Höchstens so viele Seiten lädt {@link #prefetch} je Aufruf vor. */
    public static final int MAX_PREFETCH = 25;
    /** Höchstens so viele Vorladungen zugleich, damit der Hintergrund die Quelle nicht verstopft. */
    static final int PREFETCH_PARALLEL = 2;
    /** Ab so vielen Einträgen räumt der Speicher eingesammelte Verweise weg. */
    private static final int PRUNE_AT = 4096;

    /** Eine Ansicht zur Payload, aus der sie stammt. */
    private record Remembered(String rawSha256, StoredReadingView view) {
    }

    public ReadingViews(SourceCatalog catalog, PageQueries pages, AccessGuard access, ReadingViewStore store,
                        Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.access = Objects.requireNonNull(access, "access");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Die Lese-Ansicht einer Seite.
     *
     * @param refresh wie bei {@link PageQueries#page}: Prüfung gegen die Quelle ohne Rückgriff auf den Cache; dazu
     *                wird die Ansicht neu abgerufen
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException     wie {@link PageQueries#page}
     * @throws com.aresstack.spaceharvester.application.query.CacheMissException         wie {@link PageQueries#page}
     * @throws SourceAccessException wenn die Quelle die Ansicht nicht liefert und keine abgelegte vorliegt, oder wenn
     *                               sich die Seite beim Lesen wiederholt geändert hat ({@code UNAVAILABLE})
     */
    public ReadingView view(AccessContext caller, String sourceId, String pageId, boolean refresh) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, refresh);
        ReadingView view = view(caller, page, refresh);
        if (view != null) {
            return view;
        }
        // Die Seite änderte sich zwischen Prüfung und Ansicht: einmal gegen die Quelle neu prüfen, dann gilt die neue
        // Version mit ihren Rechten.
        view = view(caller, pages.page(caller, sourceId, pageId, true), true);
        if (view == null) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                    "Die Seite ändert sich gerade; die Lese-Ansicht passt zu keiner geprüften Version", null);
        }
        return view;
    }

    /**
     * Die Lese-Ansicht nur aus dem Cache, ohne die Quelle zu fragen: zuerst zeigen, dann {@link #view} im Hintergrund
     * prüfen lassen und nur bei einer anderen Version tauschen (Angelo 17.11.2026). Aus dem Speicher, sonst aus der
     * Ablage neben dem Snapshot; Rechte wie bei {@link #view}.
     *
     * @return leer, wenn Seite oder passende Ansicht nicht lokal liegen, die Seite nicht lesbar ist oder eingebundene
     *         Inhalte jedes Mal frisch geholt werden müssen
     */
    public Optional<ReadingView> cached(AccessContext caller, String sourceId, String pageId) {
        Optional<DeliveredPage> found = pages.cached(caller, sourceId, pageId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DeliveredPage page = found.get();
        ForeignContent foreign = ForeignContent.of(page.snapshot());
        if (foreign.present()) {
            return access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT) ? Optional.empty()
                    : Optional.of(ReadingView.without(page.header(), ReadingView.Status.RESTRICTED, foreign));
        }
        String rawSha256 = page.snapshot().payload().rawSha256();
        Optional<StoredReadingView> inMemory = memory(page.header().key(), rawSha256);
        if (inMemory.isPresent()) {
            return Optional.of(delivered(page, inMemory.get(), foreign, true));
        }
        Optional<StoredReadingView> stored = storedQuietly(page).filter(view -> view.rawSha256().equals(rawSha256));
        stored.ifPresent(this::remember);
        return stored.map(view -> delivered(page, view, foreign, true));
    }

    /** Die Ansicht aus dem Speicher, wenn sie zu dieser Payload gehört und noch nicht eingesammelt ist. */
    private Optional<StoredReadingView> memory(PageKey key, String rawSha256) {
        SoftReference<Remembered> reference = remembered.get(key);
        Remembered memory = reference == null ? null : reference.get();
        return memory != null && memory.rawSha256().equals(rawSha256) ? Optional.of(memory.view())
                : Optional.empty();
    }

    /**
     * Lädt die Lese-Ansichten dieser Seiten im Hintergrund vor, etwa die Kinder eines aufgeklappten Knotens (Angelo
     * 17.11.2026, wie das Vorladen in MainframeMate): wie {@link #view} ohne {@code refresh}, also aus dem Cache, wenn
     * er gilt, sonst von der Quelle, und behält sie im Speicher. Höchstens {@value #MAX_PREFETCH} Seiten je Aufruf und
     * {@value #PREFETCH_PARALLEL} zugleich; schon laufende werden nicht doppelt geholt. Fehler bleiben still, das Öffnen
     * versucht es selbst noch einmal. Kehrt sofort zurück.
     *
     * <p>Das Vorladen löst nie einen Anmelde- oder Codedialog aus: es fragt die Quelle nur mit der bestehenden Anmeldung
     * und meldet keine Einmalcode-Abfrage an ({@code SignInRequests} bleibt unberührt). Fehlt die Anmeldung, verlangt die
     * Quelle einen Code, ist sie nicht erreichbar oder drosselt sie, gibt es still auf: die übrigen Seiten dieses
     * Aufrufs fragt es nicht mehr.
     */
    public void prefetch(AccessContext caller, String sourceId, Collection<String> pageIds) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(sourceId, "sourceId");
        if (hosted != null) {
            prefetchAsJob(caller, sourceId, pageIds);
            return;
        }
        int started = 0;
        java.util.concurrent.atomic.AtomicBoolean gaveUp = new java.util.concurrent.atomic.AtomicBoolean();
        for (String pageId : pageIds) {
            if (started >= MAX_PREFETCH) {
                break;
            }
            PageKey key = new PageKey(sourceId, pageId);
            if (!prefetching.add(key)) {
                continue;
            }
            started++;
            prefetcher.execute(() -> {
                try {
                    prefetchSlots.acquire();
                    try {
                        if (!gaveUp.get()) {
                            try {
                                // Mark the source unavailable before releasing a slot to another request.
                                ChangeTrigger.BACKGROUND.call(() -> view(caller, sourceId, pageId, false));
                            } catch (SourceAccessException e) {
                                if (e.reason() != SourceAccessException.Reason.NOT_FOUND) {
                                    gaveUp.set(true);
                                }
                                throw e;
                            }
                        }
                    } finally {
                        prefetchSlots.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (SourceAccessException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Vorladen einer Lese-Ansicht in {0} ausgelassen ({1})",
                            sourceId, e.getClass().getName());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Vorladen einer Lese-Ansicht in {0} ausgelassen ({1})",
                            sourceId, e.getClass().getName());
                } finally {
                    prefetching.remove(key);
                }
            });
        }
    }

    // --- Vorladen als Hintergrundauftrag (Fundstelle 15, Abnahme PR 4) ----------------------------------------------

    /** Kennung der Art. */
    public static final String KIND = "reading-prefetch";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String TITLE = "Lese-Ansichten vorladen";
    /** Angabe: die Seiten-IDs des Teils, durch Zeilenumbruch getrennt. */
    public static final String PAGES = "pages";
    /** Angabe eines Teils mit der Anmeldung eines Benutzers: Kennung seines Aufrufers in diesem Prozess. */
    public static final String CALLER = "caller";
    /** Angabe dazu: Eigentümer ({@link AccessContext#jobOwner()}) des Aufrufers. */
    public static final String OWNER = "owner";
    /** Fehlercode: Die Quelle war nicht erreichbar, verlangte eine Anmeldung oder drosselte. */
    public static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
    /** Code des gelben Hinweises: Nach dem Neustart fehlt die Anmeldung des Benutzers, der Teil endet. */
    public static final String NOT_RESUMED = "NOT_RESUMED";
    /** Info am ersetzten wartenden Teil. */
    static final String REPLACED = "Durch einen neueren Aufruf ersetzt: er lädt diese Seiten mit";
    private static final String COMPONENT = "reading-prefetch";
    private static final String SEPARATOR = "\n";

    private volatile com.aresstack.spaceharvester.application.background.BackgroundJobs hosted;
    private volatile AccessContext technical;
    /** Aufrufer der jüngsten Aufrufe, älteste zuerst verdrängt; danach und nach einem Neustart gilt {@link #technical}. */
    private final Map<String, AccessContext> callers = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AccessContext> eldest) {
                    return size() > 256;
                }
            });

    /**
     * Macht das Vorladen sichtbar (Fundstelle 15, Abnahme PR 4): je Aufruf und Quelle ein Teil der Art {@value #KIND},
     * höchstens ein wartender je Quelle; ein neuer Aufruf ersetzt ihn und nimmt seine Seiten hinter den eigenen mit.
     * Innen höchstens {@value #PREFETCH_PARALLEL} zugleich, nie mit Anmelde- oder Codedialog. Ist die Quelle nicht
     * erreichbar, verlangt sie eine Anmeldung oder drosselt sie, startet der Teil keine weitere Seite und endet
     * gescheitert mit Grund, ohne automatische Wiederholung.
     *
     * @param technical der technische Aufrufer des Dienstes, für fortgesetzte Teile nach einem Neustart
     */
    public void background(com.aresstack.spaceharvester.application.background.BackgroundJobs background,
                           AccessContext technical) {
        Objects.requireNonNull(background, "background");
        this.technical = Objects.requireNonNull(technical, "technical");
        background.register(new com.aresstack.spaceharvester.application.background.BackgroundKind(KIND,
                this::work));
        this.hosted = background;
    }

    private synchronized void prefetchAsJob(AccessContext caller, String sourceId, Collection<String> pageIds) {
        com.aresstack.spaceharvester.application.background.BackgroundJobs jobs = hosted;
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
        for (String pageId : pageIds) {
            if (!prefetching.contains(new PageKey(sourceId, pageId))) {
                wanted.add(pageId);
            }
        }
        // Höchstens ein wartender Teil je Quelle: Er geht in diesem auf, seine Seiten hinter den neuen.
        for (com.aresstack.spaceharvester.application.jobs.JobStatus waiting : jobs.parts(KIND)) {
            if (waiting.state() == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED
                    && sourceId.equals(waiting.sourceId()) && waiting.parameter(PAGES) != null) {
                for (String pageId : waiting.parameter(PAGES).split(SEPARATOR)) {
                    if (!pageId.isBlank()) {
                        wanted.add(pageId);
                    }
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        jobs.cancelWaiting(KIND, sourceId, null, REPLACED);
        List<String> limited = wanted.stream().limit(MAX_PREFETCH).toList();
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put(PAGES, String.join(SEPARATOR, limited));
        String groupId = com.aresstack.spaceharvester.application.background.BackgroundJobs.newGroupId();
        callers.put(groupId, caller);
        if (caller.credential() != null) {
            String token = java.util.UUID.randomUUID().toString();
            callers.put(token, caller);
            parameters.put(CALLER, token);
            parameters.put(OWNER, caller.jobOwner());
        }
        try {
            jobs.start(groupId, KIND, TITLE, List.of(
                    new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft(sourceId, null,
                            parameters)));
        } catch (RuntimeException e) {
            // Voll oder beendet: Das Öffnen lädt selbst.
            LOG.log(System.Logger.Level.DEBUG, "Vorladen in {0} nicht als Auftrag gestartet ({1})", sourceId,
                    e.getClass().getName());
        }
    }

    private com.aresstack.spaceharvester.application.jobs.JobTask work(
            com.aresstack.spaceharvester.application.background.BackgroundPart part) {
        String token = part.parameter(CALLER);
        AccessContext caller = token != null ? callers.get(token)
                : callers.getOrDefault(part.groupId(), technical);
        String sourceId = part.sourceId();
        List<String> pageIds = new java.util.ArrayList<>();
        String pages = part.parameter(PAGES);
        if (pages != null) {
            for (String pageId : pages.split(SEPARATOR)) {
                if (!pageId.isBlank()) {
                    pageIds.add(pageId);
                }
            }
        }
        return com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
            if (caller == null) {
                // Nie mit technischen Rechten für einen Benutzer weiterladen.
                context.record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.warning(
                        com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, COMPONENT, NOT_RESUMED,
                        "Nach dem Neustart nicht fortgesetzt: Die Anmeldung des Benutzers lag nur im Speicher"));
                context.incomplete();
                return;
            }
            prefetchAll(context, caller, sourceId, pageIds);
        });
    }

    /** Der Teil: höchstens {@value #PREFETCH_PARALLEL} zugleich; eine nicht erreichbare Quelle beendet ihn rot. */
    private void prefetchAll(com.aresstack.spaceharvester.application.jobs.JobContext context, AccessContext caller,
                             String sourceId, List<String> pageIds) throws InterruptedException {
        context.discovered(pageIds.size());
        Semaphore slots = new Semaphore(PREFETCH_PARALLEL);
        java.util.concurrent.atomic.AtomicReference<SourceAccessException> gaveUp =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean anyFailed = new java.util.concurrent.atomic.AtomicBoolean();
        try (ExecutorService runner = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reading-prefetch-job-", 0).factory())) {
            for (String pageId : pageIds) {
                context.checkpoint();
                slots.acquire();
                PageKey key = new PageKey(sourceId, pageId);
                if (gaveUp.get() != null || !prefetching.add(key)) {
                    slots.release();
                    context.processed(1);
                    if (gaveUp.get() != null) {
                        break;
                    }
                    continue;
                }
                runner.execute(() -> {
                    try {
                        ChangeTrigger.BACKGROUND.call(() -> view(caller, sourceId, pageId, false));
                        context.written(1);
                    } catch (SourceAccessException e) {
                        if (e.reason() != SourceAccessException.Reason.NOT_FOUND) {
                            gaveUp.compareAndSet(null, e);
                            context.failed(1);
                        }
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.DEBUG, "Vorladen einer Lese-Ansicht in {0} ausgelassen ({1})",
                                sourceId, e.getClass().getName());
                        anyFailed.set(true);
                        context.failed(1);
                    } finally {
                        context.processed(1);
                        prefetching.remove(key);
                        slots.release();
                    }
                });
            }
        }
        SourceAccessException unavailable = gaveUp.get();
        if (unavailable != null) {
            // Abnahme PR 4: kein bewusstes Auslassen, sondern ein Fehler mit Grund, ohne automatische Wiederholung.
            throw new com.aresstack.spaceharvester.application.jobs.JobFailedException(SOURCE_UNAVAILABLE,
                    "Quelle " + sourceId + " nicht erreichbar (" + unavailable.reason() + "); weitere Seiten nicht "
                            + "vorgeladen");
        }
        if (anyFailed.get()) {
            context.incomplete();
        }
    }

    private void remember(StoredReadingView view) {
        if (remembered.size() >= PRUNE_AT) {
            remembered.values().removeIf(reference -> reference.get() == null);
        }
        remembered.put(view.key(), new SoftReference<>(new Remembered(view.rawSha256(), view)));
    }

    /** @return {@code null}, wenn die gelieferte Ansicht nicht zur geprüften Version gehört */
    private ReadingView view(AccessContext caller, DeliveredPage page, boolean refresh) {
        String sourceId = page.header().key().sourceId();
        ForeignContent foreign = ForeignContent.of(page.snapshot());
        if (foreign.present() && !access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT)) {
            return ReadingView.without(page.header(), ReadingView.Status.RESTRICTED, foreign);
        }
        String rawSha256 = page.snapshot().payload().rawSha256();
        Optional<StoredReadingView> stored = memory(page.header().key(), rawSha256)
                .or(() -> storedQuietly(page).filter(view -> view.rawSha256().equals(rawSha256)));
        if (stored.isPresent() && !refresh && !foreign.present()) {
            remember(stored.get());
            return delivered(page, stored.get(), foreign, true);
        }
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        HarvestSource.ReadingPage live;
        try {
            live = source.readingPage(page.header().key().pageId());
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                return ReadingView.without(page.header(), ReadingView.Status.UNSUPPORTED, foreign);
            }
            boolean unreachable = e.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || e.reason() == SourceAccessException.Reason.RATE_LIMITED;
            if (unreachable && !refresh && stored.isPresent()) {
                LOG.log(System.Logger.Level.INFO, "Quelle {0} für die Lese-Ansicht nicht erreichbar ({1}), "
                        + "abgelegte Ansicht ausgeliefert", sourceId, e.reason());
                return delivered(page, stored.get(), foreign, true);
            }
            throw e;
        }
        String version = page.snapshot().metadata().version();
        if (live.version() == null || !live.version().equals(version)) {
            return null;
        }
        StoredReadingView fresh = new StoredReadingView(page.header().key(), rawSha256, version, live.html(),
                live.baseUri(), live.text(), live.images(), clock.instant());
        saveQuietly(fresh);
        if (!foreign.present()) {
            remember(fresh);
        }
        return delivered(page, fresh, foreign, false);
    }

    private static ReadingView delivered(DeliveredPage page, StoredReadingView view, ForeignContent foreign,
                                         boolean stored) {
        return new ReadingView(page.header(), ReadingView.Status.AVAILABLE, view.version(), view.html(),
                view.baseUri(), view.text(), view.images(), foreign, stored, view.renderedAt());
    }

    /** Lesen aus der Ablage darf die Ansicht nie verhindern; ohne Ablage wird neu abgerufen. */
    private Optional<StoredReadingView> storedQuietly(DeliveredPage page) {
        try {
            return store.find(page.header().key());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Abgelegte Lese-Ansicht nicht lesbar ({0})", e.getClass().getName());
            return Optional.empty();
        }
    }

    /** Ablegen darf die Auslieferung nie verhindern; der nächste Abruf legt erneut ab. */
    private void saveQuietly(StoredReadingView view) {
        try {
            store.save(view);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Lese-Ansicht nicht abgelegt ({0})", e.getClass().getName());
        }
    }
}
