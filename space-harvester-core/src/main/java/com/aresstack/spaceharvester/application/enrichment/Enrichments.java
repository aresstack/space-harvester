package com.aresstack.spaceharvester.application.enrichment;

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
import java.util.regex.Pattern;

/**
 * Berechnet und liest die Anreicherungen einer Seite. Jeder Prozessor läuft für sich: Scheitert einer, wird nur sein
 * Ergebnis als fehlgeschlagen gespeichert; die übrigen, die Payload und jeder Abgleich oder Export laufen weiter.
 *
 * <p>Prozessoren, die über das Netz rechnen ({@link EnrichmentProcessor#background()}), laufen bei {@link #refresh}
 * in einem begrenzten Hintergrund ({@link #background(int)}): höchstens eine Berechnung zugleich, eine Warteschlange
 * fester Länge, jede Seite höchstens einmal darin. Ist sie voll, wird die Seite übersprungen und beim nächsten
 * Abgleich oder Abruf nachgeholt, weil ihr Ergebnis dann noch fehlt oder veraltet ist.
 */
public class Enrichments {

    /** Fehlercode eines Prozessors, der eine Ausnahme geworfen hat. */
    public static final String PROCESSOR_ERROR = "PROCESSOR_ERROR";

    /**
     * Vorbehaltene Prozessor-ID: unter ihr legt die Lese-Ansicht (#176) ihre abgeleitete Darstellung in derselben
     * Ablage ab. Sie ist kein Prozessor, erscheint nie in {@link #view} und wird mit der Seite entfernt.
     */
    public static final String READING_VIEW = "reading-view";

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

    /** Die Kennungen der Prozessoren in Ausführungsreihenfolge. */
    public List<String> processorIds() {
        return List.copyOf(processors.keySet());
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
            if (READING_VIEW.equals(enrichment.processorId())) {
                // Rechte für eingebundene Inhalte prüft nur die Lese-Ansicht selbst.
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
                schedule(processor, snapshot, text, input);
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
                    if (!processor.appliesTo(snapshot.key())) {
                        return;
                    }
                    Enrichment previous = store.find(snapshot.key()).stream()
                            .filter(enrichment -> enrichment.processorId().equals(processor.id())).findFirst()
                            .orElse(null);
                    if (!force && previous != null
                            && previous.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT) {
                        return;
                    }
                    if (saveWhileApplies(processor, compute(processor, snapshot, text, input))) {
                        store.flush();
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Hintergrundberechnung {0} scheiterte ({1})",
                            processor.id(), e.getClass().getName());
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
