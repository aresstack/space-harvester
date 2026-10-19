package com.aresstack.spaceharvester.application.query.progressive;

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
 */
public record ProgressiveResult(List<RankedHit> hits, List<Variant> variants, Completion reason, List<String> loads,
                                Duration elapsed) {

    public ProgressiveResult {
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
     */
    public record RankedHit(KeywordSearch.Hit hit, double score, int foundBy, boolean confirmed) {

        public RankedHit {
            Objects.requireNonNull(hit, "hit");
        }
    }
}
