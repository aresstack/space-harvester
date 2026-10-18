package com.aresstack.spaceharvester.application.query.progressive;

import java.time.Duration;
import java.util.Objects;

/**
 * Einstellungen einer progressiven Suche (ADR 0017), nicht fest verdrahtet.
 *
 * @param mode             {@link Mode#QUALITY} (Standard) endet am Qualitätsziel, {@link Mode#ALL_VARIANTS}
 *                         arbeitet alle Varianten ab, etwa für einen Recherche-Agenten
 * @param maxVariants      höchstens so viele Varianten neben der ursprünglichen Anfrage
 * @param minRelevantHits  so viele Treffer über {@code minScore} erreichen das Qualitätsziel
 * @param minScore         Schwelle der Bewertung gegen die ursprüngliche Anfrage; modellabhängig
 * @param timeBudget       so lange darf die Suche insgesamt laufen
 * @param searchBudget     höchstens so viele Suchanfragen, die ursprüngliche eingeschlossen
 * @param limitPerSearch   Treffer je Suchanfrage (1 bis 100)
 * @param execution        Wellen, Parallelität, Fristen, Wiederholung
 * @param expansion        {@link Expansion#AI} (Standard) lässt das Chat-Modell des KI-Dienstes Varianten
 *                         formulieren, mit den Regeln als Rückfall; {@link Expansion#RULES} nutzt nur die Regeln
 */
public record SearchProfile(Mode mode, int maxVariants, int minRelevantHits, double minScore, Duration timeBudget,
                            int searchBudget, int limitPerSearch, SearchExecutionPolicy execution,
                            Expansion expansion) {

    public enum Mode {
        QUALITY,
        ALL_VARIANTS
    }

    /** Woher die Varianten kommen. Ohne Chat-Modell wirkt {@link #AI} wie {@link #RULES}. */
    public enum Expansion {
        AI,
        RULES
    }

    public static final int DEFAULT_MAX_VARIANTS = 8;
    public static final int DEFAULT_MIN_RELEVANT_HITS = 5;
    public static final double DEFAULT_MIN_SCORE = 0.5;
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofSeconds(30);
    public static final int DEFAULT_SEARCH_BUDGET = 12;
    public static final int DEFAULT_LIMIT_PER_SEARCH = 20;

    public SearchProfile {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(expansion, "expansion");
        Objects.requireNonNull(timeBudget, "timeBudget");
        if (maxVariants < 0 || maxVariants > 32) {
            throw new IllegalArgumentException("maxVariants muss zwischen 0 und 32 liegen");
        }
        if (minRelevantHits < 1) {
            throw new IllegalArgumentException("minRelevantHits muss mindestens 1 sein");
        }
        if (Double.isNaN(minScore) || Double.isInfinite(minScore)) {
            throw new IllegalArgumentException("minScore muss endlich sein");
        }
        if (timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("timeBudget muss positiv sein");
        }
        if (searchBudget < 1) {
            throw new IllegalArgumentException("searchBudget muss mindestens 1 sein");
        }
        if (limitPerSearch < 1 || limitPerSearch > 100) {
            throw new IllegalArgumentException("limitPerSearch muss zwischen 1 und 100 liegen");
        }
    }

    /** Qualitätsgesteuert mit den Standardwerten. */
    public static SearchProfile defaults() {
        return new SearchProfile(Mode.QUALITY, DEFAULT_MAX_VARIANTS, DEFAULT_MIN_RELEVANT_HITS, DEFAULT_MIN_SCORE,
                DEFAULT_TIME_BUDGET, DEFAULT_SEARCH_BUDGET, DEFAULT_LIMIT_PER_SEARCH,
                SearchExecutionPolicy.defaults(), Expansion.AI);
    }

    public SearchProfile withMode(Mode next) {
        return new SearchProfile(next, maxVariants, minRelevantHits, minScore, timeBudget, searchBudget,
                limitPerSearch, execution, expansion);
    }

    public SearchProfile withExecution(SearchExecutionPolicy next) {
        return new SearchProfile(mode, maxVariants, minRelevantHits, minScore, timeBudget, searchBudget,
                limitPerSearch, next, expansion);
    }

    public SearchProfile withBudget(Duration time, int searches) {
        return new SearchProfile(mode, maxVariants, minRelevantHits, minScore, time, searches, limitPerSearch,
                execution, expansion);
    }

    public SearchProfile withQuality(int hits, double score) {
        return new SearchProfile(mode, maxVariants, hits, score, timeBudget, searchBudget, limitPerSearch,
                execution, expansion);
    }

    public SearchProfile withExpansion(Expansion next) {
        return new SearchProfile(mode, maxVariants, minRelevantHits, minScore, timeBudget, searchBudget,
                limitPerSearch, execution, next);
    }

    /** Die Qualitätsregel dieses Profils. */
    SearchQualityPolicy quality() {
        return mode == Mode.ALL_VARIANTS ? SearchQualityPolicy.never()
                : SearchQualityPolicy.minimumRelevantHits(minRelevantHits, minScore);
    }
}
