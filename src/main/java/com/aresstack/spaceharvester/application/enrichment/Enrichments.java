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
import java.util.regex.Pattern;

/**
 * Berechnet und liest die Anreicherungen einer Seite. Jeder Prozessor läuft für sich: Scheitert einer, wird nur sein
 * Ergebnis als fehlgeschlagen gespeichert; die übrigen, die Payload und jeder Abgleich oder Export laufen weiter.
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

    public Enrichments(PageTextProjector projector, List<EnrichmentProcessor> processors, EnrichmentStore store,
                       Clock clock) {
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

    /** Die Kennungen der Prozessoren in Ausführungsreihenfolge. */
    public List<String> processorIds() {
        return List.copyOf(processors.keySet());
    }

    /**
     * Berechnet die Ergebnisse, die fehlen oder veraltet sind. Ein aktuelles Ergebnis, auch ein fehlgeschlagenes, wird
     * nicht wiederholt; dafür gibt es {@link #recompute}.
     */
    public Run refresh(PageSnapshot snapshot) {
        return run(snapshot, null, false);
    }

    /**
     * Berechnet die Ergebnisse der genannten Prozessoren neu, auch wenn sie aktuell sind (Neuindexierung).
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
            if (only != null && !only.equals(processor.id())) {
                continue;
            }
            Enrichment previous = stored.get(processor.id());
            if (!force && previous != null
                    && previous.freshness(input, processor.version()) == Enrichment.Freshness.CURRENT) {
                current++;
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
