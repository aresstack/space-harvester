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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Die laufenden, wartenden und kürzlich beendeten progressiven Suchen des Servers (ADR 0017) für
 * {@code /api/v1/search/progressive}. Jede gehört dem Aufrufer, der sie gestartet hat ({@link AccessContext#jobOwner()});
 * ein anderer bekommt sie nie zu sehen, auch nicht, dass es sie gibt. Die Grenzen sind getrennt einstellbar
 * ({@link Limits}, {@code space-harvester.search.progressive.*}, F38-4, ADR 0038 Punkt 10): Höchstens
 * {@code maxRunning} Suchen laufen zugleich, höchstens {@code maxWaiting} weitere warten in der Reihenfolge ihres
 * Starts auf einen Platz, darüber wird eine neue ausdrücklich abgewiesen. Beendete bleiben {@code retention} lang
 * abrufbar. Eine neue Suche verdrängt nie eine andere, weder eine laufende noch eine wartende oder beendete.
 *
 * <p>Das Register bleibt eigenständig und geht nicht in der Auftragsinfrastruktur ({@code Jobs}) auf (ADR 0038,
 * Punkt 10, geprüft am 26.11.2026): Eine progressive Suche lebt Sekunden bis wenige Minuten im Speicher, gehört einem
 * REST-Aufrufer und liefert Zwischenstände; Aufträge werden dauerhaft gespeichert, sieben Tage aufbewahrt und in der
 * Auftragsleiste angezeigt. Als Auftrag stünde jede Suche dort und belegte deren Vorrat.
 *
 * <p>Unterwegs hält jede Suche den letzten Stand aus ihren Ereignissen (Suchanfragen und sortierte Liste), nach dem
 * Abschluss das fertige Ergebnis. Suchtexte und Seiten-IDs kommen nie ins Log.
 */
public final class ProgressiveSearches implements AutoCloseable {

    /** Standard für {@code space-harvester.search.progressive.max-running}. */
    public static final int DEFAULT_MAX_RUNNING = 256;
    /** Standard für {@code space-harvester.search.progressive.max-waiting}. */
    public static final int DEFAULT_MAX_WAITING = 256;
    /** Standard für {@code space-harvester.search.progressive.retention}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);
    /** Standard für {@code space-harvester.search.progressive.max-time-budget}: höchstes Zeitbudget einer Anfrage. */
    public static final Duration DEFAULT_MAX_TIME_BUDGET = Duration.ofMinutes(2);

    /**
     * Grenzen des Registers.
     *
     * @param maxRunning    gleichzeitig laufende Suchen, mindestens 1
     * @param maxWaiting    Suchen, die auf einen Platz warten, mindestens 0 ({@code 0}: eine weitere wird sofort
     *                      abgewiesen)
     * @param retention     so lange bleibt eine beendete Suche abrufbar
     * @param maxTimeBudget höchstes Zeitbudget, das eine Anfrage verlangen darf
     */
    public record Limits(int maxRunning, int maxWaiting, Duration retention, Duration maxTimeBudget) {

        public static final Limits DEFAULTS = new Limits(DEFAULT_MAX_RUNNING, DEFAULT_MAX_WAITING, DEFAULT_RETENTION,
                DEFAULT_MAX_TIME_BUDGET);

        public Limits {
            Objects.requireNonNull(retention, "retention");
            Objects.requireNonNull(maxTimeBudget, "maxTimeBudget");
            if (maxRunning < 1 || maxWaiting < 0 || retention.isNegative() || maxTimeBudget.isNegative()
                    || maxTimeBudget.isZero()) {
                throw new IllegalArgumentException("maxRunning mindestens 1, maxWaiting und retention nicht negativ, "
                        + "maxTimeBudget positiv");
            }
        }
    }

    private final SearchOrchestrator orchestrator;
    private final Supplier<SearchProfile> defaults;
    private final LongSupplier nanos;
    private final Limits limits;
    /** Alle bekannten Suchen nach Kennung; unter sich selbst als Sperre. */
    private final Map<String, Tracked> searches = new LinkedHashMap<>();
    /** Wartende Suchen in der Reihenfolge ihres Starts; unter {@link #searches}. */
    private final Deque<Tracked> waiting = new ArrayDeque<>();

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
     * Startet eine Suche für den Aufrufer; ist kein Platz frei, wartet sie auf einen.
     *
     * @throws CapacityExceededException wenn schon {@code maxRunning} Suchen laufen und {@code maxWaiting} warten
     */
    public Tracked start(AccessContext caller, KeywordSearch.Query query, SearchProfile profile) {
        Objects.requireNonNull(caller, "caller");
        Tracked tracked = new Tracked(caller, query, profile, nanos.getAsLong());
        synchronized (searches) {
            purge();
            if (running() < limits.maxRunning()) {
                run(tracked);
            } else if (waiting.size() < limits.maxWaiting()) {
                waiting.addLast(tracked);
            } else {
                throw new CapacityExceededException();
            }
            searches.put(tracked.id, tracked);
        }
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
     * Bricht die Suche ab; was gefunden ist, bleibt im Ergebnis. Eine wartende endet sofort ohne Treffer.
     *
     * @return ob es die Suche für diesen Aufrufer gibt
     */
    public boolean cancel(AccessContext caller, String id) {
        Optional<Tracked> found = find(caller, id);
        found.ifPresent(tracked -> {
            ProgressiveSearch search;
            synchronized (searches) {
                search = tracked.search;
                if (search == null && waiting.remove(tracked)) {
                    tracked.finish(cancelledWhileWaiting());
                }
            }
            if (search != null) {
                search.cancel();
            }
        });
        return found.isPresent();
    }

    /** Bricht alle laufenden Suchen ab; die Suchmaschine schließt danach ihre Konfiguration. */
    @Override
    public void close() {
        synchronized (searches) {
            waiting.forEach(tracked -> tracked.finish(cancelledWhileWaiting()));
            waiting.clear();
            searches.values().stream().map(tracked -> tracked.search).filter(Objects::nonNull)
                    .forEach(ProgressiveSearch::cancel);
            searches.clear();
        }
    }

    /** Unter {@link #searches}: startet die Suche und, sobald sie endet, die nächste wartende. */
    private void run(Tracked tracked) {
        tracked.startedAt = nanos.getAsLong();
        // Ereignisse können kommen, bevor searchAsync zurückkehrt: Der Stand nimmt sie schon auf.
        ProgressiveSearch search = orchestrator.searchAsync(tracked.caller, tracked.query, tracked.profile, tracked);
        tracked.search = search;
        search.result().whenComplete((result, failure) -> {
            tracked.finish(result != null ? result : ended(Completion.FAILED));
            synchronized (searches) {
                Tracked next;
                while (running() < limits.maxRunning() && (next = waiting.pollFirst()) != null) {
                    run(next);
                }
            }
        });
    }

    private static ProgressiveResult cancelledWhileWaiting() {
        return ended(Completion.CANCELLED);
    }

    /** Ein Ergebnis ohne Treffer und Suchanfragen, etwa für eine Suche, die nie einen Platz bekam. */
    private static ProgressiveResult ended(Completion reason) {
        return new ProgressiveResult(List.of(), List.of(), reason, List.of(), Duration.ZERO, null, null, 0, 0);
    }

    private void purge() {
        long now = nanos.getAsLong();
        searches.values().removeIf(tracked -> {
            Long finished = tracked.finishedAt;
            return finished != null && now - finished >= limits.retention().toNanos();
        });
    }

    private long running() {
        return searches.values().stream()
                .filter(tracked -> tracked.search != null && !tracked.search.result().isDone()).count();
    }

    /** Zu viele laufende und wartende Suchen. */
    public static final class CapacityExceededException extends RuntimeException {

        CapacityExceededException() {
            super("Zu viele laufende und wartende progressive Suchen");
        }
    }

    /**
     * Stand einer Suche.
     *
     * @param waiting ob die Suche noch auf einen Platz wartet
     * @param done    ob die Suche beendet ist
     * @param reason  warum sie endete, {@code null}, solange sie läuft
     * @param hits    sichtbare Treffer, beste zuerst
     * @param variants alle Suchanfragen, die ursprüngliche zuerst
     * @param loads   Kennungen des Ladens in den Cache, erst nach dem Abschluss
     * @param elapsed Dauer bisher bzw. bis zum Abschluss
     */
    public record Snapshot(String id, SearchProfile profile, boolean waiting, boolean done, Completion reason, List<RankedHit> hits,
                           List<Variant> variants, List<String> loads, Duration elapsed) {
    }

    /** Eine bekannte Suche mit dem letzten Stand aus ihren Ereignissen. */
    public final class Tracked implements ProgressiveSearchListener {

        private final String id = java.util.UUID.randomUUID().toString();
        private final String owner;
        private final AccessContext caller;
        private final KeywordSearch.Query query;
        private final SearchProfile profile;
        private final long created;
        private final CompletableFuture<ProgressiveResult> result = new CompletableFuture<>();
        /** Erst gesetzt, wenn die Suche einen Platz hat. */
        private volatile ProgressiveSearch search;
        private volatile long startedAt;
        private volatile Long finishedAt;
        private volatile List<RankedHit> hits = List.of();
        private volatile List<Variant> variants = List.of();

        private Tracked(AccessContext caller, KeywordSearch.Query query, SearchProfile profile, long created) {
            this.owner = caller.jobOwner();
            this.caller = caller;
            this.query = query;
            this.profile = profile;
            this.created = created;
        }

        /** Die Kennung im Register, auch für eine wartende Suche. */
        public String id() {
            return id;
        }

        public SearchProfile profile() {
            return profile;
        }

        /** Das fertige Ergebnis, auch einer abgebrochenen wartenden Suche; scheitert nie. */
        public CompletableFuture<ProgressiveResult> result() {
            return result;
        }

        private void finish(ProgressiveResult value) {
            finishedAt = nanos.getAsLong();
            result.complete(value);
        }

        /** Nach dem Abschluss das fertige Ergebnis, sonst der letzte gemeldete Stand. */
        public Snapshot snapshot() {
            ProgressiveResult done = result.getNow(null);
            if (done != null) {
                return new Snapshot(id, profile, false, true, done.reason(), done.hits(), done.variants(),
                        done.loads(), done.elapsed());
            }
            if (search == null) {
                return new Snapshot(id, profile, true, false, null, List.of(), List.of(), List.of(),
                        Duration.ofNanos(nanos.getAsLong() - created));
            }
            return new Snapshot(id, profile, false, false, null, hits, variants, List.of(),
                    Duration.ofNanos(nanos.getAsLong() - startedAt));
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
