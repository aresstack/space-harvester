package com.aresstack.spaceharvester.deck.ndv;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verlauf wie in MainframeMate ({@code NdvConnectionTab}): jeder Eintrag ist eine Bibliothek, der leere Eintrag die
 * Bibliotheksliste. Ein neuer Eintrag verwirft den Vorwärts-Teil.
 */
public final class NdvHistory {

    private final List<String> entries = new ArrayList<>();
    private int index = -1;

    public void add(String library) {
        String entry = library == null ? "" : library;
        if (index >= 0 && entries.get(index).equals(entry)) {
            return;
        }
        while (entries.size() > index + 1) {
            entries.removeLast();
        }
        entries.add(entry);
        index = entries.size() - 1;
    }

    public boolean canBack() {
        return index > 0;
    }

    public boolean canForward() {
        return index < entries.size() - 1;
    }

    public Optional<String> back() {
        if (!canBack()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(--index));
    }

    public Optional<String> forward() {
        if (!canForward()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(++index));
    }
}
