package com.aresstack.spaceharvester.application.holdings;

import java.util.Set;

/**
 * Empfänger der Bestandsmeldungen (ADR 0027), angemeldet mit {@link Holdings#subscribe}. Meldungen kommen gebündelt
 * auf dem Zustell-Thread des Core, je Space höchstens alle
 * {@link com.aresstack.spaceharvester.application.observe.Coalescer#INTERVAL}, nie auf dem schreibenden Thread und nie
 * auf dem EDT. Sie nennen nur, was sich geändert hat; den Stand liest der Empfänger danach mit
 * {@link Holdings#overview}. Doppelte Meldungen sind möglich.
 */
public interface HoldingsListener {

    /**
     * Diese Seiten wurden geschrieben, entfernt, gesperrt oder ihr Laden ist zu Ende; höchstens
     * {@link Holdings#MAX_PAGES_PER_MESSAGE} Seiten, sonst kommt {@link #spaceChanged}.
     */
    void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds);

    /**
     * Der Space hat sich als Ganzes geändert (Generation bestätigt, sehr viele Seiten); einmal neu lesen. Ein leerer
     * {@code spaceKey} an einen Empfänger für alle Spaces heißt: alle Spaces der Quelle (Quelle entfernt oder geleert).
     */
    void spaceChanged(String sourceId, String spaceKey);

    /**
     * Das Laden dieser Seiten hat begonnen (Beschaffungsplaner, Abgleich, Export); das Ende kommt als
     * {@link #pagesChanged}. Kommt vor einem {@link #pagesChanged} derselben Zustellung.
     */
    default void loading(String sourceId, String spaceKey, Set<String> pageIds) {
    }
}
