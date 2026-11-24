package com.aresstack.spaceharvester.deck.ndv;

import com.aresstack.spaceharvester.deck.NavigationHistory;

/**
 * Verlauf wie in MainframeMate ({@code NdvConnectionTab}): jeder Eintrag ist eine Bibliothek, der leere Eintrag die
 * Bibliotheksliste. Ein neuer Eintrag verwirft den Vorwärts-Teil.
 */
public final class NdvHistory extends NavigationHistory<String> {

    @Override
    public void add(String library) {
        super.add(library == null ? "" : library);
    }
}
