package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Berechnet und liest die Anreicherungen einer Seite. Jeder Prozessor läuft für sich: Scheitert einer, wird nur sein
 * Ergebnis als fehlgeschlagen gespeichert; die übrigen, die Payload und jeder Abgleich oder Export laufen weiter.
 *
 * <p>Prozessoren, die über das Netz rechnen ({@link EnrichmentProcessor#background()}), laufen bei {@link #refresh}
 * in einem begrenzten Hintergrund ({@link #background(int)}): höchstens eine Berechnung zugleich, eine Warteschlange
 * fester Länge, jede Seite höchstens einmal darin. Ist sie voll, wird die Seite übersprungen und beim nächsten
 * Abgleich oder Abruf nachgeholt, weil ihr Ergebnis dann noch fehlt oder veraltet ist.
 *
 * <p>Mit Hintergrundaufträgen ({@link #background(BackgroundJobs, Function)}) ist diese Warteschlange sichtbar (ADR
 * 0025 Nachtrag, Punkt 10): je Quelle ein Teil der Art {@value #BACKGROUND_KIND} „{@value #BACKGROUND_TITLE}“, der die
 * Berechnungen seiner Quelle nacheinander abholt, anhaltbar und abbrechbar. Gewartet wird nur mit Prozessor und Seite,
 * ohne Grenze und ohne Verwerfen; den Snapshot liest der Teil erst beim Rechnen. Die Teile laufen hintereinander, wie
 * vorher der eine Thread.
 */
public class Enrichments {

    /** Fehlercode eines Prozessors, der eine Ausnahme geworfen hat. */
    public static final String PROCESSOR_ERROR = "PROCESSOR_ERROR";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt die Lese-Ansicht (#176) ihre abgeleitete Darstellung in derselben
     * Ablage ab. Sie ist kein Prozessor, erscheint nie in {@link #view} und wird mit der Seite entfernt.
     */
    public static final String READING_VIEW = "reading-view";
    /** Die Marken eines Videos, wie der Player sie gefunden hat ({@code VideoMarks}); kein Analyseergebnis. */
    public static final String VIDEO_MARKS = "video-marks";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt ein Import die Bilder und Anhänge einer Seite ab, die sein Paket
     * mitbrachte ({@code ImportedAssets}). Wie {@link #READING_VIEW} kein Prozessor, nie in {@link #view}, mit der Seite
     * entfernt.
     */
    public static final String IMPORTED_ASSETS = "imported-assets";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt die Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3) ihre Ergebnisse je
     * Satz ab ({@code SimplificationStore}). Wie {@link #READING_VIEW} kein Prozessor, nie in {@link #view}, mit der
     * Seite entfernt; der Entzug der KI-Freigabe entfernt sie ebenfalls.
     */
    public static final String SIMPLIFICATIONS = "sentence-simplifications";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt der Prüfschritt (ADR 0032, FL4) seine Urteile je Aussage ab
     * ({@code StatementCheckStore}). Wie {@link #SIMPLIFICATIONS} kein Prozessor, nie in {@link #view}, mit der Seite
     * entfernt; der Entzug der KI-Freigabe entfernt sie ebenfalls.
     */
    public static final String STATEMENT_CHECKS = "statement-checks";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt „In PPTX umwandeln“ das korrigierte Transkript und den Vortragstext je
     * Folie eines Videos ab ({@code PresentationStore}). Wie {@link #STATEMENT_CHECKS} kein Prozessor, nie in
     * {@link #view}, mit der Seite entfernt; der Entzug der KI-Freigabe entfernt sie ebenfalls.
     */
    public static final String VIDEO_PRESENTATION = "video-presentation";

    /** Art der Warteschlange als Hintergrundauftrag, je Quelle ein Teil. */
    public static final String BACKGROUND_KIND = "enrichment";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String BACKGROUND_TITLE = "KI-Anreicherung";
    /** Am Teil ({@link Jobs#CANCEL_NOTE}): was ein Abbruch mit den wartenden Berechnungen tut. */
    static final String CANCEL_NOTE = "die wartenden Berechnungen dieses Teils entfallen; fehlende oder veraltete "
            + "Ergebnisse holt der nächste Abgleich oder Abruf nach, erzwungene Neuberechnungen nach einer Freigabe "
            + "oder einem Update erst der Abgleich beim nächsten Start";
    /** Info an Teilen von vor dem Neustart: ihre Warteschlange lag nur im Speicher (Abnahme #1077). */
    static final String EMPTY_AFTER_RESTART = "Warteschlange nach Neustart leer";
    /** Code des gelben Ereignisses, wenn eine Berechnung des Teils scheitert und er mit der nächsten weitermacht. */
    static final String WORK_FAILED = "ENRICHMENT_WORK_FAILED";
    private static final String COMPONENT = "enrichment";

    /** So viele erzwungene Neuberechnungen warten höchstens zugleich im Hintergrund; der übrige Platz bleibt frei. */
    public static final int FORCED_IN_BACKGROUND = 50;

    private static final System.Logger LOG = System.getLogger(Enrichments.class.getName());
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final PageTextProjector projector;
    private final Map<String, EnrichmentProcessor> processors = new LinkedHashMap<>();
    private final EnrichmentStore store;
    private final Clock clock;
    private final ExecutorService background;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    /** Plätze für erzwungene Neuberechnungen im Hintergrund ({@link #recomputeInBackground}). */
    private final Semaphore forcedSlots = new Semaphore(FORCED_IN_BACKGROUND);
    /** Hintergrundaufträge für die Warteschlange; ohne sie der begrenzte {@link #background}. */
    private volatile BackgroundJobs hosted;
    /** Der gespeicherte Snapshot einer Seite, für die Teile der Warteschlange. */
    private volatile Function<PageKey, Optional<PageSnapshot>> lookup = key -> Optional.empty();
    /**
     * Je Quelle die Berechnungen, die noch neue aufnehmen: Ihr Teil hat noch nicht angefangen; unter
     * {@link #pendingLock}.
     */
    private final Map<String, Pending> pending = new HashMap<>();
    /** Je Gruppe ihre wartenden Berechnungen, auch die eines laufenden oder angehaltenen Teils; unter {@link #pendingLock}. */
    private final Map<String, Pending> groups = new HashMap<>();
    private final Object pendingLock = new Object();
    /** Laufende Teile der Warteschlange; {@link #closeBackground} wartet auf sie. */
    private final ActiveParts parts = new ActiveParts();
    private volatile boolean closed;

    /** Die Fakten aus den Ergebnissen dieser Ablage (#182), wenn sie welche führt. */
    public java.util.Optional<com.aresstack.spaceharvester.application.facts.FactStore> facts() {
        return store instanceof com.aresstack.spaceharvester.application.facts.FactStore facts
                ? java.util.Optional.of(facts) : java.util.Optional.empty();
    }

    /** Ohne Hintergrund: alle Prozessoren rechnen im aufrufenden Thread. */
    public Enrichments(PageTextProjector projector, List<EnrichmentProcessor> processors, EnrichmentStore store,
                       Clock clock) {
        this(projector, processors, store, clock, null);
    }

    /**
     * @param background Hintergrund für Prozessoren mit {@link EnrichmentProcessor#background()}, z. B.
     *                   {@link #background(int)}, oder {@code null}, dann rechnen auch sie im aufrufenden Thread
     */
    public Enrichments(PageTextProjector projector, List<EnrichmentProcessor> processors, EnrichmentStore store,
                       Clock clock, ExecutorService background) {
        this.background = background;
        this.projector = Objects.requireNonNull(projector, "projector");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (EnrichmentProcessor processor : processors) {
            if (!ID.matcher(processor.id()).matches()) {
                throw new IllegalArgumentException("Ungültige Prozessor-ID: " + processor.id());
            }
            if (this.processors.putIfAbsent(processor.id(), processor) != null) {
                throw new IllegalArgumentException("Prozessor-ID doppelt: " + processor.id());
            }
        }
    }

    /**
     * Ein begrenzter Hintergrund: ein Thread, höchstens {@code capacity} wartende Berechnungen, darüber wird verworfen.
     * Der Aufrufer schließt ihn mit {@link ExecutorService#shutdownNow()}.
     */
    public static ExecutorService background(int capacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofVirtual().name("enrichment-background-", 0).factory(),
                (task, pool) -> {
                    throw new RejectedExecutionException("Hintergrund der Anreicherungen voll");
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Beendet einen Hintergrund aus {@link #background}: Wartendes entfällt, Laufendes endet ohne Unterbrechung, denn
     * ein Interrupt mitten im Schreiben schlösse die Dateikanäle des Lucene-Index und machte dessen Sperre ungültig.
     * Erst nach der Frist wird unterbrochen.
     */
    public static void stop(ExecutorService background, Duration timeout) throws InterruptedException {
        if (background instanceof ThreadPoolExecutor pool) {
            pool.getQueue().clear();
        }
        background.shutdown();
        if (!background.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            background.shutdownNow();
        }
    }

    /** Die Ablage der Ergebnisse, etwa für den Begriffsindex (#119), wenn sie ihn mitführt. */
    public EnrichmentStore store() {
        return store;
    }

    /** Ob es den Prozessor gibt und er die Seite bearbeitet ({@link EnrichmentProcessor#appliesTo}). */
    public boolean appliesTo(String processorId, PageKey key) {
        EnrichmentProcessor processor = processors.get(processorId);
        return processor != null && processor.appliesTo(key);
    }

    /** Ob es den Prozessor gibt und er ohne Netz rechnet ({@link EnrichmentProcessor#background()} {@code false}). */
    public boolean local(String processorId) {
        EnrichmentProcessor processor = processors.get(processorId);
        return processor != null && !processor.background();
    }

    /** Die aktuelle Version des Prozessors ({@link EnrichmentProcessor#version()}); leer, wenn es ihn nicht gibt. */
    public Optional<String> version(String processorId) {
        EnrichmentProcessor processor = processors.get(processorId);
        return processor == null ? Optional.empty() : Optional.of(processor.version());
    }

    /** Die Kennungen der Prozessoren in Ausführungsreihenfolge. */
    public List<String> processorIds() {
        return List.copyOf(processors.keySet());
    }

    /**
     * Die Prozessoren für Dokumente, die die Seite bearbeiten, mit ihrer aktuellen Version: was ein aktueller
     * Analysestand eines Dokuments dieser Seite enthalten muss.
     */
    public Map<String, String> documentProcessorVersions(PageKey page) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (EnrichmentProcessor processor : processors.values()) {
            if (processor.documents() && processor.appliesTo(page)) {
                versions.put(processor.id(), processor.version());
            }
        }
        return versions;
    }

    /** Die Kennungen der Prozessoren, die auch Dokumente bearbeiten ({@link EnrichmentProcessor#documents()}). */
    public List<String> documentProcessorIds() {
        return processors.values().stream().filter(EnrichmentProcessor::documents).map(EnrichmentProcessor::id)
                .toList();
    }

    /**
     * Ob ein gespeicherter Stand eines Dokuments neu gerechnet werden muss, obwohl Text und Version gleich sind
     * ({@link EnrichmentProcessor#outdated}); ein unbekannter Prozessor sagt nein.
     */
    public boolean outdated(Enrichment stored) {
        EnrichmentProcessor processor = processors.get(stored.processorId());
        return processor != null && processor.outdated(stored);
    }

    /**
     * Berechnet für einen Abschnitt eines Anhangs oder einer enthaltenen Datei die Ergebnisse, die fehlen oder veraltet
     * sind (ADR 0026, AP9a), im aufrufenden Thread: Der Auftrag der Anreicherung ist hier selbst der Hintergrund. Nur
     * Prozessoren mit {@link EnrichmentProcessor#documents()}, nur solange sie die Seite des Dokuments bearbeiten.
     *
     * @param revision   Hash des Lesetextes des ganzen Dokuments, aus dem der Abschnitt stammt
     * @param extraction Kennung der Extraktion, für die {@code revision} gilt ({@link Enrichment#extraction()})
     * @param stored     die gespeicherten Ergebnisse des Dokuments ({@link EnrichmentStore#find(DocumentKey)})
     * @param only       nur dieser Prozessor oder {@code null} für alle
     */
    public Run refreshDocument(DocumentKey document, int section, String revision, String extraction, PageText text,
                               List<Enrichment> stored, String only) {
        return refreshDocument(document, section, revision, extraction, text, stored, only,
                DocumentPosition.UNLIMITED);
    }

    /** Wie oben mit der Stelle des Abschnitts im Dokument und der Grenze für das Embedding-Modell. */
    public Run refreshDocument(DocumentKey document, int section, String revision, String extraction, PageText text,
                               List<Enrichment> stored, String only, DocumentPosition position) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(document, "document");
        if (document.isPage()) {
            throw new IllegalArgumentException("Seiten über refresh(PageSnapshot)");
        }
        String input = text.inputSha256();
        Map<String, Enrichment> previous = new HashMap<>();
        for (Enrichment enrichment : stored) {
            if (enrichment.section() == section) {
                previous.put(enrichment.processorId(), enrichment);
            }
        }
        int computed = 0;
        int current = 0;
        int failed = 0;
        for (EnrichmentProcessor processor : processors.values()) {
            if (!processor.documents() || only != null && !only.equals(processor.id())
                    || !processor.appliesTo(document.page())) {
                continue;
            }
            Enrichment old = previous.get(processor.id());
            if (old != null && old.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT
                    && processor.current(old, text, position)) {
                current++;
                continue;
            }
            Enrichment result = compute(processor, document, section, revision, extraction, text, input, position);
            if (!saveWhileApplies(processor, result)) {
                continue;
            }
            computed++;
            if (result.status() == Enrichment.Status.FAILED) {
                failed++;
            }
        }
        return new Run(true, computed, current, failed);
    }

    /** @return das Ergebnis oder {@code null}, wenn der Prozessor das Dokument nicht bearbeiten darf */
    private Enrichment compute(EnrichmentProcessor processor, DocumentKey document, int section, String revision,
                               String extraction, PageText text, String input, DocumentPosition position) {
        PageKey key = document.page();
        try {
            EnrichmentOutput output = processor.process(document, text, position);
            String version = output.version() != null ? output.version() : processor.version();
            return new Enrichment(key, processor.id(), version, input, revision, Enrichment.Status.OK,
                    output.payloadJson(), output.warnings(), null, clock.instant(), document, section, extraction);
        } catch (ProcessingNotAllowedException e) {
            LOG.log(System.Logger.Level.DEBUG, "Prozessor {0} übersprungen, Quelle {1} nicht freigegeben",
                    processor.id(), key.sourceId());
            return null;
        } catch (RuntimeException e) {
            // Keine Meldung, kein Anhang, keine Seite: sie könnten Text oder Dateinamen enthalten.
            LOG.log(System.Logger.Level.WARNING, "Prozessor {0} {1} scheiterte an einem Dokument in {2} ({3})",
                    processor.id(), processor.version(), key.sourceId(), e.getClass().getName());
            return new Enrichment(key, processor.id(), processor.version(), input, revision,
                    Enrichment.Status.FAILED, "{}", List.of(), PROCESSOR_ERROR, clock.instant(), document, section,
                    extraction);
        }
    }

    /**
     * Berechnet die Ergebnisse, die fehlen oder veraltet sind. Ein aktuelles Ergebnis, auch ein fehlgeschlagenes, wird
     * nicht wiederholt; dafür gibt es {@link #recompute}. Prozessoren im Hintergrund zählen nicht in {@link Run}.
     */
    public Run refresh(PageSnapshot snapshot) {
        return run(snapshot, null, false);
    }

    /**
     * Wie {@link #refresh(PageSnapshot)}, nur für einen Prozessor; so kann ein Auftrag Prozessor für Prozessor
     * arbeiten und zeigen, welcher gerade rechnet.
     *
     * @throws IllegalArgumentException für einen unbekannten Prozessor
     */
    public Run refresh(PageSnapshot snapshot, String processorId) {
        if (!processors.containsKey(processorId)) {
            throw new IllegalArgumentException("Unbekannter Prozessor: " + processorId);
        }
        return run(snapshot, processorId, false);
    }

    /**
     * Berechnet die Ergebnisse der genannten Prozessoren neu, auch wenn sie aktuell sind (Neuindexierung). Auf
     * ausdrücklichen Wunsch, deshalb auch Prozessoren mit {@link EnrichmentProcessor#background()} im aufrufenden
     * Thread.
     *
     * @param processorId ein Prozessor oder {@code null} für alle
     * @throws IllegalArgumentException für einen unbekannten Prozessor
     */
    public Run recompute(PageSnapshot snapshot, String processorId) {
        if (processorId != null && !processors.containsKey(processorId)) {
            throw new IllegalArgumentException("Unbekannter Prozessor: " + processorId);
        }
        return run(snapshot, processorId, true);
    }

    /**
     * Berechnet die genannten Prozessoren einer Seite neu, auch wenn ihre Ergebnisse aktuell sind, etwa weil sich die
     * Freigabe der Quelle für den KI-Dienst geändert hat (ADR 0015): Die Version bleibt dieselbe, das Ergebnis nicht.
     * Anders als {@link #recompute} rechnen Prozessoren, die für die Seite das Netz brauchen, im Hintergrund; höchstens
     * {@link #FORCED_IN_BACKGROUND} solcher Berechnungen warten zugleich, darüber wartet der Aufrufer (gedrosselt). Ist
     * der Hintergrund voll, rechnet der Aufrufer selbst. Bearbeitet ein Prozessor die Seite nicht mehr
     * ({@link EnrichmentProcessor#appliesTo}), entfällt sein gespeichertes Ergebnis.
     *
     * @param processorIds die Prozessoren; unbekannte werden übergangen, gerechnet wird in Ausführungsreihenfolge
     * @return wie viele Ergebnisse eingereiht, berechnet oder entfernt wurden; 0 für eine nicht projizierbare Payload
     * @throws InterruptedException wenn der Aufrufer beim Warten auf einen Platz unterbrochen wird
     */
    public int recomputeInBackground(PageSnapshot snapshot, Collection<String> processorIds)
            throws InterruptedException {
        Optional<PageText> projected = projection(snapshot);
        if (projected.isEmpty()) {
            return 0;
        }
        PageText text = projected.get();
        String input = text.inputSha256();
        PageKey key = snapshot.key();
        Set<String> stored = new HashSet<>();
        store.find(key).forEach(enrichment -> stored.add(enrichment.processorId()));
        int count = 0;
        for (EnrichmentProcessor processor : processors.values()) {
            if (!processorIds.contains(processor.id())) {
                continue;
            }
            if (!processor.appliesTo(key)) {
                if (stored.contains(processor.id())) {
                    store.remove(key, processor.id());
                    count++;
                }
                continue;
            }
            if (background != null && processor.background(key)) {
                // Als Hintergrundauftrag ohne Platz aus forcedSlots: Ein Teil, der hier wartete, hielte einen Platz
                // des Hintergrunds, den der Teil der Warteschlange zum Freigeben braucht.
                if (enqueue(processor, key, true)) {
                    count++;
                    continue;
                }
                forcedSlots.acquire();
                if (schedule(processor, snapshot, text, input, true)) {
                    count++;
                    continue;
                }
            }
            if (saveWhileApplies(processor, compute(processor, snapshot, text, input))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Die gespeicherten Ergebnisse einer Seite mit ihrer Aktualität gegenüber der übergebenen Payload. Ergebnisse von
     * Prozessoren, die es nicht mehr gibt, und alle Ergebnisse einer nicht projizierbaren Payload sind veraltet.
     */
    public List<View> view(PageSnapshot snapshot) {
        return inspect(snapshot).stored();
    }

    /**
     * Wie {@link #view}, dazu der Eingabe-Hash des Snapshots und die Prozessoren, die für die Seite gelten, etwa um
     * fehlende Ergebnisse zu erkennen (Analyse-Export, #359). Liest nur; berechnet und speichert nichts.
     */
    public Inspection inspect(PageSnapshot snapshot) {
        String input = projection(snapshot).map(PageText::inputSha256).orElse(null);
        Map<String, String> applicable = new LinkedHashMap<>();
        for (EnrichmentProcessor processor : processors.values()) {
            if (processor.appliesTo(snapshot.key())) {
                applicable.put(processor.id(), processor.version());
            }
        }
        List<View> views = new ArrayList<>();
        for (Enrichment enrichment : store.find(snapshot.key())) {
            if (READING_VIEW.equals(enrichment.processorId()) || IMPORTED_ASSETS.equals(enrichment.processorId())
                    || VIDEO_MARKS.equals(enrichment.processorId())
                    || SIMPLIFICATIONS.equals(enrichment.processorId())
                    || STATEMENT_CHECKS.equals(enrichment.processorId())
                    || VIDEO_PRESENTATION.equals(enrichment.processorId())) {
                // Rechte für eingebundene Inhalte prüft nur die Lese-Ansicht selbst; mitgebrachte Bilder gehören zum
                // Export.
                continue;
            }
            EnrichmentProcessor processor = processors.get(enrichment.processorId());
            Enrichment.Freshness freshness = input == null ? Enrichment.Freshness.STALE
                    : enrichment.freshness(input, processor == null ? null : processor.version());
            views.add(new View(enrichment, freshness));
        }
        return new Inspection(input, views, applicable);
    }

    /** Die aktuellen Prozessoren mit ihrer Version in Ausführungsreihenfolge. */
    public Map<String, String> processorVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        processors.values().forEach(processor -> versions.put(processor.id(), processor.version()));
        return java.util.Collections.unmodifiableMap(versions);
    }

    /**
     * Die Textprojektion der Payload, gegen die die Ergebnisse gelten ({@link PageText#inputSha256()}); leer, wenn die
     * Payload nicht projizierbar ist. Für Ergebnisse, die nicht ein Prozessor dieser Klasse schreibt (Bildanalyse,
     * ADR 0021).
     */
    public Optional<PageText> text(PageSnapshot snapshot) {
        return projection(snapshot);
    }

    /** Die Version, mit der ein Prozessor gerade rechnet, oder leer für einen unbekannten. */
    public Optional<String> processorVersion(String processorId) {
        EnrichmentProcessor processor = processors.get(processorId);
        return processor == null ? Optional.empty() : Optional.of(processor.version());
    }

    /**
     * Entfernt die gespeicherten Ergebnisse aller Prozessoren dieser Klasse für eine Seite, deren Text weggefallen ist,
     * etwa nach dem Löschen eines Video-Transkripts. Nur was diese Klasse für die Seite aus dem Text rechnet
     * ({@link EnrichmentProcessor#appliesTo}); unabhängig abgelegte Ergebnisse (Bildanalyse, Folien) bleiben.
     *
     * @return wie viele Prozessoren ein Ergebnis hatten
     */
    public int forget(PageKey key) {
        java.util.Set<String> stored = new java.util.HashSet<>();
        for (Enrichment enrichment : store.find(key)) {
            stored.add(enrichment.processorId());
        }
        int removed = 0;
        for (EnrichmentProcessor processor : processors.values()) {
            if (stored.contains(processor.id()) && processor.appliesTo(key)) {
                store.remove(key, processor.id());
                removed++;
            }
        }
        return removed;
    }

    /** Macht die Ergebnisse dauerhaft, etwa am Ende eines Abgleichs. */
    public void flush() {
        store.flush();
    }

    /** Entfernt alle Ergebnisse einer Seite, die es im Cache nicht mehr gibt. */
    public void remove(PageKey key) {
        store.removeAll(key);
    }

    /**
     * Was zu einer Seite gespeichert ist.
     *
     * @param inputSha256 Eingabe-Hash des Snapshots oder {@code null}, wenn die Payload nicht projizierbar ist
     * @param stored      wie {@link #view}
     * @param applicable  Kennung und aktuelle Version der Prozessoren, die für die Seite gelten, in
     *                    Ausführungsreihenfolge
     */
    public record Inspection(String inputSha256, List<View> stored, Map<String, String> applicable) {

        public Inspection {
            stored = List.copyOf(stored);
            applicable = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(applicable));
        }
    }

    /** Ein Ergebnis mit seiner Aktualität. */
    public record View(Enrichment enrichment, Enrichment.Freshness freshness) {
    }

    /**
     * Ausgang eines Laufs für eine Seite.
     *
     * @param supported ob die Payload projiziert werden konnte; sonst wurde nichts berechnet
     * @param computed  neu berechnete Ergebnisse (auch fehlgeschlagene)
     * @param current   übersprungene, weil aktuelle Ergebnisse
     * @param failed    davon fehlgeschlagene
     */
    public record Run(boolean supported, int computed, int current, int failed) {
    }

    private Run run(PageSnapshot snapshot, String only, boolean force) {
        Optional<PageText> projected = projection(snapshot);
        if (projected.isEmpty()) {
            return new Run(false, 0, 0, 0);
        }
        PageText text = projected.get();
        String input = text.inputSha256();
        Map<String, Enrichment> stored = new HashMap<>();
        for (Enrichment enrichment : store.find(snapshot.key())) {
            stored.put(enrichment.processorId(), enrichment);
        }
        int computed = 0;
        int current = 0;
        int failed = 0;
        for (EnrichmentProcessor processor : processors.values()) {
            if (only != null && !only.equals(processor.id()) || !processor.appliesTo(snapshot.key())) {
                continue;
            }
            Enrichment previous = stored.get(processor.id());
            if (!force && previous != null
                    && previous.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT) {
                current++;
                continue;
            }
            if (!force && background != null && processor.background(snapshot.key())) {
                if (!enqueue(processor, snapshot.key(), false)) {
                    schedule(processor, snapshot, text, input);
                }
                continue;
            }
            Enrichment result = compute(processor, snapshot, text, input);
            if (!saveWhileApplies(processor, result)) {
                continue;
            }
            computed++;
            if (result.status() == Enrichment.Status.FAILED) {
                failed++;
            }
        }
        return new Run(true, computed, current, failed);
    }

    private void schedule(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input) {
        schedule(processor, snapshot, text, input, false);
    }

    /** Wie oft eine Hintergrundberechnung einem Text folgt, der sich während des Rechnens ändert. */
    private static final int TEXT_CHANGE_ATTEMPTS = 3;

    /**
     * Reiht eine Berechnung im Hintergrund ein. Ob der Prozessor die Seite noch bearbeitet, prüft erst die Ausführung:
     * Wird eine Freigabe entzogen, während die Berechnung wartet, geht nichts mehr an den Dienst (ADR 0015).
     *
     * @param force auch ein aktuelles Ergebnis neu berechnen; belegt dann einen Platz aus {@link #forcedSlots}, den
     *              der Aufrufer schon genommen hat
     * @return ob die Berechnung eingereiht ist oder schon wartet; {@code false}, wenn der Hintergrund voll oder beendet
     *         ist (ein Platz aus {@link #forcedSlots} ist dann wieder frei)
     */
    private boolean schedule(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input,
                             boolean force) {
        String id = (force ? "!" : "") + processor.id() + "\n" + snapshot.key().sourceId() + "\n"
                + snapshot.key().pageId();
        if (!queued.add(id)) {
            if (force) {
                forcedSlots.release();
            }
            return true;
        }
        try {
            background.execute(() -> {
                try {
                    computeQueued(processor, snapshot, text, input, force);
                } finally {
                    queued.remove(id);
                    if (force) {
                        forcedSlots.release();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            queued.remove(id);
            if (force) {
                forcedSlots.release();
                return false;
            }
            LOG.log(System.Logger.Level.INFO, "Hintergrund der Anreicherungen voll oder beendet; {0} wird später "
                    + "nachgeholt", processor.id());
            return false;
        }
    }

    /**
     * Eine Berechnung der Warteschlange. Ob der Prozessor die Seite noch bearbeitet, prüft erst die Ausführung: Wird
     * eine Freigabe entzogen, während die Berechnung wartet, geht nichts mehr an den Dienst (ADR 0015). Der Text kann
     * sich ändern, während die Berechnung wartet oder läuft, ohne neue Payload (etwa ein neues Transkript eines Videos):
     * Gerechnet und gespeichert wird nur der aktuelle; ist er weggefallen, nichts.
     */
    private Outcome computeQueued(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input,
                                  boolean force) {
        try {
            PageText current = text;
            String currentInput = input;
            for (int attempt = 0; attempt < TEXT_CHANGE_ATTEMPTS; attempt++) {
                if (!processor.appliesTo(snapshot.key())) {
                    return Outcome.SKIPPED;
                }
                Enrichment previous = store.find(snapshot.key()).stream()
                        .filter(enrichment -> enrichment.processorId().equals(processor.id())).findFirst()
                        .orElse(null);
                if (!force && previous != null && previous.freshness(currentInput, processor.version())
                        == Enrichment.Freshness.CURRENT) {
                    return Outcome.SKIPPED;
                }
                Enrichment result = compute(processor, snapshot, current, currentInput);
                Optional<PageText> now = projection(snapshot);
                if (now.isEmpty()) {
                    return Outcome.SKIPPED;
                }
                if (!now.get().inputSha256().equals(currentInput)) {
                    current = now.get();
                    currentInput = current.inputSha256();
                    continue;
                }
                if (!saveWhileApplies(processor, result)) {
                    return Outcome.SKIPPED;
                }
                store.flush();
                return result.status() == Enrichment.Status.FAILED ? Outcome.FAILED : Outcome.WRITTEN;
            }
            return Outcome.SKIPPED;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hintergrundberechnung {0} scheiterte ({1})",
                    processor.id(), e.getClass().getName());
            return Outcome.FAILED;
        }
    }

    /** Was eine Berechnung der Warteschlange ergab, für den Fortschritt des Teils (Codex-Befund #1077). */
    private enum Outcome {
        /** gespeichert */
        WRITTEN,
        /** als fehlgeschlagen gespeichert oder gescheitert */
        FAILED,
        /** nichts zu tun: aktuell, nicht freigegeben, ohne Text */
        SKIPPED
    }

    // --- Warteschlange als Hintergrundauftrag (ADR 0025 Nachtrag, Punkt 10) ----------------------------------------

    /** Eine wartende Berechnung: nur Prozessor und Seite, den Snapshot liest der Teil beim Rechnen. */
    private record Work(String processorId, PageKey key, boolean force) {
    }

    /** Die wartenden Berechnungen einer Quelle und die Gruppe, deren Teil sie abholt. */
    private static final class Pending {

        final String groupId;
        final String sourceId;
        /** Der Teil, sobald er angelegt ist. */
        volatile String partId;
        final Set<Work> work = new java.util.LinkedHashSet<>();
        /**
         * Ob der Teil angefangen hat; ab dann nimmt er nichts mehr auf (Abnahme #1077, kein Aushungern), Späteres kommt
         * in den nächsten Teil hinten in der Kette.
         */
        boolean sealed;

        Pending(String groupId, String sourceId) {
            this.groupId = groupId;
            this.sourceId = sourceId;
        }

        /**
         * Nimmt eine Berechnung auf (Abnahme #1078): Eine erzwungene ersetzt die gleiche ungezwungene an ihrer Stelle,
         * statt daneben zu stehen; eine ungezwungene neben der gleichen erzwungenen entfällt. Unter {@link #pendingLock}.
         */
        void add(Work added) {
            Work unforced = new Work(added.processorId(), added.key(), false);
            Work forced = new Work(added.processorId(), added.key(), true);
            if (!added.force()) {
                if (!work.contains(forced)) {
                    work.add(added);
                }
                return;
            }
            if (!work.contains(unforced)) {
                work.add(added);
                return;
            }
            List<Work> order = new java.util.ArrayList<>(work);
            order.set(order.indexOf(unforced), forced);
            work.clear();
            work.addAll(order);
        }
    }

    /**
     * Macht die Warteschlange sichtbar (ADR 0025 Nachtrag, Punkt 10): Teile der Art {@value #BACKGROUND_KIND}, je
     * Quelle einer, der neue Berechnungen aufnimmt, bis er anfängt; danach rechnet er nur, was bei seinem Start
     * wartete, und Späteres bekommt einen neuen Teil hinten in der Kette, wie beim früheren einen Thread. Ein
     * angehaltener Teil rechnet beim Fortsetzen weiter; ein abgebrochener verwirft seine wartenden Berechnungen (das
     * Ereignis des Abbruchs sagt es), ein gescheiterter übergibt sie einem neuen Teil. Die Teile laufen hintereinander
     * ({@link BackgroundJobs#startBehindOpen}). Kann der Hintergrund nicht laufen (ein Platz), rechnet der bisherige
     * Weg. Nach einem Neustart ist die Warteschlange leer: Teile von vorher werden abgeschlossen
     * ({@value #EMPTY_AFTER_RESTART}); fehlende oder veraltete Ergebnisse holt der nächste Abgleich oder Abruf nach.
     *
     * @param lookup der gespeicherte Snapshot einer Seite
     */
    public void background(BackgroundJobs background, Function<PageKey, Optional<PageSnapshot>> lookup) {
        Objects.requireNonNull(background, "background");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        background.register(new BackgroundKind(BACKGROUND_KIND, part -> JobTask.blocking(context ->
                parts.run(() -> closed, () -> {
                    throw new JobCancelledException(context.jobId());
                }, () -> drain(part, context)))));
        // Abnahme #1077: Ein angehaltener Teil von vorher hätte keine Arbeit mehr, hielte aber jeden neuen auf.
        List<JobStatus> retired = background.retireResumable(BACKGROUND_KIND, EMPTY_AFTER_RESTART);
        if (!retired.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "KI-Anreicherung: {0} Teile von vor dem Neustart abgeschlossen",
                    retired.size());
        }
        this.hosted = background;
    }

    /**
     * Reiht eine Berechnung als Hintergrundauftrag ein; startet für die Quelle einen Teil, wenn keiner sie abholt.
     *
     * @return {@code false} ohne Hintergrundaufträge, wenn sie nicht laufen können (ein Platz, Abnahme #1077) oder wenn
     *         der Dienst schließt; dann rechnet der Aufrufer wie bisher
     */
    private boolean enqueue(EnrichmentProcessor processor, PageKey key, boolean force) {
        BackgroundJobs jobs = hosted;
        if (jobs == null || closed || !jobs.canRun()) {
            return false;
        }
        Work work = new Work(processor.id(), key, force);
        settleIfEnded(jobs, key.sourceId());
        String groupId = null;
        synchronized (pendingLock) {
            Pending collecting = pending.get(key.sourceId());
            if (collecting != null) {
                collecting.add(work);
            } else {
                Pending created = new Pending(BackgroundJobs.newGroupId(), key.sourceId());
                created.work.add(work);
                pending.put(key.sourceId(), created);
                groups.put(created.groupId, created);
                groupId = created.groupId;
            }
        }
        // Meldungen an die Auftragsverwaltung nie unter dem eigenen Riegel.
        if (groupId != null) {
            start(jobs, key.sourceId(), groupId);
        }
        return true;
    }

    /** Startet den Teil einer Quelle; nimmt der Hintergrund ihn nicht an, rechnet der bisherige Weg. */
    private void start(BackgroundJobs jobs, String sourceId, String groupId) {
        try {
            BackgroundGroup started = jobs.startBehindOpen(groupId, BACKGROUND_KIND, BACKGROUND_TITLE,
                    new BackgroundPart.Draft(sourceId, null, Map.of(Jobs.CANCEL_NOTE, CANCEL_NOTE)));
            synchronized (pendingLock) {
                Pending current = groups.get(groupId);
                if (current != null) {
                    current.partId = started.parts().getFirst().id();
                }
            }
            Subscription[] subscription = new Subscription[1];
            subscription[0] = jobs.watch(groupId, group -> ended(sourceId, groupId, group, subscription));
            // Was schon vor dem Anmelden endete, meldet sich nicht mehr von selbst.
            ended(sourceId, groupId, jobs.group(groupId).orElse(new BackgroundGroup(groupId, null, null, List.of())),
                    subscription);
        } catch (RuntimeException e) {
            // Voll, beendet oder unerwartet (Abnahme #1077): nie eine Quelle ohne Teil zurücklassen.
            List<Work> left = release(sourceId, groupId);
            LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung der Quelle {0} nicht als Hintergrundauftrag "
                    + "gestartet ({1}): {2} Berechnungen auf dem bisherigen Weg", sourceId,
                    e.getClass().getSimpleName(), left.size());
            left.forEach(this::fallback);
        }
    }

    /**
     * Nach jeder Änderung der Gruppe: Endet ihr Teil (abgebrochen, gescheitert, fertig, nicht angehalten), holt er die
     * Quelle nicht mehr ab. Was ein gescheiterter liegen ließ, übernimmt ein neuer Teil.
     */
    private void ended(String sourceId, String groupId, BackgroundGroup group, Subscription[] subscription) {
        if (!group.parts().isEmpty() && (!group.finished() || group.paused())) {
            return;
        }
        // Ohne Teil: gelöscht, wie abgebrochen.
        JobState state = group.parts().isEmpty() ? JobState.CANCELLED : group.parts().getFirst().state();
        List<Work> left = release(sourceId, groupId);
        if (subscription[0] != null) {
            subscription[0].close();
        }
        settle(sourceId, state, left);
    }

    /**
     * Vor dem Einreihen: Ist der Teil der Quelle schon beendet, die Meldung darüber aber noch unterwegs (sie kommt
     * gebündelt), löst er die Quelle hier, damit neue Arbeit einen neuen Teil bekommt statt bei einem beendeten zu
     * liegen.
     */
    private void settleIfEnded(BackgroundJobs jobs, String sourceId) {
        Pending existing;
        synchronized (pendingLock) {
            existing = pending.get(sourceId);
        }
        String partId = existing == null ? null : existing.partId;
        if (partId == null) {
            return;
        }
        Optional<JobStatus> part = jobs.part(partId);
        if (part.isPresent() && BackgroundJobs.open(part.get())) {
            return;
        }
        settle(sourceId, part.map(JobStatus::state).orElse(JobState.CANCELLED), release(sourceId, existing.groupId));
    }

    /** Was ein beendeter Teil liegen ließ: nach einem Fehler übernimmt ein neuer Teil, sonst entfällt es. */
    private void settle(String sourceId, JobState state, List<Work> left) {
        if (left.isEmpty()) {
            return;
        }
        if (state == JobState.FAILED && !closed) {
            // Selbstheilend: ein neuer Teil übernimmt, außerhalb der Meldung der Auftragsverwaltung.
            Thread.ofVirtual().name("enrichment-handover").start(() -> left.forEach(work -> {
                EnrichmentProcessor processor = processors.get(work.processorId());
                if (processor != null && !enqueue(processor, work.key(), work.force())) {
                    fallback(work);
                }
            }));
            return;
        }
        LOG.log(System.Logger.Level.INFO, "KI-Anreicherung der Quelle {0} {1}: {2} wartende Berechnungen entfallen",
                sourceId, state == JobState.CANCELLED ? "abgebrochen" : "beendet", left.size());
    }

    /** Löst die Gruppe von ihren wartenden Berechnungen, wenn sie noch welche hat; liefert, was noch wartete. */
    private List<Work> release(String sourceId, String groupId) {
        synchronized (pendingLock) {
            Pending current = groups.remove(groupId);
            if (current == null) {
                return List.of();
            }
            pending.remove(sourceId, current);
            return List.copyOf(current.work);
        }
    }

    /**
     * Ein Teil: rechnet nacheinander, was bei seinem Start für ihn wartete (Abnahme #1077: Eine Quelle mit stetem
     * Zufluss hält so keine andere auf). Scheitert eine Berechnung, meldet er das gelb und macht mit der nächsten weiter.
     */
    private void drain(BackgroundPart part, JobContext context) {
        String groupId = part.groupId();
        Pending mine;
        int waitingAtStart;
        synchronized (pendingLock) {
            mine = groups.get(groupId);
            if (mine == null) {
                return;
            }
            if (!mine.sealed) {
                mine.sealed = true;
                pending.remove(mine.sourceId, mine);
            }
            waitingAtStart = mine.work.size();
        }
        context.discovered(waitingAtStart);
        while (true) {
            if (closed) {
                // Beim Beenden des Dienstes unterbrochen, nicht fertig.
                throw new JobCancelledException(context.jobId());
            }
            context.checkpoint();
            Work next;
            synchronized (pendingLock) {
                if (groups.get(groupId) != mine) {
                    return;
                }
                java.util.Iterator<Work> waiting = mine.work.iterator();
                if (!waiting.hasNext()) {
                    groups.remove(groupId);
                    return;
                }
                next = waiting.next();
                waiting.remove();
            }
            Outcome outcome;
            try {
                outcome = computeWork(next);
            } catch (JobCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                // Abnahme #1077: Sonst scheiterte der Teil, und jeder Ersatzteil verlöre die nächste Berechnung. Nur
                // Quelle und Exception-Klasse, die Meldung könnte Text der Seite enthalten.
                LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung {0} in Quelle {1} gescheitert ({2})",
                        next.processorId(), mine.sourceId, e.getClass().getName());
                context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, WORK_FAILED,
                        "Berechnung " + next.processorId() + " in Quelle " + mine.sourceId + " gescheitert ("
                                + e.getClass().getSimpleName() + "), weiter mit der nächsten"));
                outcome = Outcome.FAILED;
            }
            switch (outcome) {
                case WRITTEN -> context.written(1);
                case FAILED -> context.failed(1);
                case SKIPPED -> {
                }
            }
            context.processed(1);
        }
    }

    /** Rechnet eine Berechnung der Warteschlange mit dem Snapshot von jetzt. */
    private Outcome computeWork(Work work) {
        EnrichmentProcessor processor = processors.get(work.processorId());
        Optional<PageSnapshot> snapshot = processor == null ? Optional.empty() : lookup.apply(work.key());
        Optional<PageText> text = snapshot.flatMap(this::projection);
        if (text.isEmpty()) {
            return Outcome.SKIPPED;
        }
        return computeQueued(processor, snapshot.get(), text.get(), text.get().inputSha256(), work.force());
    }

    /**
     * Der bisherige Weg für eine Berechnung, die kein Teil abholt. Eine erzwungene, die auch dort keinen Platz findet,
     * rechnet im aufrufenden Thread wie bisher {@link #recomputeInBackground} (Codex-Befund #1077): Ihr altes Ergebnis
     * sähe sonst weiter aktuell aus.
     */
    private void fallback(Work work) {
        EnrichmentProcessor processor = processors.get(work.processorId());
        Optional<PageSnapshot> snapshot = processor == null ? Optional.empty() : lookup.apply(work.key());
        Optional<PageText> text = snapshot.flatMap(this::projection);
        if (text.isEmpty() || background == null) {
            return;
        }
        String input = text.get().inputSha256();
        if (!work.force()) {
            schedule(processor, snapshot.get(), text.get(), input);
        } else if (!forcedSlots.tryAcquire() || !schedule(processor, snapshot.get(), text.get(), input, true)) {
            computeQueued(processor, snapshot.get(), text.get(), input, true);
        }
    }

    /**
     * Beendet die Warteschlange als Hintergrundauftrag: Neue Berechnungen gehen den bisherigen Weg, laufende Teile
     * enden nach der aktuellen Berechnung; wartet höchstens {@code wait} auf sie. Vor der Ablage schließen.
     */
    public void closeBackground(Duration wait) throws InterruptedException {
        closed = true;
        if (!parts.await(wait)) {
            LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung beim Beenden nicht rechtzeitig fertig");
        }
    }

    /**
     * Speichert das Ergebnis nur, solange der Prozessor die Seite bearbeitet (ADR 0015). Wird die Freigabe während der
     * Berechnung oder zwischen Prüfung und Speichern entzogen, bleibt nichts aus der alten Freigabe stehen: Ein Entzug,
     * der vor dem Speichern schon aufgeräumt hat, würde das Ergebnis sonst dauerhaft übersehen.
     *
     * @param result {@code null}, wenn der Prozessor die Seite nicht bearbeiten durfte
     * @return ob das Ergebnis gespeichert blieb
     */
    private boolean saveWhileApplies(EnrichmentProcessor processor, Enrichment result) {
        if (result == null || !processor.appliesTo(result.key())) {
            return false;
        }
        store.save(result);
        if (!processor.appliesTo(result.key())) {
            store.remove(result.key(), processor.id());
            return false;
        }
        return true;
    }

    /** @return das Ergebnis oder {@code null}, wenn der Prozessor die Seite nicht bearbeiten darf */
    private Enrichment compute(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input) {
        try {
            EnrichmentOutput output = processor.process(snapshot, text);
            String version = output.version() != null ? output.version() : processor.version();
            return new Enrichment(snapshot.key(), processor.id(), version, input, snapshot.revision(),
                    Enrichment.Status.OK, output.payloadJson(), output.warnings(), null, clock.instant());
        } catch (ProcessingNotAllowedException e) {
            // Kein Fehler der Seite: nichts speichern, die Seite nicht nennen.
            LOG.log(System.Logger.Level.DEBUG, "Prozessor {0} übersprungen, Quelle {1} nicht freigegeben",
                    processor.id(), snapshot.key().sourceId());
            return null;
        } catch (RuntimeException e) {
            // Keine Meldung in Ergebnis oder Log: sie könnte Text der Seite enthalten.
            LOG.log(System.Logger.Level.WARNING, "Prozessor {0} {1} scheiterte an Seite {2}/{3} ({4})",
                    processor.id(), processor.version(), snapshot.key().sourceId(), snapshot.key().pageId(),
                    e.getClass().getName());
            return new Enrichment(snapshot.key(), processor.id(), processor.version(), input, snapshot.revision(),
                    Enrichment.Status.FAILED, "{}", List.of(), PROCESSOR_ERROR, clock.instant());
        }
    }

    private Optional<PageText> projection(PageSnapshot snapshot) {
        try {
            return projector.project(snapshot);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Seite {0}/{1} nicht projizierbar ({2})", snapshot.key().sourceId(),
                    snapshot.key().pageId(), e.getClass().getName());
            return Optional.empty();
        }
    }
}
