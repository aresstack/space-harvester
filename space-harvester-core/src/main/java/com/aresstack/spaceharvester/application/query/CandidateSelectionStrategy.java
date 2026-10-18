package com.aresstack.spaceharvester.application.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Welche vorläufigen Funde eines Stapels die Schlagwortsuche jetzt in den Cache lädt (ADR 0016, Punkt 4), getrennt vom
 * Laden selbst ({@link KeywordSearch.Prefetch}). Die Strategie verwirft nie einen Fund: Alle Funde stehen sofort als
 * vorläufige Treffer in {@link KeywordSearch.Results#found()}; was sie nicht auswählt, lädt erst, wenn jemand die Seite
 * öffnet, ein späterer Stapel sie auswählt oder die Hintergrundfüllung sie aus dem Katalog holt.
 *
 * <p>Drei Strategien, Name in Konfiguration und REST in Klammern:
 *
 * <ul>
 *   <li>{@link SourceOrder} ({@code order}): alle Funde, die nicht schon aktuell im Cache liegen, in der Reihenfolge der
 *       Quellen, ohne Rücksicht auf die Bewertung nach Titel und Auszug.</li>
 *   <li>{@link TopN} ({@code top-n}): die besten {@code n} nach Titel und Auszug ({@link ExcerptPriority}), ohne
 *       Bewertung die ersten {@code n} der Quellen; was schon aktuell im Cache liegt, zählt nicht mit. Das ist das
 *       bisherige {@code searchLoads}.</li>
 *   <li>{@link Adaptive} ({@code adaptive}, Standard): die besten nach Titel und Auszug, so viele, wie der Vordergrund
 *       gleich schafft; siehe {@link Adaptive} für die Regel.</li>
 * </ul>
 *
 * <p>Jede Strategie hat eine Obergrenze ({@code space-harvester.search.loads} bzw.
 * {@code Harvester.Builder.searchLoads}); {@value KeywordSearch#MAX_LIMIT} heißt ohne Obergrenze.
 */
@FunctionalInterface
public interface CandidateSelectionStrategy {

    /**
     * Die Funde, die jetzt laden, in Ladereihenfolge; eine Teilmenge von {@link Candidates#found()} ohne Dopplungen.
     * Scheitert nie an einzelnen Funden.
     */
    List<KeywordSearch.Hit> select(Candidates candidates);

    /**
     * Was eine Strategie über einen Stapel weiß.
     *
     * @param found    die sichtbaren Funde in der Reihenfolge der Quellen (reihum je Quelle)
     * @param ranked   dieselben Funde nach Titel und Auszug bewertet, beste zuerst; ohne Bewertung wie {@code found}
     * @param byExcerpt ob {@code ranked} aus einer Bewertung stammt ({@link ExcerptPriority.Priority#ranked()})
     * @param current  ob die Version eines Fundes schon im Cache liegt; solche Funde müssen nicht laden
     * @param capacity Lage des Vordergrunds beim Auswählen
     */
    record Candidates(List<KeywordSearch.Hit> found, List<KeywordSearch.Hit> ranked, boolean byExcerpt,
                      Predicate<KeywordSearch.Hit> current, Capacity capacity) {

        public Candidates {
            found = List.copyOf(found);
            ranked = List.copyOf(ranked);
            Objects.requireNonNull(current, "current");
            capacity = capacity == null ? Capacity.DEFAULT : capacity;
        }

        /** Die Funde in {@code order}, die noch laden müssen; eine Prüfung, die scheitert, heißt „muss laden“. */
        public List<KeywordSearch.Hit> needing(List<KeywordSearch.Hit> order) {
            List<KeywordSearch.Hit> needed = new ArrayList<>();
            for (KeywordSearch.Hit hit : order) {
                boolean fresh;
                try {
                    fresh = current.test(hit);
                } catch (RuntimeException e) {
                    fresh = false;
                }
                if (!fresh) {
                    needed.add(hit);
                }
            }
            return needed;
        }
    }

    /**
     * Lage des Vordergrunds ({@code AcquisitionScheduler}).
     *
     * @param parallelism so viele Seiten lädt der Vordergrund höchstens zugleich (reservierte Kapazität)
     * @param backlog     so viele Seiten warten dort schon, etwa aus anderen Suchen
     */
    record Capacity(int parallelism, int backlog) {

        /** Wie der Standard des Beschaffungsplaners: vier zugleich, nichts wartet. */
        public static final Capacity DEFAULT = new Capacity(4, 0);

        public Capacity {
            parallelism = Math.max(1, parallelism);
            backlog = Math.max(0, backlog);
        }
    }

    /** Die eingebauten Strategien mit ihrem Namen in Konfiguration und REST. */
    enum Kind {
        ORDER("order"), TOP_N("top-n"), ADAPTIVE("adaptive");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /**
         * Liest {@link #value()} oder den Namen der Konstante, ohne Rücksicht auf Groß- und Kleinschreibung.
         *
         * @throws IllegalArgumentException für einen unbekannten Namen
         */
        public static Kind of(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Kind kind : values()) {
                if (kind.value.equals(normalized)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Auswahl der Beschaffung muss order, top-n oder adaptive sein");
        }
    }

    /**
     * Eine eingebaute Strategie.
     *
     * @param loads Obergrenze, 1 bis {@value KeywordSearch#MAX_LIMIT}; bei {@link Kind#TOP_N} zugleich {@code n}
     */
    static CandidateSelectionStrategy of(Kind kind, int loads) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case ORDER -> new SourceOrder(loads);
            case TOP_N -> new TopN(loads);
            case ADAPTIVE -> new Adaptive(loads);
        };
    }

    /** Alle Funde in der Reihenfolge der Quellen, ohne Obergrenze. */
    static CandidateSelectionStrategy sourceOrder() {
        return new SourceOrder(KeywordSearch.MAX_LIMIT);
    }

    /** Die besten {@code n} nach Titel und Auszug. */
    static CandidateSelectionStrategy topN(int n) {
        return new TopN(n);
    }

    /** Die adaptive Auswahl ohne Obergrenze (Standard). */
    static CandidateSelectionStrategy adaptive() {
        return new Adaptive(KeywordSearch.MAX_LIMIT);
    }

    private static int checked(int value, String name) {
        if (value < 1 || value > KeywordSearch.MAX_LIMIT) {
            throw new IllegalArgumentException(name + " muss zwischen 1 und " + KeywordSearch.MAX_LIMIT + " liegen");
        }
        return value;
    }

    private static List<KeywordSearch.Hit> first(List<KeywordSearch.Hit> hits, int count) {
        List<KeywordSearch.Hit> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            if (selected.size() >= count) {
                break;
            }
            if (seen.add(hit.sourceId() + "\u0000" + hit.pageId())) {
                selected.add(hit);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Alle Funde in der Reihenfolge der Quellen ({@code order}).
     *
     * @param max höchstens so viele
     */
    record SourceOrder(int max) implements CandidateSelectionStrategy {

        public SourceOrder {
            checked(max, "max");
        }

        @Override
        public List<KeywordSearch.Hit> select(Candidates candidates) {
            return first(candidates.needing(candidates.found()), max);
        }
    }

    /**
     * Die besten {@code n} nach Titel und Auszug ({@code top-n}).
     *
     * @param n so viele, die noch laden müssen
     */
    record TopN(int n) implements CandidateSelectionStrategy {

        public TopN {
            checked(n, "n");
        }

        @Override
        public List<KeywordSearch.Hit> select(Candidates candidates) {
            return first(candidates.needing(candidates.ranked()), n);
        }
    }

    /**
     * Adaptiv ({@code adaptive}, Standard): die besten nach Titel und Auszug, so viele, wie der Vordergrund bald schafft.
     * Sei {@code need} die Zahl der Funde, deren Version nicht im Cache liegt, {@code F} die Parallelität und
     * {@code B} der Rückstand des Vordergrunds ({@link Capacity}):
     *
     * <ol>
     *   <li>Ein kleiner Stapel ({@code need ≤} {@value #SMALL_BATCH}) lädt ganz.</li>
     *   <li>Sonst {@code N = max(}{@value #SMALL_BATCH}{@code , }{@value #WAVES}{@code ·F)}: was der Vordergrund in
     *       etwa drei Runden lädt.</li>
     *   <li>Liegt schon mindestens die Hälfte der Funde aktuell im Cache, ist die Liste weitgehend bestätigt:
     *       {@code N} halbiert sich (aufgerundet).</li>
     *   <li>Was im Vordergrund schon wartet, geht ab: {@code N − B}, mindestens {@value #MIN_IMMEDIATE}.</li>
     *   <li>Höchstens {@code max} und höchstens {@code need}.</li>
     * </ol>
     *
     * @param max Obergrenze
     */
    record Adaptive(int max) implements CandidateSelectionStrategy {

        /** Bis zu so viele fehlende Funde lädt ein Stapel ganz. */
        public static final int SMALL_BATCH = 10;
        /** So viele Runden des Vordergrunds plant die Auswahl ein. */
        public static final int WAVES = 3;
        /** So viele lädt ein großer Stapel mindestens. */
        public static final int MIN_IMMEDIATE = 5;

        public Adaptive {
            checked(max, "max");
        }

        @Override
        public List<KeywordSearch.Hit> select(Candidates candidates) {
            List<KeywordSearch.Hit> needed = candidates.needing(candidates.ranked());
            int need = needed.size();
            int total = candidates.found().size();
            int count;
            if (need <= SMALL_BATCH) {
                count = need;
            } else {
                count = Math.max(SMALL_BATCH, WAVES * candidates.capacity().parallelism());
                if (total > 0 && 2 * (total - need) >= total) {
                    count = (count + 1) / 2;
                }
                count = Math.max(MIN_IMMEDIATE, count - candidates.capacity().backlog());
            }
            return first(needed, Math.min(max, Math.min(need, count)));
        }
    }
}
