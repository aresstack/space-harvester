package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Completion;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.RankedHit;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Variant;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Die laufenden und kürzlich beendeten progressiven Suchen des Servers (ADR 0017) für
 * {@code /api/v1/search/progressive}. Jede gehört dem Aufrufer, der sie gestartet hat ({@link AccessContext#jobOwner()});
 * ein anderer bekommt sie nie zu sehen, auch nicht, dass es sie gibt. Die Grenzen sind getrennt einstellbar
 * ({@link Limits}, {@code space-harvester.search.progressive.*}, F38-4): Höchstens {@code maxRunning} Suchen laufen
 * zugleich, eine weitere wird ausdrücklich abgewiesen (wartende Suchen gibt es nicht). Beendete bleiben
 * {@code retention} lang abrufbar. Eine neue Suche verdrängt nie eine andere, weder eine laufende noch eine beendete.
 *
 * <p>Unterwegs hält jede Suche den letzten Stand aus ihren Ereignissen (Suchanfragen und sortierte Liste), nach dem
 * Abschluss das fertige Ergebnis. Suchtexte und Seiten-IDs kommen nie ins Log.
 */
public final class ProgressiveSearches implements AutoCloseable {

    /** Standard für {@code space-harvester.search.progressive.max-running}. */
    public static final int DEFAULT_MAX_RUNNING = 256;
    /** Standard für {@code space-harvester.search.progressive.retention}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);

    /**
     * Grenzen des Registers.
     *
     * @param maxRunning gleichzeitig laufende Suchen, mindestens 1
     * @param retention  so lange bleibt eine beendete Suche abrufbar
     */
    public record Limits(int maxRunning, Duration retention) {

        public static final Limits DEFAULTS = new Limits(DEFAULT_MAX_RUNNING, DEFAULT_RETENTION);

        public Limits {
            Objects.requireNonNull(retention, "retention");
            if (maxRunning < 1 || retention.isNegative()) {
                throw new IllegalArgumentException("maxRunning mindestens 1, retention nicht negativ");
            }
        }
    }

    private final SearchOrchestrator orchestrator;
    private final Supplier<SearchProfile> defaults;
    private final LongSupplier nanos;
    private final Limits limits;
    private final Map<String, Tracked> searches = new LinkedHashMap<>();

    /**
     * @param defaults das Standardprofil, etwa mit Varianten vom Chat-Modell, wenn die wirksame Konfiguration eines hat
     */
    public ProgressiveSearches(SearchOrchestrator orchestrator, Supplier<SearchProfile> defaults, Limits limits) {
        this(orchestrator, defaults, limits, System::nanoTime);
    }

    ProgressiveSearches(SearchOrchestrator orchestrator, Supplier<SearchProfile> defaults, Limits limits,
                        LongSupplier nanos) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    /** Die eingestellten Grenzen. */
    public Limits limits() {
        return limits;
    }

    /** Das Standardprofil der wirksamen Konfiguration. */
    public SearchProfile defaults() {
        return defaults.get();
    }

    /**
     * Startet eine Suche für den Aufrufer.
     *
     * @throws CapacityExceededException wenn schon {@code maxRunning} Suchen laufen
     */
    public Tracked start(AccessContext caller, KeywordSearch.Query query, SearchProfile profile) {
        Objects.requireNonNull(caller, "caller");
        Tracked tracked = new Tracked(caller.jobOwner(), profile, nanos.getAsLong());
        synchronized (searches) {
            purge();
            if (running() >= limits.maxRunning()) {
                throw new CapacityExceededException();
            }
            // Ereignisse können kommen, bevor searchAsync zurückkehrt: Der Stand nimmt sie schon auf.
            ProgressiveSearch search = orchestrator.searchAsync(caller, query, profile, tracked);
            tracked.search = search;
            searches.put(search.id(), tracked);
        }
        tracked.search.result().whenComplete((result, failure) -> tracked.finishedAt = nanos.getAsLong());
        return tracked;
    }

    /** Die Suche mit dieser Kennung, nur für ihren Eigentümer; sonst leer, wie eine unbekannte. */
    public Optional<Tracked> find(AccessContext caller, String id) {
        if (caller == null || id == null) {
            return Optional.empty();
        }
        synchronized (searches) {
            purge();
            Tracked tracked = searches.get(id);
            return tracked != null && tracked.owner.equals(caller.jobOwner()) ? Optional.of(tracked)
                    : Optional.empty();
        }
    }

    /**
     * Bricht die Suche ab; was gefunden ist, bleibt im Ergebnis.
     *
     * @return ob es die Suche für diesen Aufrufer gibt
     */
    public boolean cancel(AccessContext caller, String id) {
        Optional<Tracked> tracked = find(caller, id);
        tracked.ifPresent(search -> search.search.cancel());
        return tracked.isPresent();
    }

    /** Bricht alle laufenden Suchen ab; die Suchmaschine schließt danach ihre Konfiguration. */
    @Override
    public void close() {
        synchronized (searches) {
            searches.values().forEach(tracked -> tracked.search.cancel());
            searches.clear();
        }
    }

    private void purge() {
        long now = nanos.getAsLong();
        searches.values().removeIf(tracked -> {
            Long finished = tracked.finishedAt;
            return finished != null && now - finished >= limits.retention().toNanos();
        });
    }

    private long running() {
        return searches.values().stream().filter(tracked -> !tracked.search.result().isDone()).count();
    }

    /** Zu viele laufende Suchen. */
    public static final class CapacityExceededException extends RuntimeException {

        CapacityExceededException() {
            super("Zu viele laufende progressive Suchen");
        }
    }

    /**
     * Stand einer Suche.
     *
     * @param done    ob die Suche beendet ist
     * @param reason  warum sie endete, {@code null}, solange sie läuft
     * @param hits    sichtbare Treffer, beste zuerst
     * @param variants alle Suchanfragen, die ursprüngliche zuerst
     * @param loads   Kennungen des Ladens in den Cache, erst nach dem Abschluss
     * @param elapsed Dauer bisher bzw. bis zum Abschluss
     */
    public record Snapshot(String id, SearchProfile profile, boolean done, Completion reason, List<RankedHit> hits,
                           List<Variant> variants, List<String> loads, Duration elapsed) {
    }

    /** Eine bekannte Suche mit dem letzten Stand aus ihren Ereignissen. */
    public final class Tracked implements ProgressiveSearchListener {

        private final String owner;
        private final SearchProfile profile;
        private final long started;
        private volatile ProgressiveSearch search;
        private volatile Long finishedAt;
        private volatile List<RankedHit> hits = List.of();
        private volatile List<Variant> variants = List.of();

        private Tracked(String owner, SearchProfile profile, long started) {
            this.owner = owner;
            this.profile = profile;
            this.started = started;
        }

        public String id() {
            return search.id();
        }

        public SearchProfile profile() {
            return profile;
        }

        /** Der Vorgang selbst, etwa um auf das Ergebnis zu warten. */
        public ProgressiveSearch search() {
            return search;
        }

        /** Nach dem Abschluss das fertige Ergebnis, sonst der letzte gemeldete Stand. */
        public Snapshot snapshot() {
            ProgressiveResult result = search.result().getNow(null);
            if (result != null) {
                return new Snapshot(id(), profile, true, result.reason(), result.hits(), result.variants(),
                        result.loads(), result.elapsed());
            }
            return new Snapshot(id(), profile, false, null, hits, variants, List.of(),
                    Duration.ofNanos(nanos.getAsLong() - started));
        }

        @Override
        public void variants(List<Variant> next) {
            variants = List.copyOf(next);
        }

        @Override
        public void ranked(List<RankedHit> next) {
            hits = List.copyOf(next);
        }
    }
}
