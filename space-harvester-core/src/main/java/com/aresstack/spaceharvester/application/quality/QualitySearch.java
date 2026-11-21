package com.aresstack.spaceharvester.application.quality;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Stellt eine Testfrage an die Suche. In der App ist das genau der Weg des Chats (progressive Suche im Bereich mit dem
 * eingestellten Profil und derselben Frist); dieses Paket verändert die Suche nicht.
 */
@FunctionalInterface
public interface QualitySearch {

    /**
     * Sucht und wartet auf das Endergebnis.
     *
     * @param question Testfrage
     * @param interim  bekommt jede vorläufige Rangliste, sobald die Suche sie meldet (für „erster Fund nach …“)
     * @throws Exception Ausfall der Suche; der Lauf zählt die Frage dann als gescheitert und misst weiter
     */
    Answer search(QualityCase question, Consumer<List<RankedPage>> interim) throws Exception;

    /**
     * Ein Treffer der Rangliste.
     *
     * @param page  Seite
     * @param score Bewertung der Suche gegen die Frage (Skala des Suchprofils), {@link Double#NaN} ohne
     * @param title Titel, nur für die lokale Ergebnisdatei; darf {@code null} sein
     */
    record RankedPage(PageRef page, double score, String title) {

        public RankedPage {
            Objects.requireNonNull(page, "page");
        }
    }

    /**
     * Endergebnis einer Frage.
     *
     * @param hits        Rangliste, bester zuerst
     * @param completion  Abschlussgrund der Suche (etwa {@code QUALITY_REACHED}, {@code TIME_BUDGET})
     * @param queries     Zahl der gelaufenen Suchanfragen samt der ursprünglichen
     * @param failed      davon gescheitert (etwa weil eine Quelle nicht antwortete)
     */
    record Answer(List<RankedPage> hits, String completion, int queries, int failed) {

        public Answer {
            hits = List.copyOf(hits);
            completion = completion == null ? "" : completion;
        }
    }
}
