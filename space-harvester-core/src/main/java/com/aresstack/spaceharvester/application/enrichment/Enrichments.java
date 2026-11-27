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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
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
    /** Info am gescheiterten Teil von vor dem Neustart, dessen Arbeit ein neuer Teil übernommen hat. */
    static final String HANDED_OVER = "Arbeit nach dem Neustart an einen neuen Teil übergeben";
    /** Code des gelben Ereignisses, wenn ein von einem Benutzer gestarteter Teil nach dem Neustart anhält. */
    static final String RESUME_BY_HAND = "ENRICHMENT_RESUME_BY_HAND";
    /**
     * Angabe am Teil, der die Arbeit eines gescheiterten, von einem Benutzer gestarteten Teils nach dem Neustart
     * übernimmt: Er hält beim ersten Lauf gelb an ({@value #RESUME_BY_HAND}) und rechnet erst von Hand.
     */
    static final String BY_HAND = "enrichment.by-hand";
    /**
     * Angabe am Teil, der die Arbeit eines gescheiterten Teils nach dem Neustart übernimmt: die ID des alten. Stürzt der
     * Dienst ab, nachdem der neue seinen Stand abgelegt hat, aber bevor der alte abgeschlossen ist, mustert der nächste
     * Start den alten ohne neue Übergabe aus (Abnahme #1112).
     */
    static final String TAKEN_OVER_FROM = "enrichment.taken-over-from";
    /** Info am gescheiterten Teil, dessen noch nicht übergebene Arbeit mit seiner Quelle bereinigt wurde. */
    static final String FORGOTTEN_WITH_SOURCE = "Arbeit mit der Quelle bereinigt";
    /** Nach so vielen Berechnungen (erledigt oder gescheitert) legt ein Teil seinen Stand ab ({@link EnrichmentQueueStore}). */
    static final int PERSIST_EVERY = 25;
    private static final String COMPONENT = "enrichment";

    /** So viele erzwungene Neuberechnungen warten höchstens zugleich im Hintergrund; der übrige Platz bleibt frei. */
    public static final int FORCED_IN_BACKGROUND = 50;

    private static final System.Logger LOG = System.getLogger(Enrichments.class.getName());
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final PageTextProjector projector;
    private volatile LocalReaderAnalyses localReader;

    public void localReader(LocalReaderAnalyses localReader) {
        this.localReader = Objects.requireNonNull(localReader, "localReader");
    }

    public Optional<LocalReaderAnalyses.Result> localReading(PageSnapshot snapshot) {
        LocalReaderAnalyses reader = localReader;
        return reader == null ? Optional.empty() : reader.read(snapshot);
    }

    public Optional<LocalReadingText.Reading> localReadingText(PageSnapshot snapshot) {
        LocalReaderAnalyses reader = localReader;
        return reader == null ? Optional.empty() : reader.reading(snapshot);
    }
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
    /**
     * Je Gruppe die gescheiterten Berechnungen ihres Teils, für „Nur Fehler neu versuchen“ (↻, Angelo 22.11.2026);
     * bis der Teil gelöscht wird oder sie wiederholt; unter {@link #pendingLock}.
     */
    private final Map<String, Failed> failed = new HashMap<>();
    private final Object pendingLock = new Object();
    /** Laufende Teile der Warteschlange; {@link #closeBackground} wartet auf sie. */
    private final ActiveParts parts = new ActiveParts();
    private volatile boolean closed;
    private volatile Subscription removedParts = Subscription.NONE;
    /** Die Warteschlange über einen Neustart hinweg (Angelo 22.11.2026); geschrieben von {@link #writeQueue}. */
    private volatile EnrichmentQueueStore queueStore = EnrichmentQueueStore.NONE;
    /** Folgenummer der Schnappschüsse ({@link #persist}); unter {@link #pendingLock}. */
    private long persistSequence;
    /** Je Gruppe ihr neuester noch nicht geschriebener Schnappschuss; unter {@link #pendingLock}. */
    private final Map<String, Snapshot> unwritten = new LinkedHashMap<>();
    /** Der einzige Schreiber der Ablage: Datei-E/A nie unter {@link #pendingLock}. */
    private final java.util.concurrent.locks.ReentrantLock writer = new java.util.concurrent.locks.ReentrantLock();
    /** Je Gruppe die Folgenummer des zuletzt geschriebenen Stands; unter {@link #writer}. */
    private final Map<String, Long> written = new HashMap<>();
    /** Je bereinigter Quelle die Folgenummer beim Bereinigen; ältere Stände der Quelle entfallen; unter {@link #writer}. */
    private final Map<String, Long> forgotten = new HashMap<>();
    /** Wie viele Schnappschüsse gerade außerhalb von {@link #pendingLock} geschrieben werden; unter {@link #pendingLock}. */
    private int writing;
    /**
     * Arbeit gescheiterter Teile von vor dem Neustart, bis ein neuer Teil sie übernimmt ({@link BackgroundJobs#afterResume});
     * bis dahin bleibt die alte Datei unverändert. Unter {@link #pendingLock}.
     */
    private final List<HandOver> handOvers = new ArrayList<>();
    /** Die Wiederholung (↻), damit das Bereinigen auch ihre bereitgelegte Arbeit vergisst. */
    private volatile RetryFailed retryFailed;
    /** Wie viele Berechnungen ein Teil zugleich rechnet: ai.parallel, bei jedem Teil neu gelesen. */
    private volatile IntSupplier parallel = () -> 1;
    /** Der Netzzustand: ohne Netz wartet eine Berechnung, statt Versuche zu verbrauchen; ohne ihn wie bisher. */
    private volatile com.aresstack.spaceharvester.application.network.NetworkState network;

    /**
     * Die Arbeit eines gescheiterten Teils von vor dem Neustart.
     *
     * @param starter die Angaben, mit denen ein Benutzer ihn gestartet hat; leer für automatische Teile
     */
    private record HandOver(String oldGroupId, String partId, String sourceId, List<Work> work,
                            Map<String, String> starter) {
    }

    /** Ein Stand einer Gruppe zum Schreiben; ohne {@code saved} wird ihr Eintrag gelöscht. */
    private record Snapshot(long sequence, String sourceId, EnrichmentQueueStore.Saved saved) {
    }

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
     * Ob der Prozessor für diese Seite über das Netz rechnet ({@link EnrichmentProcessor#background(PageKey)}), etwa
     * Absätze mit Embedding-Modell einer freigegebenen Quelle; für einen unbekannten Prozessor {@code false}.
     */
    public boolean network(PageKey key, String processorId) {
        EnrichmentProcessor processor = processors.get(processorId);
        return processor != null && processor.background(key);
    }

    /** Only local processors that explicitly track dependencies on other stored pages. */
    public List<String> dependentProcessorIds(String sourceId) {
        return processors.values().stream()
                .filter(processor -> !processor.background() && processor.dependsOnOtherPages(sourceId))
                .map(EnrichmentProcessor::id).toList();
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
     * Pages other than its own that a stored result was derived from ({@link EnrichmentProcessor#evidence}); results
     * of unknown processors yield none, since nobody can show them as current.
     */
    public List<PageKey> evidence(Enrichment stored) {
        EnrichmentProcessor processor = processors.get(stored.processorId());
        if (processor == null) {
            return List.of();
        }
        return processor.evidence(stored).stream().filter(key -> !key.equals(stored.key())).distinct().toList();
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
                    && (!document.localOriginal() || old.status() == Enrichment.Status.OK)
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
        if (processors.values().stream().noneMatch(processor -> processorIds.contains(processor.id())
                && !sectionOwned(snapshot, processor))) {
            return 0;
        }
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
            if (!processorIds.contains(processor.id()) || sectionOwned(snapshot, processor)) {
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
            if (!sectionOwned(snapshot, processor) && processor.appliesTo(snapshot.key())) {
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
            if (freshness == Enrichment.Freshness.CURRENT && outdated(enrichment)) {
                freshness = Enrichment.Freshness.STALE;
            }
            views.add(new View(enrichment, freshness));
        }
        return new Inspection(input, views, applicable);
    }

    /** Inspect the stored local section results without treating them as whole-page analyses. */
    public List<View> localInspection(PageSnapshot snapshot) {
        List<View> views = new ArrayList<>();
        if (com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference.recognizes(snapshot.payload())) {
            Optional<LocalReadingText.Reading> reading = localReadingText(snapshot);
            for (Enrichment enrichment : store.findDocuments(snapshot.key())) {
                if (!enrichment.document().localOriginal()) {
                    continue;
                }
                EnrichmentProcessor processor = processors.get(enrichment.processorId());
                String expectedVersion = processor == null ? null : processor.version();
                Enrichment.Freshness freshness = Enrichment.Freshness.STALE;
                if (reading.isPresent() && enrichment.extraction() != null
                        && enrichment.extraction().startsWith(DocumentEnrichments.basis(reading.get().extraction()) + "|")
                        && !outdated(enrichment)) {
                    var slice = reading.get().slices().stream()
                            .filter(s -> s.document().equals(enrichment.document()) && s.section() == enrichment.section())
                            .findFirst();
                    if (slice.isPresent()) {
                        freshness = enrichment.freshness(slice.get().text().inputSha256(), expectedVersion);
                    } else if (enrichment.processorVersion().equals(expectedVersion)) {
                        // The completed extraction can include sections outside the bounded reader.
                        freshness = Enrichment.Freshness.CURRENT;
                    }
                }
                views.add(new View(enrichment, freshness));
            }
        }
        return List.copyOf(views);
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

    private static boolean sectionOwned(PageSnapshot snapshot, EnrichmentProcessor processor) {
        return processor.documents() && com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                .recognizes(snapshot.payload());
    }

    private Run run(PageSnapshot snapshot, String only, boolean force) {
        var local = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference.read(snapshot.payload());
        if (local.isPresent() && processors.values().stream().noneMatch(processor ->
                (only == null || only.equals(processor.id())) && !sectionOwned(snapshot, processor)
                        && processor.appliesTo(snapshot.key()))) {
            return new Run(local.get().extractionKey() != null, 0, 0, 0);
        }
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
            if (only != null && !only.equals(processor.id()) || sectionOwned(snapshot, processor)
                    || !processor.appliesTo(snapshot.key())) {
                continue;
            }
            Enrichment previous = stored.get(processor.id());
            if (!force && previous != null
                    && previous.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT
                    && !processor.outdated(previous)) {
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
    private Queued computeQueued(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input,
                                 boolean force) {
        try {
            PageText current = text;
            String currentInput = input;
            for (int attempt = 0; attempt < TEXT_CHANGE_ATTEMPTS; attempt++) {
                if (sectionOwned(snapshot, processor) || !processor.appliesTo(snapshot.key())) {
                    return Queued.SKIPPED;
                }
                Enrichment previous = store.find(snapshot.key()).stream()
                        .filter(enrichment -> enrichment.processorId().equals(processor.id())).findFirst()
                        .orElse(null);
                if (!force && previous != null && previous.freshness(currentInput, processor.version())
                        == Enrichment.Freshness.CURRENT && !processor.outdated(previous)) {
                    return Queued.SKIPPED;
                }
                Computed computed = attempt(processor, snapshot, current, currentInput);
                Enrichment result = computed.result();
                Optional<PageText> now = projection(snapshot);
                if (now.isEmpty()) {
                    return Queued.SKIPPED;
                }
                if (!now.get().inputSha256().equals(currentInput)) {
                    current = now.get();
                    currentInput = current.inputSha256();
                    continue;
                }
                if (!saveWhileApplies(processor, result)) {
                    return Queued.SKIPPED;
                }
                store.flush();
                return result.status() == Enrichment.Status.FAILED ? new Queued(Outcome.FAILED, computed.cause())
                        : new Queued(Outcome.WRITTEN, null);
            }
            return Queued.SKIPPED;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hintergrundberechnung {0} scheiterte ({1})",
                    processor.id(), e.getClass().getName());
            return new Queued(Outcome.FAILED, e);
        }
    }

    /**
     * Was eine Berechnung der Warteschlange ergab, mit der Ursache eines Fehlschlags (für das Ereignis des Teils; nur
     * die Ausnahme selbst, nie im Ergebnis gespeichert).
     */
    private record Queued(Outcome outcome, Throwable cause) {

        static final Queued SKIPPED = new Queued(Outcome.SKIPPED, null);
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
    record Work(String processorId, PageKey key, boolean force) {
    }

    /** Die gescheiterten Berechnungen eines Teils. */
    private static final class Failed {

        final String sourceId;
        final String partId;
        final Set<Work> work = new java.util.LinkedHashSet<>();

        Failed(String sourceId, String partId) {
            this.sourceId = sourceId;
            this.partId = partId;
        }
    }

    /**
     * „Nur Fehler neu versuchen“ (↻): Die gescheiterten Berechnungen des Teils werden seine neue Warteschlange, im
     * selben Eintrag. Gerechnet wird wie beim ersten Lauf ({@link #drain}, {@link #computeQueued}): mit der KI-Freigabe
     * je Seite, geprüft bei der Ausführung, und denselben Einstellungen.
     */
    private final class RetryFailed implements BackgroundJobs.Retry {

        private final BackgroundJobs jobs;
        /** Was {@link #prepare} bereitlegte, bis der erneute Lauf angenommen ist; unter {@link #pendingLock}. */
        private final Map<String, Prepared> prepared = new HashMap<>();

        private record Prepared(Failed work, List<Work> retry, boolean created, Pending queue) {
        }

        RetryFailed(BackgroundJobs jobs) {
            this.jobs = jobs;
        }

        @Override
        public boolean prepare(BackgroundPart part) {
            try {
                return prepareLocked(part);
            } finally {
                writeQueue();
            }
        }

        private boolean prepareLocked(BackgroundPart part) {
            synchronized (pendingLock) {
                Failed previous = failed.remove(part.groupId());
                if (previous == null || previous.work.isEmpty()) {
                    return false;
                }
                // Erzwungen: Ein gescheitertes Ergebnis gilt bei gleichem Text und gleicher Version sonst als aktuell.
                List<Work> retry = previous.work.stream()
                        .map(work -> new Work(work.processorId(), work.key(), true)).toList();
                Pending existing = groups.get(part.groupId());
                if (existing != null) {
                    // Angehalten mit Rest: Das Gescheiterte kommt zum Rest, beides läuft im selben Eintrag weiter.
                    retry.forEach(existing::add);
                    prepared.put(part.groupId(), new Prepared(previous, retry, false, existing));
                    persist(part.groupId());
                    return true;
                }
                Pending again = new Pending(part.groupId(), previous.sourceId);
                // Nimmt nichts Neues auf: Neue Berechnungen der Quelle bekommen wie immer einen eigenen Teil.
                again.sealed = true;
                again.partId = part.jobId();
                again.work.addAll(retry);
                groups.put(part.groupId(), again);
                prepared.put(part.groupId(), new Prepared(previous, retry, true, again));
                persist(part.groupId());
                return true;
            }
        }

        @Override
        public void started(BackgroundPart part) {
            String groupId = part.groupId();
            Prepared done;
            synchronized (pendingLock) {
                done = prepared.remove(groupId);
            }
            if (done == null || !done.created()) {
                // Die Gruppe hatte schon ihre Beobachtung (angehalten mit Rest).
                return;
            }
            Subscription[] subscription = new Subscription[1];
            Pending queue = done.queue();
            subscription[0] = jobs.watch(groupId, group -> ended(part.sourceId(), groupId, queue, group,
                    subscription));
            // Was schon vor dem Anmelden endete, meldet sich nicht mehr von selbst.
            ended(part.sourceId(), groupId, queue, jobs.group(groupId).orElse(new BackgroundGroup(groupId, null, null,
                    List.of())), subscription);
        }

        @Override
        public void abandoned(BackgroundPart part) {
            synchronized (pendingLock) {
                Prepared undone = prepared.remove(part.groupId());
                if (undone == null) {
                    return;
                }
                if (undone.created()) {
                    groups.remove(part.groupId(), undone.queue());
                } else {
                    Pending existing = groups.get(part.groupId());
                    if (existing != null) {
                        undone.retry().forEach(existing.work::remove);
                    }
                }
                failed.put(part.groupId(), undone.work());
                persist(part.groupId());
            }
            writeQueue();
        }

        /** Beim Bereinigen: was für Teile der Quelle bereitlag, gilt nicht mehr. Unter {@link #pendingLock}. */
        void forget(String sourceId) {
            prepared.values().removeIf(undone -> undone.work().sourceId.equals(sourceId));
        }
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
        /** Unter {@link #pendingLock}: Die Quelle wurde bereinigt ({@link #forgetQueued}), die Gruppe gilt nicht mehr. */
        boolean forgotten;
        /**
         * Die Berechnungen, die der Teil gerade rechnet, bis zu ai.parallel zugleich: Sie bleiben im abgelegten Stand,
         * bis ihr Ergebnis feststeht (Abnahme #1112); unter {@link #pendingLock}.
         */
        final Set<Work> running = new java.util.LinkedHashSet<>();

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

        /** Stellt eine unterbrochene Berechnung erzwungen wieder vorn an. Unter {@link #pendingLock}. */
        void pushFront(Work interrupted) {
            Work forced = new Work(interrupted.processorId(), interrupted.key(), true);
            List<Work> order = new java.util.ArrayList<>(work.size() + 1);
            order.add(forced);
            for (Work each : work) {
                if (!each.key().equals(forced.key()) || !each.processorId().equals(forced.processorId())) {
                    order.add(each);
                }
            }
            work.clear();
            work.addAll(order);
        }

        /** Was der Teil noch vor sich hat, die laufenden Berechnungen zuerst. Unter {@link #pendingLock}. */
        List<Work> open() {
            if (running.isEmpty()) {
                return List.copyOf(work);
            }
            List<Work> open = new java.util.ArrayList<>(running);
            work.stream().filter(each -> !running.contains(each)).forEach(open::add);
            return open;
        }
    }

    /**
     * Macht die Warteschlange sichtbar (ADR 0025 Nachtrag, Punkt 10): Teile der Art {@value #BACKGROUND_KIND}, je
     * Quelle einer, der neue Berechnungen aufnimmt, bis er anfängt; danach rechnet er nur, was bei seinem Start
     * wartete, und Späteres bekommt einen neuen Teil hinten in der Kette, wie beim früheren einen Thread. Ein
     * angehaltener Teil rechnet beim Fortsetzen weiter; ein abgebrochener verwirft seine wartenden Berechnungen (das
     * Ereignis des Abbruchs sagt es), ein gescheiterter übergibt sie einem neuen Teil. Die Teile laufen hintereinander
     * ({@link BackgroundJobs#startBehindOpen}). Kann der Hintergrund nicht laufen (ein Platz), rechnet der bisherige
     * Weg. Ohne Ablage ist die Warteschlange nach einem Neustart leer: Teile von vorher werden abgeschlossen
     * ({@value #EMPTY_AFTER_RESTART}); fehlende oder veraltete Ergebnisse holt der nächste Abgleich oder Abruf nach.
     *
     * @param lookup der gespeicherte Snapshot einer Seite
     */
    public void background(BackgroundJobs background, Function<PageKey, Optional<PageSnapshot>> lookup) {
        background(background, lookup, EnrichmentQueueStore.NONE);
    }

    /**
     * Wie oben, mit einer Ablage der Warteschlange (Angelo 22.11.2026): Was ein Teil beim Beenden noch vor sich hatte
     * und was ihm misslang, steht nach dem Neustart wieder bereit. Ein unterbrochener Teil setzt dann von selbst fort
     * ({@link BackgroundJobs#resumeInterrupted()}), ein angehaltener bleibt mit seiner Arbeit angehalten, ↻ wiederholt
     * auch nach dem Neustart das Gescheiterte. Nur Teile ohne abgelegte Arbeit werden abgeschlossen
     * ({@value #EMPTY_AFTER_RESTART}). Die KI-Freigabe prüft jede Berechnung erst bei der Ausführung.
     *
     * @param queue die Ablage, etwa {@code FileEnrichmentQueue}
     */
    public void background(BackgroundJobs background, Function<PageKey, Optional<PageSnapshot>> lookup,
                           EnrichmentQueueStore queue) {
        Objects.requireNonNull(background, "background");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.queueStore = Objects.requireNonNull(queue, "queue");
        background.register(new BackgroundKind(BACKGROUND_KIND, part -> JobTask.blocking(context ->
                parts.run(() -> closed, () -> {
                    throw new JobCancelledException(context.jobId());
                }, () -> drain(part, context)))));
        RetryFailed retry = new RetryFailed(background);
        this.retryFailed = retry;
        background.registerRetry(BACKGROUND_KIND, retry);
        // Gelöschte Teile brauchen ihre gescheiterten Berechnungen nicht mehr.
        this.removedParts = background.subscribe(BACKGROUND_KIND, changed -> {
        }, removed -> {
            String groupId = removed.parameter(BackgroundJobs.GROUP);
            if (groupId != null) {
                synchronized (pendingLock) {
                    failed.remove(groupId);
                    persist(groupId);
                }
                writeQueue();
            }
        });
        Set<String> restored = restore(background);
        Set<String> handingOver;
        synchronized (pendingLock) {
            handingOver = handOvers.stream().map(HandOver::partId).collect(java.util.stream.Collectors.toSet());
        }
        // Abnahme #1077: Ein angehaltener Teil von vorher ohne Arbeit hielte jeden neuen auf. Ein gescheiterter, dessen
        // Arbeit noch übergeben wird, bleibt bis dahin stehen, damit ein Absturz davor sie nicht verliert.
        List<JobStatus> retired = background.retireResumable(BACKGROUND_KIND, EMPTY_AFTER_RESTART,
                part -> restored.contains(part.id()) || handingOver.contains(part.id()));
        if (!retired.isEmpty() || !restored.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "KI-Anreicherung: {0} Teile von vor dem Neustart fortsetzbar, {1} "
                    + "abgeschlossen", restored.size(), retired.size());
        }
        this.hosted = background;
        // Was ein gescheiterter Teil vor dem Neustart nicht mehr übergeben konnte, übernimmt ein neuer, aber erst
        // zusammen mit dem Fortsetzen der unterbrochenen (Abnahme #1112): Vorher ist das offene Löschen einer Quelle noch
        // nicht angemeldet (Jobs#sourceHold), und ein neuer Teil liefe an ihm vorbei.
        boolean anyHandOver;
        synchronized (pendingLock) {
            anyHandOver = !handOvers.isEmpty();
        }
        if (anyHandOver) {
            background.afterResume(this::handOver);
        }
    }

    /** Übergibt die Arbeit gescheiterter Teile von vor dem Neustart je einem neuen Teil. */
    private void handOver() {
        while (true) {
            HandOver left;
            synchronized (pendingLock) {
                if (handOvers.isEmpty()) {
                    return;
                }
                // Je Eintrag unter dem Riegel heraus: forgetQueued sieht so jeden noch nicht begonnenen (Abnahme #1112).
                left = handOvers.removeFirst();
            }
            handOver(left);
        }
    }

    /**
     * Ein eigener Teil für die Arbeit eines gescheiterten. Erst wenn er seinen Stand abgelegt hat, verliert die alte Datei
     * ihre Arbeit (Abnahme #1112): Endet der Start vorher, steht sie beim nächsten Start noch da. War der alte von einem
     * Benutzer gestartet, übernimmt der neue dessen Angaben und hält bei jedem Lauf ohne Menschen gelb an
     * ({@value #RESUME_BY_HAND}). Für eine Quelle, deren Löschen offen ist, entfällt die Arbeit.
     */
    private void handOver(HandOver left) {
        BackgroundJobs jobs = hosted;
        if (jobs == null || closed || left.work().isEmpty()) {
            return;
        }
        Pending created = new Pending(BackgroundJobs.newGroupId(), left.sourceId());
        created.sealed = true;
        left.work().forEach(created::add);
        boolean withdrawing;
        synchronized (pendingLock) {
            // Unter demselben Riegel wie forgetQueued: Entweder es sieht die neue Gruppe, oder hier das Löschen.
            withdrawing = jobs.withdrawing(left.sourceId());
            if (!withdrawing) {
                groups.put(created.groupId, created);
            }
        }
        if (withdrawing) {
            LOG.log(System.Logger.Level.INFO, "KI-Anreicherung der Quelle {0}: Löschen offen, {1} übernommene "
                    + "Berechnungen entfallen", left.sourceId(), left.work().size());
            persist(left.oldGroupId());
            retire(jobs, left.partId());
            return;
        }
        Map<String, String> parameters = new LinkedHashMap<>(left.starter());
        if (!left.starter().isEmpty()) {
            parameters.put(BY_HAND, "true");
        }
        parameters.put(TAKEN_OVER_FROM, left.partId());
        // Scheitert der Start, bleiben alter Teil und alte Datei, wie sie sind; der nächste Start übergibt erneut. Nie
        // auf den bisherigen Weg: Der liefe ohne die Rechte des Benutzers (Abnahme #1112).
        if (!start(jobs, left.sourceId(), created.groupId, parameters, true)) {
            return;
        }
        boolean forgotten;
        synchronized (pendingLock) {
            forgotten = created.forgotten;
        }
        if (forgotten) {
            // Die Quelle wurde inzwischen bereinigt (forgetQueued): Ihre Arbeit entfällt samt dem alten Teil.
            cancel(jobs, created.groupId, left.sourceId());
            retire(jobs, left.partId(), FORGOTTEN_WITH_SOURCE);
            return;
        }
        // Erst wenn der Stand des neuen Teils sicher auf der Platte liegt, verliert die alte Datei die Arbeit.
        if (!persistNow(created.groupId)) {
            synchronized (pendingLock) {
                groups.remove(created.groupId, created);
            }
            cancel(jobs, created.groupId, left.sourceId());
            LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung der Quelle {0}: Stand des neuen Teils nicht abgelegt, "
                    + "der gescheiterte behält seine {1} Berechnungen bis zum nächsten Start", left.sourceId(),
                    left.work().size());
            return;
        }
        persist(left.oldGroupId());
        retire(jobs, left.partId());
    }

    /** Bricht den neuen Teil einer gescheiterten Übergabe ab; seine Arbeit hat er nicht mehr. */
    private static void cancel(BackgroundJobs jobs, String groupId, String sourceId) {
        try {
            jobs.cancel(groupId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung der Quelle {0}: neuer Teil nicht abgebrochen ({1})",
                    sourceId, e.getClass().getName());
        }
    }

    /** Schließt den gescheiterten Teil ab, dessen Arbeit jetzt ein neuer hat. */
    private static void retire(BackgroundJobs jobs, String partId) {
        retire(jobs, partId, HANDED_OVER);
    }

    private static void retire(BackgroundJobs jobs, String partId, String reason) {
        jobs.retireResumable(BACKGROUND_KIND, reason, part -> !part.id().equals(partId));
    }

    /**
     * Holt die abgelegte Warteschlange zurück: die Arbeit unterbrochener, angehaltener oder offener Teile und das
     * Gescheiterte aller Teile, die es noch gibt. Abgelegtes zu gelöschten Teilen entfällt.
     *
     * Die Arbeit gescheiterter Teile kommt nach {@link #handOvers}.
     *
     * @return die Teile, die mit ihrer Arbeit weiterlaufen
     */
    private Set<String> restore(BackgroundJobs background) {
        List<EnrichmentQueueStore.Saved> saved;
        try {
            saved = queueStore.all();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Warteschlange der KI-Anreicherung nicht lesbar ({0})",
                    e.getClass().getName());
            return Set.of();
        }
        Set<String> restored = new HashSet<>();
        Set<String> savedParts = saved.stream().map(EnrichmentQueueStore.Saved::partId)
                .collect(java.util.stream.Collectors.toSet());
        // Abnahme #1112: wen ein vorhandener Teil schon übernommen hat (er legte seinen Stand ab oder ist fertig).
        Set<String> takenOver = background.parts(BACKGROUND_KIND).stream()
                .filter(status -> status.parameter(TAKEN_OVER_FROM) != null
                        && (savedParts.contains(status.id()) || status.state() == JobState.COMPLETED))
                .map(status -> status.parameter(TAKEN_OVER_FROM))
                .collect(java.util.stream.Collectors.toSet());
        for (EnrichmentQueueStore.Saved entry : saved) {
            String groupId = entry.groupId();
            Optional<JobStatus> part = background.part(entry.partId());
            synchronized (pendingLock) {
                if (part.isEmpty() || !groupId.equals(part.get().parameter(BackgroundJobs.GROUP))
                        || !BACKGROUND_KIND.equals(part.get().parameter(BackgroundJobs.KIND))) {
                    // Der Teil ist gelöscht: nichts mehr, wofür seine Arbeit stünde.
                    persist(groupId);
                    continue;
                }
                JobStatus status = part.get();
                List<Work> work = works(entry.sourceId(), entry.work());
                List<Work> broken = works(entry.sourceId(), entry.failed());
                if (!broken.isEmpty()) {
                    Failed again = new Failed(entry.sourceId(), entry.partId());
                    again.work.addAll(broken);
                    failed.put(groupId, again);
                }
                boolean resumable = !work.isEmpty() && (!status.state().terminal()
                        || status.state() == JobState.PAUSED || BackgroundJobs.interrupted(status));
                if (resumable) {
                    Pending waiting = new Pending(groupId, entry.sourceId());
                    waiting.sealed = true;
                    waiting.partId = entry.partId();
                    waiting.work.addAll(work);
                    groups.put(groupId, waiting);
                    restored.add(entry.partId());
                } else if (!work.isEmpty() && status.state() == JobState.FAILED
                        && !takenOver.contains(entry.partId())) {
                    Map<String, String> starter = new LinkedHashMap<>();
                    if (BackgroundJobs.startedByUser(status)) {
                        starter.putAll(BackgroundJobs.starter(status));
                    }
                    handOvers.add(new HandOver(groupId, entry.partId(), entry.sourceId(), work, starter));
                    // Die Datei bleibt, wie sie ist, bis der neue Teil seinen Stand abgelegt hat.
                    continue;
                }
                // Was nicht weiterläuft, steht nicht mehr als wartend in der Ablage.
                persist(groupId);
            }
            if (restored.contains(entry.partId())) {
                // Ohne Prüfung jetzt: Der Teil ist unterbrochen oder angehalten und läuft erst mit dem Fortsetzen.
                Subscription[] subscription = new Subscription[1];
                String sourceId = entry.sourceId();
                Pending queue;
                synchronized (pendingLock) {
                    queue = groups.get(groupId);
                }
                subscription[0] = background.watch(groupId, group -> ended(sourceId, groupId, queue, group,
                        subscription));
            }
        }
        // Die Schnappschüsse von oben, außerhalb des Riegels.
        writeQueue();
        return restored;
    }

    private static List<Work> works(String sourceId, List<EnrichmentQueueStore.Entry> entries) {
        return entries.stream().map(entry -> new Work(entry.processorId(), new PageKey(sourceId, entry.pageId()),
                entry.force())).toList();
    }

    private static List<EnrichmentQueueStore.Entry> entries(Collection<Work> work) {
        return work.stream().map(each -> new EnrichmentQueueStore.Entry(each.processorId(), each.key().pageId(),
                each.force())).toList();
    }

    /**
     * Legt den Stand der Gruppe ab, wie er jetzt im Speicher steht: ihre wartende Arbeit samt der laufenden Berechnung
     * und ihre gescheiterte; ohne beides entfällt der Eintrag. Unter {@link #pendingLock} entsteht nur der Schnappschuss
     * mit Folgenummer; geschrieben wird außerhalb ({@link #writeQueue}), damit Datei-E/A nie das Einreihen aller
     * Quellen aufhält (Abnahme #1112). Unter dem Riegel aufgerufen, schreibt der Aufrufer danach mit
     * {@link #writeQueue}.
     */
    private void persist(String groupId) {
        if (queueStore == EnrichmentQueueStore.NONE) {
            return;
        }
        synchronized (pendingLock) {
            unwritten.put(groupId, snapshot(groupId));
        }
        writeQueue();
    }

    /** Der Stand der Gruppe von jetzt, mit neuer Folgenummer; unter {@link #pendingLock}. */
    private Snapshot snapshot(String groupId) {
        Pending waiting = groups.get(groupId);
        Failed broken = failed.get(groupId);
        List<EnrichmentQueueStore.Entry> work = waiting == null ? List.of() : entries(waiting.open());
        List<EnrichmentQueueStore.Entry> misses = broken == null ? List.of() : entries(broken.work);
        String partId = waiting != null && waiting.partId != null ? waiting.partId
                : broken != null ? broken.partId : null;
        String sourceId = waiting != null ? waiting.sourceId : broken != null ? broken.sourceId : null;
        EnrichmentQueueStore.Saved saved = partId == null || work.isEmpty() && misses.isEmpty() ? null
                : new EnrichmentQueueStore.Saved(groupId, sourceId, partId, work, misses);
        return new Snapshot(++persistSequence, sourceId, saved);
    }

    /**
     * Wie {@link #persist}, nur sofort in diesem Faden und mit Rückmeldung (Abnahme #1112): für die Übergabe, bei der die
     * alte Datei ihre Arbeit erst verliert, wenn der neue Stand sicher abgelegt ist.
     *
     * @return ob dieser Stand oder ein jüngerer der Gruppe abgelegt ist; nein, wenn das Schreiben scheiterte oder die
     *         Quelle inzwischen bereinigt wurde
     */
    private boolean persistNow(String groupId) {
        EnrichmentQueueStore target = queueStore;
        if (target == EnrichmentQueueStore.NONE) {
            return true;
        }
        Snapshot snapshot;
        synchronized (pendingLock) {
            snapshot = snapshot(groupId);
            // Ein älterer, noch nicht genommener Stand derselben Gruppe ist durch diesen überholt.
            unwritten.remove(groupId);
            writing++;
        }
        boolean stored;
        writer.lock();
        try {
            stored = write(target, groupId, snapshot);
        } finally {
            finishWriting();
            writer.unlock();
        }
        writeQueue();
        return stored;
    }

    /**
     * Schreibt einen Stand unter {@link #writer}, außer ein jüngerer der Gruppe ist schon abgelegt oder die Quelle wurde
     * seitdem bereinigt.
     *
     * @return ob dieser Stand oder ein jüngerer abgelegt ist
     */
    private boolean write(EnrichmentQueueStore target, String groupId, Snapshot snapshot) {
        Long done = written.get(groupId);
        Long purged = snapshot.sourceId() == null ? null : forgotten.get(snapshot.sourceId());
        if (purged != null && purged >= snapshot.sequence()) {
            return false;
        }
        if (done != null && done >= snapshot.sequence()) {
            return true;
        }
        try {
            if (snapshot.saved() == null) {
                target.delete(groupId);
            } else {
                target.save(snapshot.saved());
            }
            written.put(groupId, snapshot.sequence());
            return true;
        } catch (RuntimeException e) {
            // Nur die Klasse: Der nächste Stand schreibt neu.
            LOG.log(System.Logger.Level.WARNING, "Warteschlange der KI-Anreicherung {0} nicht abgelegt ({1})",
                    groupId, e.getClass().getName());
            return false;
        }
    }

    /** Nach einem Schreibgang, noch unter {@link #writer}. */
    private void finishWriting() {
        synchronized (pendingLock) {
            // Ohne Stand unterwegs gibt es keinen älteren mehr, den die Folgenummern abweisen müssten.
            if (--writing == 0) {
                written.clear();
                forgotten.clear();
            }
        }
    }

    /**
     * Schreibt die offenen Schnappschüsse, einer nach dem anderen ({@link #writer}); ein älterer Stand überschreibt nie
     * einen neueren, und nach dem Bereinigen einer Quelle schreibt kein Stand von davor ihre Datei neu. Unter
     * {@link #pendingLock} ohne Wirkung; der Aufrufer schreibt nach dem Riegel.
     */
    private void writeQueue() {
        EnrichmentQueueStore target = queueStore;
        if (target == EnrichmentQueueStore.NONE || Thread.holdsLock(pendingLock)) {
            return;
        }
        while (true) {
            List<Map.Entry<String, Snapshot>> batch;
            synchronized (pendingLock) {
                if (unwritten.isEmpty()) {
                    return;
                }
                batch = new ArrayList<>();
                unwritten.forEach((groupId, snapshot) -> batch.add(Map.entry(groupId, snapshot)));
                unwritten.clear();
                writing++;
            }
            writer.lock();
            try {
                for (Map.Entry<String, Snapshot> each : batch) {
                    write(target, each.getKey(), each.getValue());
                }
            } finally {
                finishWriting();
                writer.unlock();
            }
        }
    }

    /**
     * „Daten bereinigen“ und „Quelle entfernen“: vergisst die wartende und die gescheiterte Arbeit der Quelle, auch die
     * abgelegte. Ein Teil der Quelle, der danach noch läuft oder fortgesetzt wird, findet nichts mehr zu tun.
     */
    public void forgetQueued(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        long sequence;
        List<String> dropped = new ArrayList<>();
        synchronized (pendingLock) {
            pending.remove(sourceId);
            groups.values().removeIf(waiting -> {
                if (!waiting.sourceId.equals(sourceId)) {
                    return false;
                }
                waiting.forgotten = true;
                return true;
            });
            failed.values().removeIf(broken -> broken.sourceId.equals(sourceId));
            RetryFailed retry = retryFailed;
            if (retry != null) {
                retry.forget(sourceId);
            }
            unwritten.values().removeIf(snapshot -> sourceId.equals(snapshot.sourceId()));
            handOvers.stream().filter(left -> left.sourceId().equals(sourceId)).map(HandOver::partId)
                    .forEach(dropped::add);
            handOvers.removeIf(left -> left.sourceId().equals(sourceId));
            sequence = ++persistSequence;
        }
        BackgroundJobs jobs = hosted;
        if (jobs != null) {
            // Der gescheiterte Teil, dessen Arbeit noch übergeben werden sollte, setzt danach nie mehr fort.
            dropped.forEach(partId -> retire(jobs, partId, FORGOTTEN_WITH_SOURCE));
        }
        writer.lock();
        try {
            // Auch ein Stand, den ein anderer Faden vor dem Bereinigen genommen hat, schreibt die Datei nicht neu.
            forgotten.put(sourceId, sequence);
            queueStore.removeSource(sourceId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Warteschlange der KI-Anreicherung der Quelle {0} nicht "
                    + "bereinigt ({1})", sourceId, e.getClass().getName());
            throw e;
        } finally {
            writer.unlock();
        }
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
        boolean saved = false;
        synchronized (pendingLock) {
            Pending collecting = pending.get(key.sourceId());
            if (collecting != null) {
                collecting.add(work);
                if (collecting.work.size() % PERSIST_EVERY == 0) {
                    persist(collecting.groupId);
                    saved = true;
                }
            } else {
                Pending created = new Pending(BackgroundJobs.newGroupId(), key.sourceId());
                created.work.add(work);
                pending.put(key.sourceId(), created);
                groups.put(created.groupId, created);
                groupId = created.groupId;
            }
        }
        if (saved) {
            writeQueue();
        }
        // Meldungen an die Auftragsverwaltung nie unter dem eigenen Riegel.
        if (groupId != null) {
            start(jobs, key.sourceId(), groupId);
        }
        return true;
    }

    /** Startet den Teil einer Quelle; nimmt der Hintergrund ihn nicht an, rechnet der bisherige Weg. */
    private void start(BackgroundJobs jobs, String sourceId, String groupId) {
        start(jobs, sourceId, groupId, Map.of(), false);
    }

    /**
     * Startet den Teil der Gruppe. Bei einer Übergabe ({@code handOver}) legt der Aufrufer den Stand selbst ab
     * ({@link #persistNow}), und ein gescheiterter Start gibt die Arbeit nur frei, ohne sie auf dem bisherigen Weg zu
     * rechnen.
     *
     * @return ob der Teil gestartet ist
     */
    private boolean start(BackgroundJobs jobs, String sourceId, String groupId, Map<String, String> extra,
                          boolean handOver) {
        try {
            Map<String, String> parameters = new LinkedHashMap<>(extra);
            parameters.put(Jobs.CANCEL_NOTE, CANCEL_NOTE);
            BackgroundGroup started = jobs.startBehindOpen(groupId, BACKGROUND_KIND, BACKGROUND_TITLE,
                    new BackgroundPart.Draft(sourceId, null, parameters));
            Pending queue;
            synchronized (pendingLock) {
                queue = groups.get(groupId);
                if (queue != null) {
                    queue.partId = started.parts().getFirst().id();
                    if (!handOver) {
                        persist(groupId);
                    }
                }
            }
            writeQueue();
            Subscription[] subscription = new Subscription[1];
            subscription[0] = jobs.watch(groupId, group -> ended(sourceId, groupId, queue, group, subscription));
            // Was schon vor dem Anmelden endete, meldet sich nicht mehr von selbst.
            ended(sourceId, groupId, queue, jobs.group(groupId).orElse(new BackgroundGroup(groupId, null, null,
                    List.of())), subscription);
            return true;
        } catch (RuntimeException e) {
            if (handOver) {
                List<Work> left = release(sourceId, groupId);
                LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung der Quelle {0}: übernommene Arbeit nicht als "
                        + "Hintergrundauftrag gestartet ({1}), {2} Berechnungen warten auf den nächsten Start", sourceId,
                        e.getClass().getSimpleName(), left.size());
                return false;
            }
            // Voll, beendet oder unerwartet (Abnahme #1077): nie eine Quelle ohne Teil zurücklassen.
            List<Work> left = release(sourceId, groupId);
            LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung der Quelle {0} nicht als Hintergrundauftrag "
                    + "gestartet ({1}): {2} Berechnungen auf dem bisherigen Weg", sourceId,
                    e.getClass().getSimpleName(), left.size());
            left.forEach(this::fallback);
            return false;
        }
    }

    /**
     * Nach jeder Änderung der Gruppe: Endet ihr Teil (abgebrochen, gescheitert, fertig, nicht angehalten), holt er die
     * Quelle nicht mehr ab. Was ein gescheiterter liegen ließ, übernimmt ein neuer Teil.
     */
    private void ended(String sourceId, String groupId, Pending queue, BackgroundGroup group,
                       Subscription[] subscription) {
        if (!group.parts().isEmpty() && (!group.finished() || group.paused()
                || group.parts().stream().anyMatch(BackgroundJobs::interrupted))) {
            // Unterbrochen (Beenden des Dienstes): Die Arbeit bleibt für das Fortsetzen nach dem Neustart.
            return;
        }
        // Ohne Teil: gelöscht, wie abgebrochen.
        JobState state = group.parts().isEmpty() ? JobState.CANCELLED : group.parts().getFirst().state();
        // Nur die eigene Warteschlange (Codex #1109): Eine verspätete Meldung des vorigen Laufs gibt nie die
        // Warteschlange frei, die ↻ inzwischen bereitgelegt hat.
        List<Work> left = release(sourceId, groupId, queue);
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
        settle(sourceId, part.map(JobStatus::state).orElse(JobState.CANCELLED),
                release(sourceId, existing.groupId, existing));
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
        return release(sourceId, groupId, null);
    }

    /** Wie oben, nur wenn die Gruppe noch {@code queue} hält ({@code null}: jede). */
    private List<Work> release(String sourceId, String groupId, Pending queue) {
        try {
            synchronized (pendingLock) {
                Pending current = groups.get(groupId);
                if (current == null || queue != null && current != queue) {
                    return List.of();
                }
                groups.remove(groupId);
                pending.remove(sourceId, current);
                if (!closed) {
                    // Beim Beenden bleibt der abgelegte Rest für den Neustart stehen.
                    persist(groupId);
                }
                return List.copyOf(current.work);
            }
        } finally {
            writeQueue();
        }
    }

    /**
     * Ein Teil: rechnet nacheinander, was bei seinem Start für ihn wartete (Abnahme #1077: Eine Quelle mit stetem
     * Zufluss hält so keine andere auf). Scheitert eine Berechnung, meldet er das gelb und macht mit der nächsten weiter.
     */
    private void drain(BackgroundPart part, JobContext context) {
        BackgroundJobs jobs = hosted;
        // Abnahme #1112: Ein übernommener Teil eines Benutzers rechnet nur, wenn ein Mensch ihn fortsetzt.
        boolean byHand = "true".equals(part.parameter(BY_HAND)) && (!part.resumed() || part.automatic());
        if (byHand || part.automatic() && jobs != null && jobs.part(part.jobId())
                .filter(BackgroundJobs::startedByUser).isPresent()) {
            // Abnahme 22.11.2026: Mit dem Benutzer im Speicher fehlen seine Rechte; nie von selbst weiter.
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, RESUME_BY_HAND,
                    "Lauf eines Benutzers; bitte von Hand fortsetzen"));
            context.pause(com.aresstack.spaceharvester.application.jobs.PauseReason.RESUME_BY_HAND);
            return;
        }
        try {
            drainSealed(part, context);
        } finally {
            // Angehalten, abgebrochen, beim Beenden unterbrochen oder fertig: der Stand von jetzt.
            persist(part.groupId());
        }
    }

    private void drainSealed(BackgroundPart part, JobContext context) {
        String groupId = part.groupId();
        Pending mine;
        int waitingAtStart;
        int carried;
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
            Failed before = failed.get(groupId);
            carried = before == null ? 0 : before.work.size();
            persist(groupId);
        }
        writeQueue();
        // Jeder Lauf beginnt bei null (Jobs#rerun): Was vorher scheiterte und noch nicht wiederholt ist, zählt weiter als
        // Fehler, damit ↻ auch nach Fortsetzen oder Neustart angeboten wird (Codex #1109).
        context.discovered(waitingAtStart + carried);
        if (carried > 0) {
            context.processed(carried);
            context.failed(carried);
        }
        // Angelo 22.11.2026: Jeder Auftrag hält sich an die Parallelität der KI-Einstellungen (ai.parallel), auch
        // innerhalb seines Schritts; vorher rechnete ein Teil streng nacheinander.
        int width = Math.max(1, Math.min(MAX_PARALLEL, parallel.getAsInt()));
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(width);
        AtomicReference<RuntimeException> stop = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger sinceSaved = new java.util.concurrent.atomic.AtomicInteger();
        // Je Seite bleibt die Reihenfolge der Prozessoren (die Navigation braucht etwa das Embedding derselben Seite):
        // parallel laufen nur Berechnungen verschiedener Seiten. Jede beendete Berechnung meldet sich hier.
        java.util.concurrent.Semaphore finished = new java.util.concurrent.Semaphore(0);
        try {
            while (stop.get() == null) {
                if (closed) {
                    // Beim Beenden des Dienstes unterbrochen, nicht fertig.
                    throw new JobCancelledException(context.jobId());
                }
                context.checkpoint();
                acquire(slots, 1, context);
                if (closed) {
                    // Während des Wartens auf einen Platz beendet: nichts Neues mehr anfangen, der Rest bleibt abgelegt.
                    slots.release();
                    throw new JobCancelledException(context.jobId());
                }
                finished.drainPermits();
                Work next = null;
                boolean blocked;
                synchronized (pendingLock) {
                    if (closed || groups.get(groupId) != mine || mine.work.isEmpty()) {
                        slots.release();
                        break;
                    }
                    Set<PageKey> busy = new HashSet<>();
                    mine.running.forEach(each -> busy.add(each.key()));
                    for (java.util.Iterator<Work> waiting = mine.work.iterator(); waiting.hasNext(); ) {
                        Work candidate = waiting.next();
                        if (!busy.contains(candidate.key())) {
                            next = candidate;
                            waiting.remove();
                            mine.running.add(next);
                            break;
                        }
                    }
                    blocked = next == null;
                }
                if (blocked) {
                    // Alles Wartende gehört zu Seiten, die gerade rechnen: auf deren Ende warten, dann neu wählen.
                    slots.release();
                    acquire(finished, 1, context);
                    continue;
                }
                Work started = next;
                Thread.ofVirtual().name("enrichment-" + mine.sourceId).start(() -> {
                    try {
                        computeOne(part, context, groupId, mine, started, sinceSaved);
                    } catch (RuntimeException e) {
                        stop.compareAndSet(null, e);
                    } finally {
                        slots.release();
                        finished.release();
                    }
                });
            }
        } finally {
            // Laufende Berechnungen enden immer mit ihrem Ergebnis oder kommen wieder vorn an, nie mittendrin
            // abgebrochen (ein Interrupt schlösse die Dateikanäle des Lucene-Index).
            slots.acquireUninterruptibly(width);
            slots.release(width);
        }
        RuntimeException stopped = stop.get();
        if (stopped != null) {
            throw stopped;
        }
        if (closed) {
            throw new JobCancelledException(context.jobId());
        }
        synchronized (pendingLock) {
            if (groups.get(groupId) == mine && mine.work.isEmpty() && mine.running.isEmpty()) {
                groups.remove(groupId);
            }
        }
    }

    /**
     * Woher ein Teil der Warteschlange seine Breite nimmt (ai.parallel, Angelo 22.11.2026: jeder Auftrag hält sich an
     * die Job-Parallel-Einstellungen, auch innerhalb seines Schritts). Ohne Angabe rechnet er nacheinander.
     */
    public void parallel(IntSupplier value) {
        this.parallel = Objects.requireNonNull(value, "value");
    }

    /**
     * Angelo 22.11.2026: Ohne Netz wartet die KI-Anreicherung, bis es zurück ist, und rechnet dieselbe Seite dann im
     * selben Teil weiter; ein Netzfehler zählt nicht als Fehlversuch und nicht als gescheitert.
     */
    public void network(com.aresstack.spaceharvester.application.network.NetworkState value) {
        this.network = value;
    }

    /** Höchstens so viele Berechnungen eines Teils zugleich, gleich was ai.parallel sagt. */
    static final int MAX_PARALLEL = 16;
    /**
     * So oft rechnet ein Teil eine Berechnung, die am Dienst scheiterte (nicht erreichbar, Frist), bevor sie als
     * gescheitert zählt (Angelo 22.11.2026: selbstheilend, nie stumm scheitern). Jeder Fehlversuch steht mit Grund in
     * der Diagnose.
     */
    static final int ATTEMPTS = 3;

    /** Wartet auf einen Platz; Anhalten und Abbrechen greifen auch währenddessen. */
    private static void acquire(java.util.concurrent.Semaphore slots, int permits, JobContext context) {
        while (true) {
            context.checkpoint();
            try {
                if (slots.tryAcquire(permits, 1, java.util.concurrent.TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobCancelledException(context.jobId());
            }
        }
    }

    /**
     * Eine Berechnung des Teils, auf ihrem eigenen Thread: am Dienst gescheiterte rechnet sie bis {@link #ATTEMPTS}-mal
     * (erzwungen, ein gescheitertes Ergebnis gälte sonst als aktuell), jeder Fehlversuch mit Grund in der Diagnose;
     * erst der letzte zählt als gescheitert und wartet auf ↻.
     */
    private void computeOne(BackgroundPart part, JobContext context, String groupId, Pending mine, Work next,
                            java.util.concurrent.atomic.AtomicInteger sinceSaved) {
        Work attempt = next;
        Queued queued;
        int tries = 0;
        com.aresstack.spaceharvester.application.network.NetworkState net = network;
        com.aresstack.spaceharvester.application.network.NetworkWait wait = net == null ? null
                : new com.aresstack.spaceharvester.application.network.NetworkWait(net, context, COMPONENT);
        while (true) {
            tries++;
            try {
                if (wait != null) {
                    wait.beforeFetch();
                }
                queued = computeWork(attempt);
            } catch (JobCancelledException e) {
                // Angehalten, abgebrochen oder beim Beenden unterbrochen: Die Berechnung kommt wieder vorn an.
                backToFront(groupId, mine, next);
                throw e;
            } catch (RuntimeException e) {
                // Abnahme #1077: Sonst scheiterte der Teil, und jeder Ersatzteil verlöre die nächste Berechnung. Im Log
                // nur Quelle und Exception-Klasse, die Meldung könnte Text der Seite enthalten.
                LOG.log(System.Logger.Level.WARNING, "KI-Anreicherung {0} in Quelle {1} gescheitert ({2})",
                        next.processorId(), mine.sourceId, e.getClass().getName());
                queued = new Queued(Outcome.FAILED, e);
            }
            if (wait != null && !closed) {
                boolean offline;
                try {
                    // Wartet ohne Netz, bis es zurück ist (Beobachter, kein Abfragen); dann dieselbe Seite erneut.
                    offline = queued.outcome() == Outcome.FAILED && wait.failed(queued.cause());
                } catch (RuntimeException e) {
                    backToFront(groupId, mine, next);
                    throw e;
                }
                if (offline) {
                    tries--;
                    attempt = new Work(next.processorId(), next.key(), true);
                    continue;
                }
                if (queued.outcome() == Outcome.WRITTEN) {
                    wait.answered();
                }
            }
            if (queued.outcome() != Outcome.FAILED || closed || tries >= ATTEMPTS || !retryable(queued.cause())) {
                break;
            }
            context.record(failure(next, mine.sourceId, queued.cause(), tries));
            try {
                context.checkpoint();
            } catch (RuntimeException e) {
                // Zwischen zwei Versuchen angehalten oder abgebrochen: Die Berechnung kommt wieder vorn an.
                backToFront(groupId, mine, next);
                throw e;
            }
            attempt = new Work(next.processorId(), next.key(), true);
        }
        Outcome outcome = queued.outcome();
        if (outcome == Outcome.FAILED && closed) {
            // Abnahme #1112: Beim Beenden unterbrochen ist kein Fehler der Seite. Sie wartet wieder (erzwungen, ein
            // gescheitertes Ergebnis gälte sonst als aktuell), ohne rotes Ereignis; der Teil gilt als unterbrochen.
            backToFront(groupId, mine, next);
            throw new JobCancelledException(context.jobId());
        }
        boolean mineStill;
        synchronized (pendingLock) {
            mine.running.remove(next);
            // Nach dem Bereinigen der Quelle (forgetQueued) nimmt nichts mehr Gescheitertes auf (Abnahme #1112).
            mineStill = groups.get(groupId) == mine;
            if (outcome == Outcome.FAILED && mineStill) {
                failed.computeIfAbsent(groupId, ignored -> new Failed(mine.sourceId, part.jobId())).work.add(next);
            }
        }
        if (outcome == Outcome.FAILED) {
            context.record(failure(next, mine.sourceId, queued.cause()));
        }
        switch (outcome) {
            case WRITTEN -> context.written(1);
            case FAILED -> context.failed(1);
            case SKIPPED -> {
            }
        }
        context.processed(1);
        // Ein Fehlschlag sofort (Nachabnahme #1112): Sein FAILED-Ergebnis gilt schon als aktuell, nach einem Absturz
        // käme er sonst als gewöhnliche Berechnung zurück und fiele weg. Geschrieben wird außerhalb des Riegels.
        if ((sinceSaved.incrementAndGet() >= PERSIST_EVERY || outcome == Outcome.FAILED) && mineStill) {
            sinceSaved.set(0);
            persist(groupId);
        }
    }

    /**
     * Ob ein Fehlschlag am Dienst lag und ein neuer Versuch helfen kann: nicht erreichbar oder Frist überschritten,
     * auch als Netz-Ausnahme in der Kette. Eine unbrauchbare Antwort, eine fehlende Freigabe oder ein Fehler im eigenen
     * Code wiederholt sich nicht.
     */
    static boolean retryable(Throwable cause) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable each = cause; each != null && seen.add(each); each = each.getCause()) {
            if (each instanceof com.aresstack.spaceharvester.ai.AiServiceException service) {
                return service.reason() == com.aresstack.spaceharvester.ai.AiServiceException.Reason.UNREACHABLE
                        || service.reason() == com.aresstack.spaceharvester.ai.AiServiceException.Reason.TIMEOUT;
            }
            if (each instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /** Stellt die laufende Berechnung wieder vorn an, solange die Gruppe dem Teil gehört. */
    private void backToFront(String groupId, Pending mine, Work next) {
        synchronized (pendingLock) {
            if (mine.running.remove(next)) {
                if (groups.get(groupId) == mine) {
                    mine.pushFront(next);
                }
            }
        }
    }

    /**
     * Das rote Ereignis einer gescheiterten Berechnung (Angelo 22.11.2026: jeder Fehler mit Grund in der Diagnose).
     * Abnahme: nur Quelle, Seite als Verweis, Prozessor, Exception-Klassen und die Befunde der Kette (Bereich, HTTP,
     * TLS, Stelle im Space Harvester), nie Meldungstexte, denn die könnten Seiteninhalt oder eine Modellantwort
     * enthalten.
     */
    static JobEvent.Draft failure(Work work, String sourceId, Throwable cause) {
        return failure(work, sourceId, cause, 0);
    }

    /**
     * Wie {@link #failure(Work, String, Throwable)}; mit {@code attempt > 0} ein Fehlversuch, der wiederholt wird
     * (Angelo 22.11.2026: Versuchszähler und Grund in der Diagnose), als Warnung statt als Fehler.
     */
    static JobEvent.Draft failure(Work work, String sourceId, Throwable cause, int attempt) {
        String where = "Berechnung " + work.processorId() + " der Seite " + work.key().pageId() + " in Quelle "
                + sourceId + " gescheitert";
        boolean retried = attempt > 0;
        JobEvent.Severity severity = retried ? JobEvent.Severity.WARNING : JobEvent.Severity.ERROR;
        String then = retried ? "; Versuch " + attempt + " von " + ATTEMPTS + ", wird wiederholt"
                : "; weiter mit der nächsten";
        if (cause == null) {
            return new JobEvent.Draft(severity, JobEvent.Category.JOB, COMPONENT, WORK_FAILED,
                    where + then, null, null, null);
        }
        com.aresstack.spaceharvester.application.jobs.FailureDiagnosis.Findings findings =
                com.aresstack.spaceharvester.application.jobs.FailureDiagnosis.of(cause, WORK_FAILED);
        Throwable root = cause;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        String reason = cause.getClass().getSimpleName()
                + (root == cause ? "" : " ← " + root.getClass().getSimpleName())
                + (findings.httpStatus() == null ? "" : ", HTTP " + findings.httpStatus())
                + (findings.component() == null ? "" : ", in " + findings.component());
        // Der TLS-Befund trägt sonst die Meldung der Ausnahme (Codex #1103): nur ihre Klasse.
        String tls = findings.tlsFinding();
        if (tls != null && tls.indexOf(": ") >= 0) {
            tls = tls.substring(0, tls.indexOf(": "));
        }
        return new JobEvent.Draft(severity, findings.category(), COMPONENT, WORK_FAILED,
                where + ": " + reason + then, findings.httpStatus(), tls, null);
    }

    /** Rechnet eine Berechnung der Warteschlange mit dem Snapshot von jetzt. */
    private Queued computeWork(Work work) {
        EnrichmentProcessor processor = processors.get(work.processorId());
        Optional<PageSnapshot> snapshot = processor == null ? Optional.empty() : lookup.apply(work.key());
        Optional<PageText> text = snapshot.filter(page -> !sectionOwned(page, processor)).flatMap(this::projection);
        if (text.isEmpty()) {
            return Queued.SKIPPED;
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
        Optional<PageText> text = snapshot.filter(page -> !sectionOwned(page, processor)).flatMap(this::projection);
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
        removedParts.close();
        // Was wartet oder angehalten ist, für den Neustart; laufende Teile legen ihren Stand selbst ab.
        List<String> open;
        synchronized (pendingLock) {
            Set<String> ids = new java.util.LinkedHashSet<>(groups.keySet());
            ids.addAll(failed.keySet());
            open = List.copyOf(ids);
        }
        open.forEach(this::persist);
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
        return attempt(processor, snapshot, text, input).result();
    }

    /** Ein Ergebnis und, wenn der Prozessor scheiterte, seine Ausnahme. */
    private record Computed(Enrichment result, RuntimeException cause) {
    }

    /** Wie {@link #compute}, mit der Ausnahme eines gescheiterten Prozessors für die Diagnose des Teils. */
    private Computed attempt(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input) {
        try {
            EnrichmentOutput output = processor.process(snapshot, text);
            String version = output.version() != null ? output.version() : processor.version();
            return new Computed(new Enrichment(snapshot.key(), processor.id(), version, input, snapshot.revision(),
                    Enrichment.Status.OK, output.payloadJson(), output.warnings(), null, clock.instant()), null);
        } catch (ProcessingNotAllowedException e) {
            // Kein Fehler der Seite: nichts speichern, die Seite nicht nennen.
            LOG.log(System.Logger.Level.DEBUG, "Prozessor {0} übersprungen, Quelle {1} nicht freigegeben",
                    processor.id(), snapshot.key().sourceId());
            return new Computed(null, null);
        } catch (RuntimeException e) {
            // Keine Meldung in Ergebnis oder Log: sie könnte Text der Seite enthalten.
            LOG.log(System.Logger.Level.WARNING, "Prozessor {0} {1} scheiterte an Seite {2}/{3} ({4})",
                    processor.id(), processor.version(), snapshot.key().sourceId(), snapshot.key().pageId(),
                    e.getClass().getName());
            return new Computed(new Enrichment(snapshot.key(), processor.id(), processor.version(), input,
                    snapshot.revision(), Enrichment.Status.FAILED, "{}", List.of(), PROCESSOR_ERROR, clock.instant()), e);
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
