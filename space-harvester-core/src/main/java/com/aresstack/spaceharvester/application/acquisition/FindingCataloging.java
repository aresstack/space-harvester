package com.aresstack.spaceharvester.application.acquisition;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
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

    private record Task(AccessContext finder, PageKey key, String version) {
    }

    private record Checked(String version, Instant at) {
    }

    private final SourceCatalog sources;
    private final AccessGuard access;
    private final Store store;
    private final Consumer<List<BackgroundFill.Gap>> catalogued;
    private final Clock clock;
    private final Object monitor = new Object();
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
        this.sources = Objects.requireNonNull(sources, "sources");
        this.access = Objects.requireNonNull(access, "access");
        this.store = Objects.requireNonNull(store, "store");
        this.catalogued = Objects.requireNonNull(catalogued, "catalogued");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void offer(AccessContext finder, String sourceId, List<Finding> findings) {
        if (finder == null || finder.kind() == AccessContext.PrincipalKind.END_USER || finder.credential() != null
                || sourceId == null || findings == null || findings.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        synchronized (monitor) {
            if (closed) {
                return;
            }
            for (Finding finding : findings) {
                if (finding == null) {
                    continue;
                }
                PageKey key = new PageKey(sourceId, finding.pageId());
                Checked last = checked.get(key);
                if (last != null && now.isBefore(last.at().plus(RECHECK))
                        && (finding.version() == null || finding.version().equals(last.version()))) {
                    continue;
                }
                if (queued.contains(key)) {
                    continue;
                }
                if (waiting.size() >= CAPACITY) {
                    dropped++;
                    continue;
                }
                queued.add(key);
                waiting.add(new Task(finder, key, finding.version()));
            }
            workers.removeIf(worker -> !worker.isAlive());
            while (workers.size() < PARALLELISM && workers.size() < waiting.size()) {
                workers.add(Thread.ofVirtual().name("finding-catalog").start(this::drain));
            }
        }
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
            try {
                version = catalog(task);
            } catch (RuntimeException e) {
                count(() -> failed++);
                LOG.log(System.Logger.Level.DEBUG, "Fund in {0} nicht katalogisiert ({1})", task.key().sourceId(),
                        e.getClass().getName());
            } finally {
                Instant at = clock.instant();
                String seen = version;
                synchronized (monitor) {
                    queued.remove(task.key());
                    checked.put(task.key(), new Checked(seen, at));
                }
            }
        }
    }

    /** @return die Version, die die Seite jetzt hat, soweit bekannt */
    private String catalog(Task task) {
        PageKey key = task.key();
        HarvestSource source = sources.find(key.sourceId()).orElse(null);
        if (source == null || !source.capabilities().contains(SourceCapability.PAGE_METADATA)) {
            return task.version();
        }
        Optional<Entry> entry = store.entry(key);
        if (entry.isPresent() && (entry.get().withheld() || task.version() == null
                || task.version().equals(entry.get().revision()))) {
            // Gesperrt, oder die Suche nennt keine andere Version als der Katalog: nichts zu fragen.
            count(() -> unchanged++);
            return entry.get().withheld() ? task.version() : entry.get().revision();
        }
        PageMetadata metadata;
        try {
            metadata = source.pageMetadata(key.pageId());
        } catch (SourceAccessException e) {
            // Ohne Seiten-ID: eine nicht mehr sichtbare Seite kommt nicht in den Katalog.
            count(() -> failed++);
            LOG.log(System.Logger.Level.DEBUG, "Kopfdaten eines Fundes in {0} nicht lesbar ({1})", key.sourceId(),
                    e.reason());
            return task.version();
        }
        if (!key.pageId().equals(metadata.pageId())) {
            count(() -> failed++);
            return task.version();
        }
        if (!access.permits(task.finder(), ResourceScope.page(key.sourceId(), metadata.partitionKey(), key.pageId()),
                AccessKind.METADATA)) {
            // Von der Richtlinie ausgeschlossen: kein Eintrag, kein bestehender wird angefasst.
            count(() -> excluded++);
            return metadata.version();
        }
        Outcome outcome = store.catalog(PageHeader.of(key.sourceId(), metadata, clock.instant()));
        switch (outcome) {
            case NOT_LOADED -> {
                count(() -> newEntries++);
                report(new BackgroundFill.Gap(key, BackgroundFill.Kind.NOT_LOADED, metadata.modifiedAt()));
            }
            case OUTDATED -> {
                count(() -> outdated++);
                report(new BackgroundFill.Gap(key, BackgroundFill.Kind.OUTDATED, metadata.modifiedAt()));
            }
            case BUSY -> {
                // Gleich wieder versuchen lohnt nicht; die nächste Suche bietet die Seite erneut an.
                return null;
            }
            default -> count(() -> unchanged++);
        }
        return metadata.version();
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
