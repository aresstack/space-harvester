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
    private final QueryExpansionStrategy expansion;
    private final HitScorer scorer;
    private final ExecutorService executor;

    public SearchOrchestrator(VariantSearch search, QueryExpansionStrategy expansion, HitScorer scorer) {
        this.search = Objects.requireNonNull(search, "search");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
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

    /** Mit eigener Qualitätsregel statt der des Profils. */
    public ProgressiveSearch searchAsync(AccessContext caller, KeywordSearch.Query query, SearchProfile profile,
                                         SearchQualityPolicy quality, ProgressiveSearchListener listener) {
        Run run = new Run(caller, Objects.requireNonNull(query, "query"), Objects.requireNonNull(profile, "profile"),
                Objects.requireNonNull(quality, "quality"), listener == null ? ProgressiveSearchListener.NONE
                : listener);
        executor.execute(run::coordinate);
        return run;
    }

    /** Nimmt keine Suchen mehr an; laufende enden ohne Unterbrechung. */
    @Override
    public void close() {
        executor.shutdown();
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
        private final Map<Integer, CompletableFuture<KeywordSearch.Results>> late = new LinkedHashMap<>();
        private final List<CompletableFuture<KeywordSearch.Results>> running = new ArrayList<>();
        private final List<String> loads = new ArrayList<>();
        private long sequence;
        private int searches;
        private int successes;
        private int newInWave;
        private boolean budgetHit;

        Run(AccessContext caller, KeywordSearch.Query query, SearchProfile profile, SearchQualityPolicy quality,
            ProgressiveSearchListener listener) {
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
                CompletableFuture<List<String>> expanded = CompletableFuture.supplyAsync(
                        () -> expansion.expand(query.text(), profile.maxVariants()), executor);
                variants.add(new QueryVariant(query.text(), 0));
                states.add(Status.PENDING);
                newHits.add(0);
                CompletableFuture<KeywordSearch.Results> original = start(0);
                publishVariants();
                await(0, original, Math.min(deadline, started + profile.execution().variantTimeout().toNanos()));
                addVariants(awaitExpansion(expanded));
                publishVariants();
                int next = 1;
                while (true) {
                    mergeLate();
                    if (cancelled()) {
                        finish(Completion.CANCELLED);
                        return;
                    }
                    if (successes > 0 && quality.satisfied(state())) {
                        finish(Completion.QUALITY_REACHED);
                        return;
                    }
                    boolean remaining = next < variants.size();
                    if (remaining && (budgetHit || System.nanoTime() >= deadline
                            || searches >= profile.searchBudget())) {
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
            List<CompletableFuture<KeywordSearch.Results>> wave = new ArrayList<>();
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
        private CompletableFuture<KeywordSearch.Results> start(int index) {
            states.set(index, Status.RUNNING);
            searches++;
            KeywordSearch.Query variant = index == 0 ? query : new KeywordSearch.Query(variants.get(index).text(),
                    query.sourceId(), query.sourceIds(), query.spaceKeys(), query.excludedSpaceKeys());
            CompletableFuture<KeywordSearch.Results> future = CompletableFuture.supplyAsync(() -> {
                slots.acquireUninterruptibly();
                try {
                    return attempt(variant);
                } finally {
                    slots.release();
                }
            }, executor);
            running.add(future);
            return future;
        }

        private KeywordSearch.Results attempt(KeywordSearch.Query variant) {
            int retries = profile.execution().retries();
            for (int attempt = 0; ; attempt++) {
                if (cancelled()) {
                    throw new IllegalStateException("abgebrochen");
                }
                try {
                    return search.search(caller, variant, profile.limitPerSearch());
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
        private void await(int index, CompletableFuture<KeywordSearch.Results> future, long until) {
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

        /** Verspätete Antworten gescheiterter Suchanfragen werden noch nach Rang einsortiert. */
        private void mergeLate() {
            var iterator = late.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                CompletableFuture<KeywordSearch.Results> future = entry.getValue();
                if (!future.isDone()) {
                    continue;
                }
                iterator.remove();
                if (!future.isCompletedExceptionally()) {
                    release(entry.getKey(), future.join());
                }
            }
        }

        private void release(int index, KeywordSearch.Results results) {
            successes++;
            states.set(index, Status.DONE);
            if (results.progress() != null && !results.progress().done()) {
                loads.add(results.progress().id());
            }
            List<Entry> unscored = new ArrayList<>();
            int added = 0;
            for (KeywordSearch.Hit hit : results.hits()) {
                added += merge(index, hit, true, unscored);
            }
            for (KeywordSearch.Hit hit : results.found()) {
                added += merge(index, hit, false, unscored);
            }
            newHits.set(index, newHits.get(index) + added);
            newInWave += added;
            score(unscored);
            publishVariants();
            emit(() -> listener.ranked(ranked()));
        }

        private int merge(int index, KeywordSearch.Hit hit, boolean confirmed, List<Entry> unscored) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            Entry known = entries.get(key);
            if (known == null) {
                Entry entry = new Entry(sequence++, variants.get(index).priority(), hit, confirmed);
                entries.put(key, entry);
                unscored.add(entry);
                return 1;
            }
            if (confirmed && !known.confirmed) {
                // Bestätigt aus dem Cache: Kopfdaten und Auszug aus dem Inhalt, Platz nach der Bewertung.
                known.hit = hit;
                known.confirmed = true;
                if (Double.isNaN(known.score)) {
                    unscored.add(known);
                }
            }
            return 0;
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
                running.forEach(future -> future.cancel(false));
                for (String load : loads) {
                    try {
                        search.cancelLoading(load);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.INFO, "Laden nicht abgebrochen ({0})", e.getClass().getName());
                    }
                }
            }
            ProgressiveResult done = new ProgressiveResult(ranked(), variants(), reason, loads,
                    Duration.ofNanos(System.nanoTime() - started));
            emit(() -> listener.variants(done.variants()));
            emit(() -> listener.completed(done));
            result.complete(done);
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
