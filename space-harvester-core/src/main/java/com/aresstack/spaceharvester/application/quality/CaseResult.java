package com.aresstack.spaceharvester.application.quality;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Messung einer Testfrage.
 *
 * @param id                  Kennung der Frage
 * @param kinds               Arten der Frage
 * @param expected            erwartete Fundstellen
 * @param ranks               je erwartete Fundstelle ihr Rang in der Endliste ab 1, {@code 0} = nicht gefunden
 * @param stored              je erwartete Fundstelle: vor der Frage lokal gespeichert
 * @param durationMillis      Laufzeit bis zum Endergebnis
 * @param firstExpectedMillis Laufzeit bis eine erwartete Fundstelle zum ersten Mal in einer Rangliste stand, {@code -1}
 *                            = nie
 * @param completion          Abschlussgrund der Suche, leer wenn gescheitert
 * @param variants            gelaufene Suchanfragen neben der ursprünglichen
 * @param unavailable         Quellen ohne Antwort
 * @param hits                Länge der Endliste
 * @param bestScore           höchste Bewertung der Endliste, {@link Double#NaN} ohne Treffer
 * @param usefulInContext     Treffer im Chat-Kontext mit Bewertung mindestens {@link QualityRun.Settings#relevantScore()}
 * @param failure             Klasse des Fehlers, wenn die Suche gescheitert ist, sonst {@code null}
 * @param found               die ersten 10 Seiten der Endliste
 * @param question            Wortlaut, nur für die lokale Ergebnisdatei
 * @param titles              Titel der ersten 10 Seiten, nur für die lokale Ergebnisdatei
 */
public record CaseResult(String id, Set<CaseKind> kinds, List<PageRef> expected, List<Integer> ranks,
                         List<Boolean> stored, long durationMillis, long firstExpectedMillis, String completion,
                         int variants, int unavailable, int hits, double bestScore, int usefulInContext,
                         String failure, List<PageRef> found, String question, List<String> titles) {

    public CaseResult {
        Objects.requireNonNull(id, "id");
        kinds = Set.copyOf(kinds);
        expected = List.copyOf(expected);
        ranks = List.copyOf(ranks);
        stored = List.copyOf(stored);
        found = List.copyOf(found);
        titles = titles == null ? List.of() : titles.stream().map(t -> t == null ? "" : t).toList();
        completion = completion == null ? "" : completion;
        question = question == null ? "" : question;
        if (ranks.size() != expected.size() || stored.size() != expected.size()) {
            throw new IllegalArgumentException("je erwartete Fundstelle ein Rang und ein Speicherstand");
        }
    }

    /** Bester Rang einer erwarteten Fundstelle, {@code 0} = keine gefunden. */
    public int bestRank() {
        int best = 0;
        for (int rank : ranks) {
            if (rank > 0 && (best == 0 || rank < best)) {
                best = rank;
            }
        }
        return best;
    }

    /** Mindestens eine erwartete Fundstelle unter den ersten {@code k}. */
    public boolean inTop(int k) {
        int best = bestRank();
        return best > 0 && best <= k;
    }

    /** Anteil der erwarteten Fundstellen unter den ersten 10; {@code 0} ohne erwartete. */
    public double recallTop10() {
        if (expected.isEmpty()) {
            return 0;
        }
        long inTop = ranks.stream().filter(rank -> rank > 0 && rank <= 10).count();
        return (double) inTop / expected.size();
    }

    /** Ob alle erwarteten Fundstellen vor der Frage lokal lagen. */
    public boolean allStored() {
        return !stored.contains(Boolean.FALSE);
    }

    /** Ob die Antwort nirgends steht ({@link CaseKind#KEINE}). */
    public boolean noAnswer() {
        return kinds.contains(CaseKind.KEINE);
    }

    /** Bei {@link CaseKind#KEINE}: die Suche lieferte zu Recht nichts Brauchbares in den Chat-Kontext. */
    public boolean noAnswerCorrect() {
        return noAnswer() && failure == null && usefulInContext == 0;
    }

    public boolean failed() {
        return failure != null;
    }
}
