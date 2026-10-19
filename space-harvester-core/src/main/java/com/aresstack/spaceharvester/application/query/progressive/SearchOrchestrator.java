package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Completion;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.RankedHit;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Status;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult.Variant;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progressive Suche im Core (ADR 0017): eine Suchmaschine für Oberfläche und Agenten. Die ursprüngliche Anfrage sucht
 * sofort, gleichzeitig erzeugt die {@link QueryExpansionStrategy} Varianten. Die laufen in Wellen
 * ({@link SearchExecutionPolicy}); ihre Ergebnisse werden in der Reihenfolge der Prioritäten freigegeben, jeder Fund
 * gegen die ursprüngliche Anfrage bewertet ({@link HitScorer}) und nach Quelle und Seiten-ID zusammengeführt. Nach
 * jeder Welle entscheidet die {@link SearchQualityPolicy}, ob weitergesucht wird; Zeit- und Abfragebudget begrenzen.
 *
 * <p>Mit {@link SearchProfile.Ranking#SEMANTIC} läuft jede Suchanfrage über die semantische Suche (ADR 0013): Die
 * Variante findet und lädt nur, Treffer kommen aus der Vektorsuche mit der ursprünglichen Anfrage und tragen ihre
 * Kosinusähnlichkeit zu ihr als Bewertung ({@link VariantSearch.Outcome#scores()}); der {@link HitScorer} bewertet
 * dann nicht, damit keine Werte verschiedener Modelle gemischt werden.
 *
 * <p>Ein Agent wartet auf {@link ProgressiveSearch#result()}, eine Oberfläche bekommt unterwegs
 * {@link ProgressiveSearchListener}-Ereignisse; beides ist derselbe Vorgang. Rechte prüft die {@link VariantSearch}:
 * Was der Aufrufer nicht sehen darf, erscheint weder als Treffer noch in einer Zahl. Suchtexte und Seiten-IDs kommen
 * nie ins Log.
 *
 * <p>Laufende Suchanfragen werden nie unterbrochen, nur nicht mehr abgewartet: Ein Interrupt mitten im Lesen eines
 * Lucene-Index schlösse dessen Dateikanäle.
 */
public final class SearchOrchestrator implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SearchOrchestrator.class.getName());

    private final VariantSearch search;
    private final VariantSearch semantic;
    private final QueryExpansionStrategy expansion;
    private final QueryExpansionStrategy rules;
    private final HitScorer scorer;
    private final ExecutorService executor;
    private final Set<Run> active = ConcurrentHashMap.newKeySet();

    /** Eine Strategie für beide Einstellungen von {@link SearchProfile#expansion()}. */
    public SearchOrchestrator(VariantSearch search, QueryExpansionStrategy expansion, HitScorer scorer) {
        this(search, expansion, expansion, scorer);
    }

    /**
     * @param expansion Varianten für {@link SearchProfile.Expansion#AI}, etwa {@link AiQueryExpansion}
     * @param rules     Varianten für {@link SearchProfile.Expansion#RULES}, etwa {@link RuleBasedExpansion}
     */
    public SearchOrchestrator(VariantSearch search, QueryExpansionStrategy expansion, QueryExpansionStrategy rules,
                              HitScorer scorer) {
        this(search, null, expansion, rules, scorer);
    }

    /**
     * @param search   Suchanfragen für {@link SearchProfile.Ranking#KEYWORD}, die Schlagwortsuche
     * @param semantic Suchanfragen für {@link SearchProfile.Ranking#SEMANTIC} oder {@code null}, dann gibt es keine
     *                 semantische progressive Suche
     */
    public SearchOrchestrator(VariantSearch search, VariantSearch semantic, QueryExpansionStrategy expansion,
                              QueryExpansionStrategy rules, HitScorer scorer) {
        this.search = Objects.requireNonNull(search, "search");
        this.semantic = semantic;
        this.expansion = Objects.requireNonNull(expansion, "expansion");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("progressive-search-", 0)
                .factory());
    }

    public ProgressiveSearch searchAsync(AccessContext caller, KeywordSearch.Query query, SearchProfile profile) {
        return searchAsync(caller, query, profile, ProgressiveSearchListener.NONE);
    }

    public ProgressiveSearch searchAsync(AccessContext caller, KeywordSearch.Query query, SearchProfile profile,
                                         ProgressiveSearchListener listener) {
        return searchAsync(caller, query, profile, profile.quality(), listener);
    }

    /**
     * Mit eigener Qualitätsregel statt der des Profils.
     *
     * @throws IllegalArgumentException mit {@link SearchProfile.Ranking#SEMANTIC}, wenn keine semantische Suche
     *                                  verdrahtet ist
     * @throws RuntimeException         aus {@link VariantSearch#requireAvailable}, nach den Rechten am Umfang, wenn die gewählte Suche gerade
     *                                  nicht möglich ist, etwa ohne Embedding-Modell; nie ein stiller Rückfall
     */
    public ProgressiveSearch searchAsync(AccessContext caller, KeywordSearch.Query query, SearchProfile profile,
                                         SearchQualityPolicy quality, ProgressiveSearchListener listener) {
        Objects.requireNonNull(profile, "profile");
        VariantSearch chosen = search;
        if (profile.ranking() == SearchProfile.Ranking.SEMANTIC) {
            if (semantic == null) {
                throw new IllegalArgumentException("Keine semantische Suche eingerichtet");
            }
            chosen = semantic;
        }
        chosen.requireAvailable(caller, Objects.requireNonNull(query, "query"));
        Run run = new Run(chosen, caller, query, profile,
                Objects.requireNonNull(quality, "quality"), listener == null ? ProgressiveSearchListener.NONE
                : listener);
        active.add(run);
        run.result.whenComplete((done, failure) -> active.remove(run));
        executor.execute(run::coordinate);
        return run;
    }

    /** So lange wartet {@link #close()} auf laufende Suchanfragen. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Bricht laufende Suchen ab und wartet bis {@link #CLOSE_TIMEOUT}, bis ihre Suchanfragen zurückgekehrt sind, damit
     * danach Cache und Indizes schließen können. Unterbrochen wird nie.
     */
    @Override
    public void close() {
        active.forEach(Run::cancel);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Suchanfragen laufen nach {0} noch", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ein Treffer der zusammengeführten Liste. */
    private static final class Entry {

        final long sequence;
        final int foundBy;
        KeywordSearch.Hit hit;
        double score = Double.NaN;
        boolean confirmed;

        Entry(long sequence, int foundBy, KeywordSearch.Hit hit, boolean confirmed) {
            this.sequence = sequence;
            this.foundBy = foundBy;
            this.hit = hit;
            this.confirmed = confirmed;
        }

        RankedHit ranked() {
            return new RankedHit(hit, score, foundBy, confirmed);
        }
    }

    private final class Run implements ProgressiveSearch {

        private final String id = UUID.randomUUID().toString();
        private final VariantSearch search;
        /** Semantisch bewertet die Suche selbst; Werte des Cross-Encoders kämen auf eine andere Skala. */
        private final HitScorer scorer;
        private final AccessContext caller;
        private final KeywordSearch.Query query;
        private final SearchProfile profile;
        private final SearchQualityPolicy quality;
        private final ProgressiveSearchListener listener;
        private final CompletableFuture<ProgressiveResult> result = new CompletableFuture<>();
        private final CompletableFuture<Void> cancelSignal = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Semaphore slots;
        private final long started = System.nanoTime();
        private final long deadline;

        // Nur der koordinierende Thread ändert diese Felder; die Listen gehen als Kopien hinaus.
        private final Map<PageKey, Entry> entries = new LinkedHashMap<>();
        private final List<QueryVariant> variants = new ArrayList<>();
        private final List<Status> states = new ArrayList<>();
        private final List<Integer> newHits = new ArrayList<>();
        private final Map<Integer, CompletableFuture<VariantSearch.Outcome>> late = new LinkedHashMap<>();
        private final List<String> loads = new ArrayList<>();
        private long sequence;
        private int searches;
        private int successes;
        private int newInWave;
        private boolean budgetHit;

        Run(VariantSearch search, AccessContext caller, KeywordSearch.Query query, SearchProfile profile,
            SearchQualityPolicy quality, ProgressiveSearchListener listener) {
            this.search = search;
            this.scorer = profile.ranking() == SearchProfile.Ranking.SEMANTIC ? HitScorer.none()
                    : SearchOrchestrator.this.scorer;
            this.caller = caller;
            this.query = query;
            this.profile = profile;
            this.quality = quality;
            this.listener = listener;
            this.slots = new Semaphore(profile.execution().parallelism());
            this.deadline = started + profile.timeBudget().toNanos();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CompletableFuture<ProgressiveResult> result() {
            return result;
        }

        @Override
        public boolean cancel() {
            boolean open = !result.isDone();
            cancelSignal.complete(null);
            return open;
        }

        private boolean cancelled() {
            return cancelSignal.isDone();
        }

        void coordinate() {
            try {
                QueryExpansionStrategy strategy = profile.expansion() == SearchProfile.Expansion.RULES ? rules
                        : expansion;
                CompletableFuture<List<String>> expanded = CompletableFuture.supplyAsync(
                        () -> strategy.expand(query.text(), profile.maxVariants()), executor);
                variants.add(new QueryVariant(query.text(), 0));
                states.add(Status.PENDING);
                newHits.add(0);
                CompletableFuture<VariantSearch.Outcome> original = start(0);
                publishVariants();
                await(0, original, Math.min(deadline, started + profile.execution().variantTimeout().toNanos()));
                // Genügt schon die ursprüngliche Anfrage, wartet die Suche nicht auf das Modell.
                if (!cancelled() && successes > 0 && !budgetHit && System.nanoTime() < deadline
                        && quality.satisfied(state())) {
                    if (expanded.isDone()) {
                        addVariants(awaitExpansion(expanded));
                    } else {
                        expanded.cancel(false);
                    }
                    finish(Completion.QUALITY_REACHED);
                    return;
                }
                addVariants(awaitExpansion(expanded));
                publishVariants();
                int next = 1;
                while (true) {
                    mergeLate();
                    if (cancelled()) {
                        finish(Completion.CANCELLED);
                        return;
                    }
                    boolean remaining = next < variants.size();
                    if (!remaining && !late.isEmpty() && System.nanoTime() < deadline) {
                        // Bis zur Gesamtfrist auf verspätete Antworten warten, statt sie zu verwerfen.
                        awaitLate();
                        continue;
                    }
                    // Die Frist zuerst: Eine Suche, die an der Zeit endet, behauptet nie das Qualitätsziel.
                    if ((remaining || !late.isEmpty()) && (budgetHit || System.nanoTime() >= deadline)) {
                        finish(Completion.BUDGET_EXHAUSTED);
                        return;
                    }
                    if (successes > 0 && quality.satisfied(state())) {
                        finish(Completion.QUALITY_REACHED);
                        return;
                    }
                    if (remaining && searches >= profile.searchBudget()) {
                        finish(Completion.BUDGET_EXHAUSTED);
                        return;
                    }
                    if (!remaining) {
                        finish(successes == 0 ? Completion.FAILED
                                : budgetHit ? Completion.BUDGET_EXHAUSTED : Completion.ALL_VARIANTS_DONE);
                        return;
                    }
                    int wave = Math.min(Math.min(profile.execution().waveSize(), variants.size() - next),
                            profile.searchBudget() - searches);
                    runWave(next, wave);
                    next += wave;
                }
            } catch (RuntimeException | Error e) {
                LOG.log(System.Logger.Level.WARNING, "Progressive Suche gescheitert ({0})", e.getClass().getName());
                finish(Completion.FAILED);
            }
        }

        private void runWave(int first, int count) {
            newInWave = 0;
            long waveStart = System.nanoTime();
            List<CompletableFuture<VariantSearch.Outcome>> wave = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                wave.add(start(first + i));
            }
            publishVariants();
            int parallelism = profile.execution().parallelism();
            long timeout = profile.execution().variantTimeout().toNanos();
            for (int i = 0; i < count; i++) {
                if (cancelled()) {
                    return;
                }
                // Wer auf einen freien Platz wartet, beginnt später und bekommt dafür mehr Zeit.
                long own = waveStart + timeout * (i / parallelism + 1);
                await(first + i, wave.get(i), Math.min(own, deadline));
            }
        }

        /** Startet die Suchanfrage {@code index}; höchstens {@code parallelism} zugleich. */
        private CompletableFuture<VariantSearch.Outcome> start(int index) {
            states.set(index, Status.RUNNING);
            searches++;
            KeywordSearch.Query variant = index == 0 ? query : new KeywordSearch.Query(variants.get(index).text(),
                    query.sourceId(), query.sourceIds(), query.spaceKeys(), query.excludedSpaceKeys());
            CompletableFuture<VariantSearch.Outcome> future = CompletableFuture.supplyAsync(() -> {
                slots.acquireUninterruptibly();
                try {
                    return attempt(variant);
                } finally {
                    slots.release();
                }
            }, executor);
            // Kehrt eine Suchanfrage erst nach dem Abbruch zurück, kennt finish ihr Laden nicht: hier abbrechen.
            future.thenAccept(outcome -> {
                KeywordSearch.Progress progress = outcome.results().progress();
                if (cancelled() && progress != null && !progress.done()) {
                    cancelLoading(progress.id());
                }
            });
            return future;
        }

        private VariantSearch.Outcome attempt(KeywordSearch.Query variant) {
            int retries = profile.execution().retries();
            for (int attempt = 0; ; attempt++) {
                if (cancelled()) {
                    throw new IllegalStateException("abgebrochen");
                }
                try {
                    return search.run(caller, variant, query.text(), profile.limitPerSearch());
                } catch (InvalidQueryException | AccessDeniedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    if (attempt >= retries) {
                        throw e;
                    }
                    LOG.log(System.Logger.Level.INFO, "Suchanfrage gescheitert ({0}); neuer Versuch",
                            e.getClass().getName());
                }
            }
        }

        /** Wartet bis zur Frist auf die Suchanfrage {@code index} und gibt sie frei; sonst gilt sie als gescheitert. */
        private void await(int index, CompletableFuture<VariantSearch.Outcome> future, long until) {
            try {
                CompletableFuture.anyOf(future, cancelSignal).get(Math.max(0, until - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
                if (future.isDone()) {
                    // Verspätete Antworten höherer Priorität zuerst.
                    mergeLate();
                    release(index, future.join());
                } else {
                    // Abgebrochen; was noch kommt, bleibt draußen.
                    states.set(index, Status.SKIPPED);
                }
            } catch (TimeoutException e) {
                states.set(index, Status.FAILED);
                late.put(index, future);
                if (until >= deadline) {
                    budgetHit = true;
                }
                publishVariants();
            } catch (ExecutionException | CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LOG.log(System.Logger.Level.INFO, "Suchanfrage gescheitert ({0})", cause.getClass().getName());
                states.set(index, Status.FAILED);
                publishVariants();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSignal.complete(null);
            }
        }

        /** Wartet bis zur Gesamtfrist oder zum Abbruch auf die verspäteten Suchanfragen. */
        private void awaitLate() {
            try {
                CompletableFuture.allOf(late.values().toArray(CompletableFuture[]::new))
                        .applyToEither(cancelSignal, ignored -> null)
                        .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                budgetHit = true;
            } catch (ExecutionException | CompletionException e) {
                // Gescheiterte bleiben gescheitert; mergeLate nimmt die übrigen.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSignal.complete(null);
            }
            mergeLate();
        }

        /** Verspätete Antworten gescheiterter Suchanfragen werden noch nach Rang einsortiert. */
        private void mergeLate() {
            var iterator = late.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                CompletableFuture<VariantSearch.Outcome> future = entry.getValue();
                if (!future.isDone()) {
                    continue;
                }
                iterator.remove();
                if (!future.isCompletedExceptionally()) {
                    release(entry.getKey(), future.join());
                }
            }
        }

        private void release(int index, VariantSearch.Outcome outcome) {
            KeywordSearch.Results results = outcome.results();
            successes++;
            states.set(index, Status.DONE);
            if (results.progress() != null && !results.progress().done()) {
                loads.add(results.progress().id());
            }
            List<Entry> unscored = new ArrayList<>();
            int added = 0;
            for (KeywordSearch.Hit hit : results.hits()) {
                added += merge(index, hit, true, unscored, outcome.scores());
            }
            for (KeywordSearch.Hit hit : results.found()) {
                added += merge(index, hit, false, unscored, outcome.scores());
            }
            newHits.set(index, newHits.get(index) + added);
            newInWave += added;
            score(unscored);
            publishVariants();
            emit(() -> listener.ranked(ranked()));
        }

        /** @param scores Bewertungen der Suche selbst; diese Treffer bewertet der {@link HitScorer} nicht */
        private int merge(int index, KeywordSearch.Hit hit, boolean confirmed, List<Entry> unscored,
                          Map<PageKey, Double> scores) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            Double given = scores.get(key);
            Entry known = entries.get(key);
            if (known == null) {
                Entry entry = new Entry(sequence++, variants.get(index).priority(), hit, confirmed);
                entries.put(key, entry);
                if (usable(given)) {
                    entry.score = given;
                } else {
                    unscored.add(entry);
                }
                return 1;
            }
            if (usable(given)) {
                // Dieselbe ursprüngliche Anfrage; ein neuer Wert kommt etwa von einem inzwischen neu berechneten
                // Embedding.
                known.score = given;
            }
            if (confirmed && !known.confirmed) {
                // Bestätigt aus dem Cache: Kopfdaten und Auszug aus dem Inhalt, Platz nach der Bewertung.
                boolean changed = !Objects.equals(known.hit.title(), hit.title())
                        || !Objects.equals(known.hit.snippet(), hit.snippet());
                known.hit = hit;
                known.confirmed = true;
                // Neue Eingabe der Bewertung, also neu bewerten.
                if (!usable(given) && (changed || Double.isNaN(known.score))) {
                    unscored.add(known);
                }
            }
            return 0;
        }

        private static boolean usable(Double score) {
            return score != null && !score.isNaN() && !score.isInfinite();
        }

        /** Immer gegen die ursprüngliche Anfrage, nie gegen die Variante, die den Treffer fand. */
        private void score(List<Entry> unscored) {
            if (unscored.isEmpty()) {
                return;
            }
            double[] scores;
            try {
                scores = scorer.score(query.text(), unscored.stream().map(entry -> entry.hit).toList());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Bewertung nicht möglich ({0})", e.getClass().getName());
                return;
            }
            if (scores == null || scores.length != unscored.size()) {
                return;
            }
            for (int i = 0; i < scores.length; i++) {
                if (!Double.isNaN(scores[i]) && !Double.isInfinite(scores[i])) {
                    unscored.get(i).score = scores[i];
                }
            }
        }

        /** Bewertete zuerst, beste vorn; unbewertete danach in der Reihenfolge ihrer Freigabe. */
        private List<RankedHit> ranked() {
            List<Entry> all = new ArrayList<>(entries.values());
            all.sort(Comparator.comparing((Entry entry) -> Double.isNaN(entry.score))
                    .thenComparing(entry -> Double.isNaN(entry.score) ? 0 : -entry.score)
                    .thenComparingLong(entry -> entry.sequence));
            return all.stream().map(Entry::ranked).toList();
        }

        private SearchQualityPolicy.State state() {
            List<Double> scores = entries.values().stream().map(entry -> entry.score)
                    .filter(score -> !Double.isNaN(score)).sorted(Comparator.reverseOrder()).toList();
            return new SearchQualityPolicy.State(scores, entries.size(), newInWave, searches);
        }

        private List<String> awaitExpansion(CompletableFuture<List<String>> expanded) {
            long until = Math.min(deadline, System.nanoTime() + profile.execution().variantTimeout().toNanos());
            try {
                CompletableFuture.anyOf(expanded, cancelSignal).get(Math.max(0, until - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
                return expanded.isDone() ? expanded.join() : List.of();
            } catch (TimeoutException e) {
                LOG.log(System.Logger.Level.INFO, "Keine Varianten in der Frist");
                return List.of();
            } catch (ExecutionException | CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LOG.log(System.Logger.Level.INFO, "Varianten nicht erzeugt ({0})", cause.getClass().getName());
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSignal.complete(null);
                return List.of();
            }
        }

        /** Dubletten (auch der ursprünglichen Anfrage) und ungültige Texte fallen weg; Prioritäten nach Reihenfolge. */
        private void addVariants(List<String> expanded) {
            Set<String> seen = new HashSet<>();
            seen.add(RuleBasedExpansion.normalize(query.text()));
            for (String text : expanded == null ? List.<String>of() : expanded) {
                if (variants.size() > profile.maxVariants()) {
                    break;
                }
                if (text == null || text.isBlank() || text.strip().length() > KeywordSearch.MAX_TEXT_LENGTH
                        || !seen.add(RuleBasedExpansion.normalize(text))) {
                    continue;
                }
                variants.add(new QueryVariant(text, variants.size()));
                states.add(Status.PENDING);
                newHits.add(0);
            }
        }

        private void publishVariants() {
            List<Variant> snapshot = variants();
            emit(() -> listener.variants(snapshot));
        }

        private List<Variant> variants() {
            List<Variant> snapshot = new ArrayList<>(variants.size());
            for (int i = 0; i < variants.size(); i++) {
                snapshot.add(new Variant(variants.get(i), states.get(i), newHits.get(i)));
            }
            return List.copyOf(snapshot);
        }

        private void finish(Completion reason) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            mergeLate();
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i) == Status.PENDING || states.get(i) == Status.RUNNING) {
                    states.set(i, Status.SKIPPED);
                }
            }
            if (reason == Completion.CANCELLED) {
                loads.forEach(this::cancelLoading);
            }
            ProgressiveResult done = new ProgressiveResult(ranked(), variants(), reason, loads,
                    Duration.ofNanos(System.nanoTime() - started));
            emit(() -> listener.variants(done.variants()));
            emit(() -> listener.completed(done));
            result.complete(done);
        }

        private void cancelLoading(String load) {
            try {
                search.cancelLoading(load);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Laden nicht abgebrochen ({0})", e.getClass().getName());
            }
        }

        private void emit(Runnable event) {
            try {
                event.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Empfänger der Suchereignisse scheiterte ({0})",
                        e.getClass().getName());
            }
        }
    }
}
