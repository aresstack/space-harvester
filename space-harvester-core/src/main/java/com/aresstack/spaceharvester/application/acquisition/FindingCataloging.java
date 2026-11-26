package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Katalogisiert die Funde der Quellensuche als Kopfdaten (ADR 0016): Ein Fund, der noch nicht im Katalog steht, kommt
 * als Eintrag ohne Inhalt hinein ({@link BackgroundFill.Kind#NOT_LOADED}); nennt die Suche für einen Eintrag eine
 * andere Version, bekommt er die neuen Kopfdaten und ist damit {@link BackgroundFill.Kind#OUTDATED}. Beides meldet
 * {@code catalogued}, damit die {@link BackgroundFill} die Seiten lädt, auch in Spaces ohne bestätigte Generation.
 *
 * <p><b>Woher der Space kommt.</b> Die Funde nennen ihren Space nicht (der Enterprise AI Client reicht ihn in
 * {@code SourceSearchHit} nicht durch). Vollständige Kopfdaten mit Space, Elternseite und Attributen braucht der
 * Katalog ohnehin, denn {@code page-metadata/v1} und {@code page-attributes/v1} hashen sie. Deshalb liest das
 * Katalogisieren die Kopfdaten jeder neuen oder geänderten Seite einzeln bei der Quelle
 * ({@link HarvestSource#pageMetadata(String)}), mit deren Zugangsdaten, also als technische Aktion. Unveränderte
 * Einträge kosten keine Anfrage.
 *
 * <p><b>Rechte.</b> Nur Funde eines technischen Aufrufers ohne Benutzeranmeldung zählen; die eines Endnutzers fallen
 * still weg, damit seine Suche keinen Katalogeintrag und keine Zahl verändert, an der er Seiten erkennen könnte, die er
 * nicht sehen darf. Katalogisiert wird nur, was die Richtlinie diesem Aufrufer an der gelesenen Seite samt Space für
 * {@link AccessKind#METADATA} freigibt. Gesperrte Seiten ({@code WITHHELD}) und bestehende Einträge, die die Richtlinie
 * inzwischen ausschließt, bleiben unberührt.
 *
 * <p><b>Nie zulasten der Suche.</b> {@link #offer} reiht nur ein und kehrt sofort zurück; höchstens
 * {@value #PARALLELISM} Kopfdatenabrufe laufen zugleich auf virtuellen Threads, höchstens {@value #CAPACITY} warten,
 * was darüber hinausgeht, fällt weg. Eine Seite, die eben geprüft wurde, fragt erst nach {@link #RECHECK} wieder.
 * Fehler zählen nur. Protokolliert werden Quellen und Gründe, nie Seiten-IDs oder Suchtexte.
 *
 * <p><b>Sichtbar als Auftrag</b> (Fundstelle 14, Abnahme PR 4): Mit {@link #background} ist das Katalogisieren die
 * Hintergrundart {@value #KIND} „{@value #TITLE}“, je Angebot und Quelle ein Teil mit den Seiten-IDs in den Angaben
 * ({@value #PAGES}). Höchstens ein wartender Teil je Quelle, Aufrufer und Anlass: Ein neues Angebot ersetzt ihn und
 * nimmt seine Funde hinter den eigenen mit. Ein Angebot ohne neue Funde (etwa dieselbe Suche neu sortiert) legt keinen
 * Teil an. {@link #RECHECK} gilt weiter, auch beim Fortsetzen. Der technische Aufrufer steht als Kennung am Teil
 * ({@value #FINDER}); ein Teil setzt so auch nach einem Neustart mit seinen Rechten fort. Bei nur einem Platz
 * ({@link com.aresstack.spaceharvester.application.background.BackgroundJobs#canRun()}) bleibt es beim bisherigen Weg.
 */
public final class FindingCataloging implements FindingCatalog, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FindingCataloging.class.getName());

    /** Gleichzeitige Kopfdatenabrufe. */
    static final int PARALLELISM = 2;
    /** Höchstens so viele Funde warten. */
    static final int CAPACITY = 1000;
    /** So viele eben geprüfte Seiten merkt sich das Katalogisieren. */
    static final int REMEMBERED = 10_000;
    /** Frühestens so lange nach der letzten Prüfung fragt eine Seite wieder bei der Quelle. */
    static final Duration RECHECK = Duration.ofMinutes(10);

    /** Was das Ablegen der Kopfdaten ergab. */
    public enum Outcome {
        /** Neu im Katalog, ohne Inhalt. */
        NOT_LOADED,
        /** Neue Kopfdaten mit anderer Version als der gespeicherte Inhalt. */
        OUTDATED,
        /** Neue Kopfdaten, der gespeicherte Inhalt hat schon diese Version. */
        CURRENT,
        /** Nichts geschrieben: der Eintrag nennt dieselbe Version oder ist neuer. */
        UNCHANGED,
        /** Nichts geschrieben: die Seite ist gesperrt. */
        WITHHELD,
        /** Nichts geschrieben: der Cache war gerade für einen anderen Schreiblauf gesperrt. */
        BUSY
    }

    /**
     * Ein Eintrag des Katalogs.
     *
     * @param withheld ob die Seite gesperrt ist ({@code WITHHELD})
     * @param revision Version laut Kopfdaten oder {@code null}, wenn unbekannt
     */
    public record Entry(boolean withheld, String revision) {
    }

    /** Der Katalog im Cache; umgesetzt im Lucene-Cache. */
    public interface Store {

        /** Der Eintrag der Seite, leer, wenn der Katalog sie nicht kennt. */
        Optional<Entry> entry(PageKey key);

        /**
         * Legt die Kopfdaten ab, wenn die Seite fehlt oder ihr Eintrag eine andere Version nennt, nie für eine
         * gesperrte Seite und nie über neuere Kopfdaten; ein gespeicherter Inhalt bleibt. Wartet nicht lange auf die
         * Schreibsperre.
         */
        Outcome catalog(PageHeader header);
    }

    /** Zähler seit dem Start, nur für Betrieb und Tests. */
    public record Counts(long catalogued, long outdated, long unchanged, long excluded, long failed, long dropped) {
    }

    /** Kennung der Hintergrundart. */
    public static final String KIND = "catalog";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String TITLE = "Funde katalogisieren";
    /** Angabe: die Funde des Teils, je Zeile Seiten-ID, Tabulator und Version (leer, wenn unbekannt). */
    public static final String PAGES = "pages";
    /** Angabe: Kennung des technischen Aufrufers, dessen Rechte gelten. */
    public static final String FINDER = "finder";
    /** Angabe: Anlass im Änderungsprotokoll (ADR 0030). */
    public static final String CHANGE_TRIGGER = "change-trigger";
    /** Info am ersetzten wartenden Teil. */
    static final String REPLACED = "Durch ein neueres Angebot ersetzt: es katalogisiert diese Funde mit";
    private static final String COMPONENT = "finding-catalog";

    /** Was das Katalogisieren einer Seite für die Zähler eines Teils bedeutet. */
    private enum Effect {
        WRITTEN, SKIPPED, FAILED
    }

    /** Ergebnis einer Seite: die Version, die sie jetzt hat, soweit bekannt, und die Wirkung. */
    private record Done(String version, Effect effect) {
    }

    /** {@code trigger}: Auslöser des Anbietenden (ADR 0030), der Arbeiter-Thread kennt ihn sonst nicht. */
    private record Task(AccessContext finder, PageKey key, String version, ChangeTrigger trigger) {
    }

    private record Checked(String version, Instant at) {
    }

    private final SourceCatalog sources;
    private final AccessGuard access;
    private final Store store;
    private final Consumer<List<BackgroundFill.Gap>> catalogued;
    private final Clock clock;
    private final java.util.function.Predicate<String> acquisitionAllowed;
    /** Ausgeschlossene Funde kommen nicht in den Katalog (Angelo 26.11.2026); ohne Aufruf ist nichts ausgeschlossen. */
    private volatile com.aresstack.spaceharvester.application.sync.Exclusions exclusions =
            com.aresstack.spaceharvester.application.sync.Exclusions.NONE;
    private final Object monitor = new Object();
    /**
     * Höchstens {@value #PARALLELISM} Kopfdatenabrufe zugleich, über alle Teile und den bisherigen Weg hinweg
     * (Codex-Befund #1082): So bleibt die Quelle auch bei vielen Plätzen unter „Vorbereitung“ geschont.
     */
    private final java.util.concurrent.Semaphore metadataSlots = new java.util.concurrent.Semaphore(PARALLELISM);
    private final Deque<Task> waiting = new ArrayDeque<>();
    private final Set<PageKey> queued = new HashSet<>();
    private final LinkedHashMap<PageKey, Checked> checked = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PageKey, Checked> eldest) {
            return size() > REMEMBERED;
        }
    };
    private final List<Thread> workers = new ArrayList<>();
    private boolean closed;
    private volatile com.aresstack.spaceharvester.application.background.BackgroundJobs hosted;
    private long newEntries;
    private long outdated;
    private long unchanged;
    private long excluded;
    private long failed;
    private long dropped;

    /**
     * @param catalogued erfährt neue Lücken, etwa {@link BackgroundFill#offer}; Fehler dort zählen nicht
     */
    public FindingCataloging(SourceCatalog sources, AccessGuard access, Store store,
                             Consumer<List<BackgroundFill.Gap>> catalogued, Clock clock) {
        this(sources, access, store, catalogued, clock, id -> true);
    }

    public FindingCataloging(SourceCatalog sources, AccessGuard access, Store store,
                             Consumer<List<BackgroundFill.Gap>> catalogued, Clock clock,
                             java.util.function.Predicate<String> acquisitionAllowed) {
        this.acquisitionAllowed = Objects.requireNonNull(acquisitionAllowed, "acquisitionAllowed");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.access = Objects.requireNonNull(access, "access");
        this.store = Objects.requireNonNull(store, "store");
        this.catalogued = Objects.requireNonNull(catalogued, "catalogued");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void offer(AccessContext finder, String sourceId, List<Finding> findings) {
        accept(finder, sourceId, findings);
    }

    /** Ausgeschlossenes katalogisiert die Fundübernahme nicht. */
    public void exclusions(com.aresstack.spaceharvester.application.sync.Exclusions exclusions) {
        this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
    }

    /**
     * Wie {@link #offer}; nennt die angenommenen Seiten (Abnahme #1082): auch eben geprüfte und solche, die ein offener
     * Teil schon in derselben Fassung katalogisiert. Was nicht angenommen ist, weil der wartende Teil oder die
     * Warteschlange voll ist, bietet der Anbietende später wieder an. Funde, die nie zählen (Endnutzer, Delegierte),
     * gelten als angenommen. Kehrt sofort zurück: Teile legt ein eigener Arbeiter an.
     */
    @Override
    public Set<String> accept(AccessContext finder, String sourceId, List<Finding> findings) {
        Set<String> accepted = new java.util.LinkedHashSet<>();
        if (findings == null) {
            return accepted;
        }
        if (finder == null || finder.kind() == AccessContext.PrincipalKind.END_USER || finder.credential() != null
                || sourceId == null || !acquisitionAllowed.test(sourceId) || findings.isEmpty()) {
            findings.forEach(finding -> {
                if (finding != null) {
                    accepted.add(finding.pageId());
                }
            });
            return accepted;
        }
        Instant now = clock.instant();
        ChangeTrigger trigger = ChangeTrigger.currentOr(ChangeTrigger.SEARCH);
        com.aresstack.spaceharvester.application.background.BackgroundJobs jobs = hosted;
        if (jobs != null && finder.kind() == AccessContext.PrincipalKind.TECHNICAL_CLIENT && jobs.canRun()) {
            return acceptForJob(finder, sourceId, findings, trigger, now);
        }
        synchronized (monitor) {
            if (closed) {
                return accepted;
            }
            for (Finding finding : findings) {
                if (finding == null) {
                    continue;
                }
                PageKey key = new PageKey(sourceId, finding.pageId());
                Checked last = checked.get(key);
                if (last != null && now.isBefore(last.at().plus(RECHECK))
                        && (finding.version() == null || finding.version().equals(last.version()))) {
                    accepted.add(finding.pageId());
                    continue;
                }
                if (queued.contains(key)) {
                    accepted.add(finding.pageId());
                    continue;
                }
                if (waiting.size() >= CAPACITY) {
                    dropped++;
                    continue;
                }
                queued.add(key);
                waiting.add(new Task(finder, key, finding.version(), trigger));
                accepted.add(finding.pageId());
            }
            workers.removeIf(worker -> !worker.isAlive());
            while (workers.size() < PARALLELISM && workers.size() < waiting.size()) {
                workers.add(Thread.ofVirtual().name("finding-catalog").start(this::drain));
            }
        }
        return accepted;
    }

    /** Zähler seit dem Start. */
    public Counts counts() {
        synchronized (monitor) {
            return new Counts(newEntries, outdated, unchanged, excluded, failed, dropped);
        }
    }

    /** Wartet höchstens {@code timeout}, bis nichts mehr wartet oder läuft; für Tests. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (monitor) {
                if (queued.isEmpty()) {
                    return true;
                }
            }
            Thread.sleep(5);
        }
        return false;
    }

    private void drain() {
        while (true) {
            Task task;
            synchronized (monitor) {
                task = closed ? null : waiting.poll();
                if (task == null) {
                    workers.remove(Thread.currentThread());
                    return;
                }
            }
            String version = task.version();
            boolean slot = false;
            try {
                metadataSlots.acquire();
                slot = true;
                version = task.trigger().call(() -> catalog(task)).version();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                count(() -> failed++);
                LOG.log(System.Logger.Level.DEBUG, "Fund in {0} nicht katalogisiert ({1})", task.key().sourceId(),
                        e.getClass().getName());
            } finally {
                if (slot) {
                    metadataSlots.release();
                }
                Instant at = clock.instant();
                String seen = version;
                synchronized (monitor) {
                    queued.remove(task.key());
                    checked.put(task.key(), new Checked(seen, at));
                }
            }
        }
    }

    /** @return die Version, die die Seite jetzt hat, soweit bekannt, und was geschah */
    private Done catalog(Task task) {
        PageKey key = task.key();
        if (!acquisitionAllowed.test(key.sourceId())) {
            return new Done(null, Effect.SKIPPED);
        }
        HarvestSource source = sources.find(key.sourceId()).orElse(null);
        if (source == null || !source.capabilities().contains(SourceCapability.PAGE_METADATA)) {
            return new Done(task.version(), Effect.SKIPPED);
        }
        Optional<Entry> entry = store.entry(key);
        if (entry.isPresent() && (entry.get().withheld() || task.version() == null
                || task.version().equals(entry.get().revision()))) {
            // Gesperrt, oder die Suche nennt keine andere Version als der Katalog: nichts zu fragen.
            count(() -> unchanged++);
            return new Done(entry.get().withheld() ? task.version() : entry.get().revision(), Effect.SKIPPED);
        }
        PageMetadata metadata;
        try {
            metadata = source.pageMetadata(key.pageId());
        } catch (SourceAccessException e) {
            // Ohne Seiten-ID: eine nicht mehr sichtbare Seite kommt nicht in den Katalog.
            count(() -> failed++);
            LOG.log(System.Logger.Level.DEBUG, "Kopfdaten eines Fundes in {0} nicht lesbar ({1})", key.sourceId(),
                    e.reason());
            return new Done(task.version(), Effect.FAILED);
        }
        if (!key.pageId().equals(metadata.pageId())) {
            count(() -> failed++);
            return new Done(task.version(), Effect.FAILED);
        }
        if (!access.permits(task.finder(), ResourceScope.page(key.sourceId(), metadata.partitionKey(), key.pageId()),
                AccessKind.METADATA)) {
            // Von der Richtlinie ausgeschlossen: kein Eintrag, kein bestehender wird angefasst.
            count(() -> excluded++);
            return new Done(metadata.version(), Effect.SKIPPED);
        }
        if (!acquisitionAllowed.test(key.sourceId())) {
            return new Done(null, Effect.SKIPPED);
        }
        if (exclusions.within(key.sourceId(), metadata.partitionKey(),
                pageId -> pageId.equals(key.pageId()) ? metadata.parentPageId() : null).test(key.pageId())) {
            // Ausgeschlossen: kein Eintrag, keine Lücke für die Hintergrundfüllung; ein bestehender bleibt.
            count(() -> excluded++);
            return new Done(metadata.version(), Effect.SKIPPED);
        }
        Outcome outcome = store.catalog(PageHeader.of(key.sourceId(), metadata, clock.instant()));
        switch (outcome) {
            case NOT_LOADED -> {
                count(() -> newEntries++);
                report(new BackgroundFill.Gap(key, BackgroundFill.Kind.NOT_LOADED, metadata.modifiedAt()));
                return new Done(metadata.version(), Effect.WRITTEN);
            }
            case OUTDATED -> {
                count(() -> outdated++);
                report(new BackgroundFill.Gap(key, BackgroundFill.Kind.OUTDATED, metadata.modifiedAt()));
                return new Done(metadata.version(), Effect.WRITTEN);
            }
            case CURRENT -> {
                count(() -> unchanged++);
                return new Done(metadata.version(), Effect.WRITTEN);
            }
            case BUSY -> {
                // Gleich wieder versuchen lohnt nicht; die nächste Suche bietet die Seite erneut an.
                return new Done(null, Effect.SKIPPED);
            }
            default -> {
                count(() -> unchanged++);
                return new Done(metadata.version(), Effect.SKIPPED);
            }
        }
    }

    // --- Als Hintergrundauftrag (Fundstelle 14) ------------------------------------------------------------------

    /** Wessen Funde ein Teil katalogisiert: Quelle, technischer Aufrufer und Anlass. */
    private record BatchKey(String sourceId, String finder, ChangeTrigger trigger) {
    }

    /** Ein offener Teil im Speicher (Abnahme #1082): was er noch katalogisiert und ob er noch wartet. */
    private static final class Open {

        final String partId;
        final BatchKey key;
        /** Seiten-ID und Fassung, die er noch nicht erledigt hat. */
        final LinkedHashMap<String, String> remaining;
        boolean waiting;
        boolean started;

        Open(String partId, BatchKey key, LinkedHashMap<String, String> remaining, boolean waiting) {
            this.partId = partId;
            this.key = key;
            this.remaining = remaining;
            this.waiting = waiting;
        }
    }

    /** Offene Teile nach ID, gepflegt über Anlegen, Arbeit und Auftragsmeldungen; nie über den ganzen Speicher. */
    private final Map<String, Open> open = new java.util.HashMap<>();
    /**
     * Was {@link #flush} gerade als Teil anlegt, je Schlüssel, bis der Teil in {@link #open} steht; unter monitor.
     * Sonst sähe ein Angebot dazwischen keinen wartenden Teil und nähme über {@link #CAPACITY} hinaus an.
     */
    private final Map<BatchKey, LinkedHashMap<String, String>> flushing = new java.util.HashMap<>();
    /** Angenommene Funde je Schlüssel, die der Arbeiter noch in einen Teil legt. */
    private final Map<BatchKey, LinkedHashMap<String, String>> accepted = new java.util.HashMap<>();
    /** Teile, deren Arbeit schon endete; ein spätes Eintragen legt sie nicht wieder an. */
    private final Set<String> ended = java.util.Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    });
    private volatile com.aresstack.spaceharvester.application.observe.Coalescer<BatchKey, String> flusher;
    private volatile com.aresstack.spaceharvester.application.observe.Subscription watching;

    /**
     * Macht das Katalogisieren als Hintergrundart {@value #KIND} sichtbar. Liest die wartenden Teile einmal ein und
     * folgt danach den Auftragsmeldungen ihrer Art.
     */
    public void background(com.aresstack.spaceharvester.application.background.BackgroundJobs background) {
        Objects.requireNonNull(background, "background");
        background.register(new com.aresstack.spaceharvester.application.background.BackgroundKind(KIND,
                this::work));
        synchronized (monitor) {
            for (com.aresstack.spaceharvester.application.jobs.JobStatus part : background.parts(KIND)) {
                if (part.state() == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED) {
                    BatchKey key = keyOf(part.sourceId(), part.parameter(FINDER), part.parameter(CHANGE_TRIGGER));
                    if (key != null) {
                        open.putIfAbsent(part.id(), new Open(part.id(), key, entries(part.parameter(PAGES)), true));
                    }
                }
            }
        }
        this.flusher = new com.aresstack.spaceharvester.application.observe.Coalescer<>("finding-catalog",
                (key, ignored) -> flush(background, key));
        this.watching = background.subscribe(KIND, this::changed, last -> forgetPart(last.id()));
        this.hosted = background;
    }

    private static BatchKey keyOf(String sourceId, String finder, String trigger) {
        if (sourceId == null || finder == null) {
            return null;
        }
        ChangeTrigger cause;
        try {
            cause = trigger == null ? ChangeTrigger.SEARCH : ChangeTrigger.valueOf(trigger);
        } catch (IllegalArgumentException e) {
            cause = ChangeTrigger.SEARCH;
        }
        return new BatchKey(sourceId, finder, cause);
    }

    /** Eine Auftragsmeldung: Läuft der Teil, wartet er nicht mehr; endet oder hält er an, zählt er nicht mehr. */
    private void changed(com.aresstack.spaceharvester.application.jobs.JobStatus status) {
        com.aresstack.spaceharvester.application.jobs.JobState state = status.state();
        if (state == com.aresstack.spaceharvester.application.jobs.JobState.RUNNING) {
            synchronized (monitor) {
                Open known = open.get(status.id());
                if (known != null) {
                    known.waiting = false;
                }
            }
        } else if (state != com.aresstack.spaceharvester.application.jobs.JobState.QUEUED) {
            forgetPart(status.id());
        }
    }

    private void forgetPart(String partId) {
        synchronized (monitor) {
            open.remove(partId);
        }
    }

    /** Unter {@link #monitor}: der wartende Teil dieses Schlüssels, oder {@code null}. */
    private Open waitingFor(BatchKey key) {
        for (Open part : open.values()) {
            if (part.waiting && !part.started && part.key.equals(key)) {
                return part;
            }
        }
        return null;
    }

    /** Unter {@link #monitor}: ob ein offener Teil der Quelle die Seite in dieser Fassung noch katalogisiert. */
    private boolean covered(String sourceId, Finding finding) {
        for (Open part : open.values()) {
            if (part.key.sourceId().equals(sourceId) && part.remaining.containsKey(finding.pageId())) {
                String version = part.remaining.get(finding.pageId());
                if (finding.version() == null || finding.version().equals(version)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> acceptForJob(AccessContext finder, String sourceId, List<Finding> findings,
                                     ChangeTrigger trigger, Instant now) {
        Set<String> taken = new java.util.LinkedHashSet<>();
        BatchKey key = new BatchKey(sourceId, finder.principal(), trigger);
        boolean added = false;
        synchronized (monitor) {
            if (closed) {
                return taken;
            }
            LinkedHashMap<String, String> pending = accepted.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            Open waitingPart = waitingFor(key);
            // Ein Teil, der gerade angelegt wird, umfasst den bisherigen wartenden samt Angenommenem.
            Map<String, String> inPart = flushing.containsKey(key) ? flushing.get(key)
                    : waitingPart == null ? null : waitingPart.remaining;
            int room = CAPACITY - (inPart == null ? 0 : inPart.size()) - pending.size();
            for (Finding finding : findings) {
                if (finding == null) {
                    continue;
                }
                Checked last = checked.get(new PageKey(sourceId, finding.pageId()));
                if (last != null && now.isBefore(last.at().plus(RECHECK))
                        && (finding.version() == null || finding.version().equals(last.version()))
                        || covered(sourceId, finding)) {
                    taken.add(finding.pageId());
                    continue;
                }
                boolean counted = pending.containsKey(finding.pageId())
                        || inPart != null && inPart.containsKey(finding.pageId());
                if (!counted) {
                    if (room <= 0) {
                        // Abnahme #1082: Angenommenes fällt nie weg; ist der Teil voll, bleibt der Rest draußen.
                        dropped++;
                        continue;
                    }
                    room--;
                }
                // Eine neue Fassung ersetzt die alte im wartenden Teil oder in den angenommenen.
                pending.put(finding.pageId(), finding.version());
                taken.add(finding.pageId());
                added = true;
            }
            if (pending.isEmpty()) {
                accepted.remove(key);
            }
        }
        com.aresstack.spaceharvester.application.observe.Coalescer<BatchKey, String> worker = flusher;
        if (added && worker != null) {
            worker.mark(key);
        }
        return taken;
    }

    /**
     * Legt die angenommenen Funde eines Schlüssels als Teil an, auf dem Thread des Arbeiters. Wartet schon ein Teil,
     * nimmt der neue dessen Funde hinter den eigenen mit; erst danach wird der alte abgebrochen (Abnahme #1082).
     * Scheitert das Anlegen, bleiben der alte Teil und die angenommenen Funde stehen.
     */
    private void flush(com.aresstack.spaceharvester.application.background.BackgroundJobs jobs, BatchKey key) {
        LinkedHashMap<String, String> pending;
        LinkedHashMap<String, String> entries;
        String replaced;
        synchronized (monitor) {
            pending = accepted.remove(key);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            Open old = waitingFor(key);
            entries = new LinkedHashMap<>(pending);
            if (old != null) {
                old.remaining.forEach(entries::putIfAbsent);
            }
            replaced = old == null ? null : old.partId;
            flushing.put(key, entries);
        }
        List<String> lines = new ArrayList<>();
        entries.forEach((pageId, version) -> lines.add(pageId + "\t" + (version == null ? "" : version)));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(PAGES, String.join("\n", lines));
        parameters.put(FINDER, key.finder());
        parameters.put(CHANGE_TRIGGER, key.trigger().name());
        String partId;
        try {
            partId = jobs.start(KIND, TITLE, List.of(new com.aresstack.spaceharvester.application.background
                    .BackgroundPart.Draft(key.sourceId(), null, parameters))).parts().getFirst().id();
        } catch (RuntimeException e) {
            synchronized (monitor) {
                flushing.remove(key);
                // Nichts geht verloren: Die angenommenen Funde warten weiter, der alte Teil bleibt.
                LinkedHashMap<String, String> later = accepted.remove(key);
                if (later != null) {
                    pending.putAll(later);
                }
                accepted.put(key, pending);
            }
            LOG.log(System.Logger.Level.DEBUG, "Funde in {0} nicht als Auftrag eingereiht ({1})", key.sourceId(),
                    e.getClass().getName());
            return;
        }
        synchronized (monitor) {
            flushing.remove(key);
            if (!ended.contains(partId)) {
                open.putIfAbsent(partId, new Open(partId, key, entries, true));
            }
        }
        if (replaced != null && jobs.cancelIfWaiting(replaced, REPLACED).isPresent()) {
            forgetPart(replaced);
        }
    }

    private static LinkedHashMap<String, String> entries(String pages) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        if (pages == null) {
            return entries;
        }
        for (String line : pages.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String pageId = tab < 0 ? line : line.substring(0, tab);
            String version = tab < 0 || tab == line.length() - 1 ? null : line.substring(tab + 1);
            entries.put(pageId, version);
        }
        return entries;
    }

    private com.aresstack.spaceharvester.application.jobs.JobTask work(
            com.aresstack.spaceharvester.application.background.BackgroundPart part) {
        String partId = part.jobId();
        String sourceId = part.sourceId();
        BatchKey key = keyOf(sourceId, part.parameter(FINDER), part.parameter(CHANGE_TRIGGER));
        LinkedHashMap<String, String> entries = entries(part.parameter(PAGES));
        return com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
            if (key == null) {
                context.incomplete();
                return;
            }
            ChangeTrigger cause = key.trigger();
            AccessContext finder = AccessContext.technicalClient(key.finder());
            Open mine;
            synchronized (monitor) {
                // Auch ein fortgesetzter Teil zählt wieder als offen, mit dem, was er noch nicht erledigt hat.
                mine = open.computeIfAbsent(partId, id -> new Open(id, key, new LinkedHashMap<>(entries), false));
                mine.started = true;
                mine.waiting = false;
            }
            context.discovered(entries.size());
            java.util.concurrent.Semaphore slots = metadataSlots;
            try (java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("finding-catalog-job-", 0).factory())) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    context.checkpoint();
                    String pageId = entry.getKey();
                    String listed = entry.getValue();
                    PageKey page = new PageKey(sourceId, pageId);
                    Instant now = clock.instant();
                    boolean recent;
                    synchronized (monitor) {
                        Checked last = checked.get(page);
                        // Fortsetzen oder ein zweiter Teil: eben Geprüftes fragt erst nach RECHECK wieder.
                        recent = last != null && now.isBefore(last.at().plus(RECHECK))
                                && (listed == null || listed.equals(last.version()));
                        if (recent) {
                            mine.remaining.remove(pageId);
                        }
                    }
                    if (recent) {
                        context.processed(1);
                        continue;
                    }
                    slots.acquire();
                    runner.execute(() -> {
                        String version = listed;
                        try {
                            Done done = cause.call(() -> catalog(new Task(finder, page, listed, cause)));
                            version = done.version();
                            if (done.effect() == Effect.WRITTEN) {
                                context.written(1);
                            } else if (done.effect() == Effect.FAILED) {
                                context.failed(1);
                            }
                        } catch (RuntimeException e) {
                            count(() -> failed++);
                            context.failed(1);
                            LOG.log(System.Logger.Level.DEBUG, "Fund in {0} nicht katalogisiert ({1})", sourceId,
                                    e.getClass().getName());
                        } finally {
                            Instant at = clock.instant();
                            String seen = version;
                            synchronized (monitor) {
                                checked.put(page, new Checked(seen, at));
                                mine.remaining.remove(pageId);
                            }
                            context.processed(1);
                            slots.release();
                        }
                    });
                }
            } finally {
                synchronized (monitor) {
                    // Fertig, angehalten oder abgebrochen: zählt nicht mehr als offen.
                    open.remove(partId);
                    ended.add(partId);
                }
            }
        });
    }

    private void report(BackgroundFill.Gap gap) {
        try {
            catalogued.accept(List.of(gap));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Lücke in {0} nicht gemeldet ({1})", gap.key().sourceId(),
                    e.getClass().getName());
        }
    }

    private void count(Runnable increment) {
        synchronized (monitor) {
            increment.run();
        }
    }

    /** Nimmt nichts mehr an und verwirft, was wartet; ein laufender Abruf endet regulär. */
    @Override
    public void close() {
        com.aresstack.spaceharvester.application.observe.Coalescer<BatchKey, String> pendingFlush = flusher;
        if (pendingFlush != null) {
            pendingFlush.close();
        }
        com.aresstack.spaceharvester.application.observe.Subscription subscription = watching;
        if (subscription != null) {
            subscription.close();
        }
        List<Thread> running;
        synchronized (monitor) {
            closed = true;
            waiting.forEach(task -> queued.remove(task.key()));
            waiting.clear();
            running = List.copyOf(workers);
        }
        for (Thread worker : running) {
            try {
                worker.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
