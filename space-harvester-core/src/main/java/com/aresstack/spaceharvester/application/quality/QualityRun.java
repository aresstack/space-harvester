package com.aresstack.spaceharvester.application.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Misst einen Testbestand: jede Frage nacheinander (vergleichbare Laufzeiten) über die {@link QualitySearch}. Vor jeder
 * Frage wird festgehalten, ob ihre erwarteten Seiten lokal liegen, denn die Suche lädt Funde in den Cache. Ein
 * Abbruch lässt die gemessenen Fragen stehen; ein Fortsetzen übergibt sie und misst nur die fehlenden. Kein Log mit
 * Inhalten: Fehler einer Frage werden nur mit ihrer Klasse festgehalten.
 */
public final class QualityRun {

    /** Wie viele Seiten die Ergebnisdatei je Frage nennt. */
    static final int LISTED = 10;

    /**
     * @param contextSize   so viele Treffer gibt der Chat ins Modell (in der App {@code ChatKnowledge.MAX_SOURCES})
     * @param relevantScore ab dieser Bewertung gilt ein Treffer als brauchbar (in der App {@code minScore} des
     *                      Suchprofils, dieselbe Schwelle wie das Qualitätsziel der progressiven Suche)
     */
    public record Settings(int contextSize, double relevantScore) {

        public Settings {
            if (contextSize < 1 || contextSize > 100) {
                throw new IllegalArgumentException("contextSize muss zwischen 1 und 100 liegen");
            }
            if (Double.isNaN(relevantScore) || Double.isInfinite(relevantScore)) {
                throw new IllegalArgumentException("relevantScore muss endlich sein");
            }
        }
    }

    /** Fortschritt nach jeder gemessenen Frage. */
    @FunctionalInterface
    public interface Listener {

        Listener NONE = (done, total, result) -> { };

        void measured(int done, int total, CaseResult result);
    }

    /**
     * Ergebnis eines Laufs.
     *
     * @param results   gemessene Fragen in der Reihenfolge des Testbestands
     * @param total     Fragen im Testbestand
     * @param cancelled abgebrochen, bevor alle gemessen waren
     */
    public record Result(List<CaseResult> results, int total, boolean cancelled) {

        public Result {
            results = List.copyOf(results);
        }
    }

    private final QualitySearch search;
    private final LocalHoldings holdings;
    private final Settings settings;
    private final LongSupplier nanoTime;

    public QualityRun(QualitySearch search, LocalHoldings holdings, Settings settings) {
        this(search, holdings, settings, System::nanoTime);
    }

    QualityRun(QualitySearch search, LocalHoldings holdings, Settings settings, LongSupplier nanoTime) {
        this.search = Objects.requireNonNull(search, "search");
        this.holdings = Objects.requireNonNull(holdings, "holdings");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Settings settings() {
        return settings;
    }

    /**
     * @param cases     Fragen des Testbestands
     * @param done      schon gemessene Fragen nach ID (Fortsetzen), werden übernommen statt neu gemessen
     * @param cancelled vor jeder Frage gefragt; {@code true} beendet den Lauf
     */
    public Result run(List<QualityCase> cases, Map<String, CaseResult> done, BooleanSupplier cancelled,
                      Listener listener) {
        List<CaseResult> results = new ArrayList<>();
        int measured = 0;
        for (QualityCase question : cases) {
            CaseResult previous = done.get(question.id());
            if (previous != null) {
                results.add(previous);
                measured++;
                continue;
            }
            if (cancelled.getAsBoolean()) {
                return new Result(results, cases.size(), true);
            }
            CaseResult result = measure(question);
            results.add(result);
            measured++;
            listener.measured(measured, cases.size(), result);
        }
        return new Result(results, cases.size(), false);
    }

    /** Misst eine Frage. */
    public CaseResult measure(QualityCase question) {
        List<Boolean> stored = new ArrayList<>();
        for (PageRef page : question.expected()) {
            stored.add(holdings.stored(page));
        }
        long start = nanoTime.getAsLong();
        AtomicLong first = new AtomicLong(-1);
        QualitySearch.Answer answer;
        String failure = null;
        try {
            answer = search.search(question, interim -> {
                if (first.get() < 0 && containsExpected(interim, question)) {
                    first.compareAndSet(-1, nanoTime.getAsLong() - start);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            answer = new QualitySearch.Answer(List.of(), "", 0, 0);
            failure = interrupted.getClass().getSimpleName();
        } catch (Exception broken) {
            answer = new QualitySearch.Answer(List.of(), "", 0, 0);
            failure = broken.getClass().getSimpleName();
        }
        long duration = nanoTime.getAsLong() - start;
        List<QualitySearch.RankedPage> hits = distinct(answer.hits());
        List<Integer> ranks = new ArrayList<>();
        for (PageRef page : question.expected()) {
            ranks.add(rank(hits, page));
        }
        if (first.get() < 0 && ranks.stream().anyMatch(rank -> rank > 0)) {
            first.set(duration);
        }
        double best = Double.NaN;
        int useful = 0;
        List<PageRef> found = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            QualitySearch.RankedPage hit = hits.get(i);
            if (Double.isNaN(best) || hit.score() > best) {
                best = hit.score();
            }
            if (i < settings.contextSize() && hit.score() >= settings.relevantScore()) {
                useful++;
            }
            if (i < LISTED) {
                found.add(hit.page());
                titles.add(hit.title());
            }
        }
        return new CaseResult(question.id(), question.kinds(), question.expected(), ranks, stored,
                millis(duration), first.get() < 0 ? -1 : millis(first.get()), answer.completion(),
                answer.variants(), answer.unavailable(), hits.size(), best, useful, failure, found,
                question.question(), titles);
    }

    /** Jede Seite einmal, an ihrem ersten Platz (wie der Chat die Treffer übernimmt). */
    static List<QualitySearch.RankedPage> distinct(List<QualitySearch.RankedPage> hits) {
        List<QualitySearch.RankedPage> pages = new ArrayList<>();
        java.util.Set<PageRef> seen = new java.util.HashSet<>();
        for (QualitySearch.RankedPage hit : hits) {
            if (seen.add(hit.page())) {
                pages.add(hit);
            }
        }
        return pages;
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static boolean containsExpected(List<QualitySearch.RankedPage> hits, QualityCase question) {
        for (QualitySearch.RankedPage hit : hits) {
            if (question.expected().contains(hit.page())) {
                return true;
            }
        }
        return false;
    }

    /** Rang ab 1, {@code 0} = nicht in der Liste. */
    private static int rank(List<QualitySearch.RankedPage> hits, PageRef page) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).page().equals(page)) {
                return i + 1;
            }
        }
        return 0;
    }
}
