package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static final System.Logger LOG = System.getLogger(Enrichments.class.getName());
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final PageTextProjector projector;
    private final Map<String, EnrichmentProcessor> processors = new LinkedHashMap<>();
    private final EnrichmentStore store;
    private final Clock clock;
    private final ExecutorService background;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

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
     * Die gespeicherten Ergebnisse einer Seite mit ihrer Aktualität gegenüber der übergebenen Payload. Ergebnisse von
     * Prozessoren, die es nicht mehr gibt, und alle Ergebnisse einer nicht projizierbaren Payload sind veraltet.
     */
    public List<View> view(PageSnapshot snapshot) {
        String input = projection(snapshot).map(PageText::inputSha256).orElse(null);
        List<View> views = new ArrayList<>();
        for (Enrichment enrichment : store.find(snapshot.key())) {
            EnrichmentProcessor processor = processors.get(enrichment.processorId());
            Enrichment.Freshness freshness = input == null ? Enrichment.Freshness.STALE
                    : enrichment.freshness(input, processor == null ? null : processor.version());
            views.add(new View(enrichment, freshness));
        }
        return views;
    }

    /** Macht die Ergebnisse dauerhaft, etwa am Ende eines Abgleichs. */
    public void flush() {
        store.flush();
    }

    /** Entfernt alle Ergebnisse einer Seite, die es im Cache nicht mehr gibt. */
    public void remove(PageKey key) {
        store.removeAll(key);
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
            if (!force && background != null && processor.background()) {
                schedule(processor, snapshot, text, input);
                continue;
            }
            Enrichment result = compute(processor, snapshot, text, input);
            store.save(result);
            computed++;
            if (result.status() == Enrichment.Status.FAILED) {
                failed++;
            }
        }
        return new Run(true, computed, current, failed);
    }

    private void schedule(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input) {
        String id = processor.id() + "\n" + snapshot.key().sourceId() + "\n" + snapshot.key().pageId();
        if (!queued.add(id)) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    Enrichment previous = store.find(snapshot.key()).stream()
                            .filter(enrichment -> enrichment.processorId().equals(processor.id())).findFirst()
                            .orElse(null);
                    if (previous != null
                            && previous.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT) {
                        return;
                    }
                    store.save(compute(processor, snapshot, text, input));
                    store.flush();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Hintergrundberechnung {0} scheiterte ({1})",
                            processor.id(), e.getClass().getName());
                } finally {
                    queued.remove(id);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.remove(id);
            LOG.log(System.Logger.Level.INFO, "Hintergrund der Anreicherungen voll oder beendet; {0} wird später "
                    + "nachgeholt", processor.id());
        }
    }

    private Enrichment compute(EnrichmentProcessor processor, PageSnapshot snapshot, PageText text, String input) {
        try {
            EnrichmentOutput output = processor.process(text);
            return new Enrichment(snapshot.key(), processor.id(), processor.version(), input, snapshot.revision(),
                    Enrichment.Status.OK, output.payloadJson(), output.warnings(), null, clock.instant());
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
