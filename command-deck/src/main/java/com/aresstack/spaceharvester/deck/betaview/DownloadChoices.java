package com.aresstack.spaceharvester.deck.betaview;

/**
 * Was der Download-Dialog des Servers für das gerade gezeigte Dokument anbietet.
 *
 * @param pages      Seitenzahl
 * @param csvEnabled ob CSV als Format möglich ist
 */
public record DownloadChoices(int pages, boolean csvEnabled) {

    public DownloadChoices {
        pages = Math.max(1, pages);
    }
}
