package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Ergebnis einer progressiven Suche (ADR 0017): die stabile, sortierte Trefferliste, die Suchanfragen mit ihrem Stand
 * und warum die Suche endete. „Abgeschlossen“ heißt, die Strategie hat ihre Arbeit beendet, nicht, dass jede denkbare
 * Seite untersucht wurde.
 *
 * @param hits       sichtbare Treffer, beste zuerst
 * @param variants   alle Suchanfragen, die ursprüngliche zuerst
 * @param reason     warum die Suche endete
 * @param loads      Kennungen des Ladens in den Cache ({@link KeywordSearch#progress(String)})
 * @param elapsed    Dauer
 * @param failure    nur bei {@link Completion#FAILED}: woran die Suchanfragen scheiterten, soweit der KI-Dienst es
 *                   meldete ({@link AiServiceException.Reason}) oder die Frist ablief; sonst und wenn unbekannt
 *                   {@code null}. Nennt nie Ursachentexte, Key oder Anfrage.
 * @param neighbors  ob die Suchmethode „Nachbarseiten“ (R2) lief und ihre Kandidaten prüfen konnte
 * @param neighborQueries       abgeschlossene Suchanfragen der Nachbarseiten (gezählt wie Varianten)
 * @param failedNeighborQueries davon gescheiterte
 * @param neighborLoad          das Nachladen fehlender Nachbarn im Hintergrund oder {@code null}; nach seinem Ende
 *                              findet {@link NeighborSearch} die geladenen, wenn sie die Anfrage treffen
 * @param technicalTerms        Stand der Suchmethode „Fachbegriffe“ (W8)
 */
public record ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                                Duration elapsed, AiServiceException.Reason failure, Neighbors neighbors,
                                int neighborQueries, int failedNeighborQueries, NeighborLoader.Load neighborLoad,
                                TechnicalTerms technicalTerms) {

    /** Ohne die Suchmethode „Fachbegriffe“. */
    public ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                             Duration elapsed, AiServiceException.Reason failure, Neighbors neighbors,
                             int neighborQueries, int failedNeighborQueries, NeighborLoader.Load neighborLoad) {
        this(hits, variants, reason, loads, elapsed, failure, neighbors, neighborQueries, failedNeighborQueries,
                neighborLoad, TechnicalTerms.OFF);
    }

    /** Ohne Nachladen fehlender Nachbarn. */
    public ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                             Duration elapsed, AiServiceException.Reason failure, Neighbors neighbors,
                             int neighborQueries, int failedNeighborQueries) {
        this(hits, variants, reason, loads, elapsed, failure, neighbors, neighborQueries, failedNeighborQueries, null);
    }

    /**
     * Mit dem Stand der Nachbarseiten, ohne Zählung ihrer Suchanfrage.
     */
    public ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                             Duration elapsed, AiServiceException.Reason failure, Neighbors neighbors) {
        this(hits, variants, reason, loads, elapsed, failure, neighbors, 0, 0, null);
    }

    public ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                             Duration elapsed) {
        this(hits, variants, reason, loads, elapsed, null);
    }

    public ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                             Duration elapsed, AiServiceException.Reason failure) {
        this(hits, variants, reason, loads, elapsed, failure, Neighbors.OFF);
    }

    /** Stand der Suchmethode „Nachbarseiten“ (R2). */
    public enum Neighbors {
        /** Nicht zugeschaltet. */
        OFF,
        /** Zugeschaltet und gelaufen; fehlt ein Nachbar, traf er die Anfrage nicht oder es gab keinen. */
        CHECKED,
        /**
         * Zugeschaltet, aber nicht (ganz) geprüft: Frist, Fehler oder Abbruch. Ein fehlender Nachbar heißt dann nicht,
         * dass es ihn nicht gibt.
         */
        UNCHECKED
    }

    /**
     * Stand der Suchmethode „Fachbegriffe“ (W8). {@link #CAPPED} und {@link #UNCHECKED} sind Hinweise, nie Fehler: Die
     * gefundenen Treffer bleiben stehen.
     */
    public enum TechnicalTerms {
        /** Nicht zugeschaltet. */
        OFF,
        /** Zugeschaltet und ganz gelaufen; auch, wenn die Anfrage keinen Fachbegriff nennt. */
        CHECKED,
        /** Gelaufen, aber der Bereich war größer als die Grenze des Begriffswissens; nur ein Teil wurde gelesen. */
        CAPPED,
        /** Zugeschaltet, aber nicht (ganz) gelaufen: Frist, Abbruch, Grenze der Proben oder Fehler. */
        UNCHECKED
    }

    public ProgressiveResult {
        neighbors = neighbors == null ? Neighbors.OFF : neighbors;
        technicalTerms = technicalTerms == null ? TechnicalTerms.OFF : technicalTerms;
        if (neighborQueries < 0 || failedNeighborQueries < 0 || failedNeighborQueries > neighborQueries) {
            throw new IllegalArgumentException("Zählung der Nachbarsuche ungültig");
        }
        if (reason != Completion.FAILED) {
            failure = null;
        }
        hits = List.copyOf(hits);
        variants = List.copyOf(variants);
        Objects.requireNonNull(reason, "reason");
        loads = List.copyOf(loads);
    }

    public enum Completion {
        /** Das Qualitätsziel des Profils ist erreicht. */
        QUALITY_REACHED,
        /** Alle vorgesehenen Suchanfragen sind gelaufen. */
        ALL_VARIANTS_DONE,
        /** Zeit- oder Abfragebudget ist ausgeschöpft; behauptet nie, das Qualitätsziel sei erreicht. */
        BUDGET_EXHAUSTED,
        /** Abgebrochen, etwa durch eine neue Eingabe. */
        CANCELLED,
        /** Keine Suchanfrage ließ sich ausführen. */
        FAILED
    }

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        SKIPPED
    }

    /** Eine Suchanfrage mit ihrem Stand und wie viele Treffer sie neu brachte. */
    public record Variant(QueryVariant query, Status status, int newHits) {

        public Variant {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Ein Treffer der zusammengeführten Liste; Identität ist Quelle und Seiten-ID.
     *
     * @param hit       Kopfdaten und Auszug; aus dem Cache bestätigt, wenn {@code confirmed}
     * @param score     Bewertung gegen die ursprüngliche Anfrage, {@link Double#NaN} ohne; mit
     *                  {@link SearchProfile.Ranking#SEMANTIC} die Kosinusähnlichkeit zu ihr
     * @param foundBy   Priorität der Suchanfrage, die ihn zuerst freigab
     * @param confirmed ob der Treffer aus dem Cache stammt (Volltextindex, semantisch die Vektoren), nicht nur aus der
     *                  Suche der Quelle
     * @param reranked  ob {@code score} vom Reranker ({@link HitScorer}) stammt; der Reranker ordnet nur um und ist
     *                  keine Ursache des Treffers (R1, Ursachen in {@link KeywordSearch.Hit#reasons()})
     */
    public record RankedHit(KeywordSearch.Hit hit, double score, int foundBy, boolean confirmed, boolean reranked) {

        public RankedHit {
            Objects.requireNonNull(hit, "hit");
        }

        public RankedHit(KeywordSearch.Hit hit, double score, int foundBy, boolean confirmed) {
            this(hit, score, foundBy, confirmed, false);
        }
    }
}
