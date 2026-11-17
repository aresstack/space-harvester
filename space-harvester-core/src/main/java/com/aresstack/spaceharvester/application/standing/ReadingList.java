package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Leseempfehlung über eine Auswahl (ADR 0032, FL6): Seiten in der Reihenfolge, in der sie sich zum Lesen lohnen.
 * Reine Auswertung, speichert nichts.
 *
 * @param topic         gewähltes Thema (Begriff) oder {@code null} für die ganze Auswahl
 * @param topTerms      die häufigsten Begriffe der Auswahl (nach Zahl der Seiten), meiste zuerst
 * @param changesSince  Beginn des Zeitraums für {@link ReadingReason#RECENT_CHANGE}
 * @param pages         Empfehlungen, höchste Summe zuerst; bei gleicher Summe nach Titel
 */
public record ReadingList(String topic, List<String> topTerms, Instant changesSince, List<Recommendation> pages) {

    public ReadingList {
        topTerms = List.copyOf(topTerms);
        Objects.requireNonNull(changesSince, "changesSince");
        pages = List.copyOf(pages);
    }

    /**
     * Eine empfohlene Seite.
     *
     * @param page     Seite bzw. Dokument
     * @param score    Summe der Teilzahlen
     * @param parts    die Teilzahlen, je Grund höchstens eine, ohne Nullen
     * @param standing Stand der Seite aus der Status-quo-Sicht
     */
    public record Recommendation(KnowledgeGraph.PageRef page, int score, List<Part> parts, PageStanding standing) {

        public Recommendation {
            Objects.requireNonNull(page, "page");
            parts = List.copyOf(parts);
            Objects.requireNonNull(standing, "standing");
        }
    }

    /**
     * Eine Teilzahl.
     *
     * @param reason Grund
     * @param count  wie oft der Grund zutrifft (Aussagen, Begriffe, Erwähnungen; 1 bei Ja/Nein-Gründen)
     * @param points Punkte insgesamt ({@code count} mal Punkte je Grund)
     * @param terms  bei {@link ReadingReason#TOP_TERMS} die gefundenen Begriffe, sonst leer
     */
    public record Part(ReadingReason reason, int count, int points, List<String> terms) {

        public Part {
            Objects.requireNonNull(reason, "reason");
            terms = List.copyOf(terms);
        }
    }
}
