package com.aresstack.spaceharvester.deck;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Zurück/Vor-Verlauf wie in MainframeMate ({@code NdvConnectionTab}): Ein neuer Eintrag verwirft den Vorwärts-Teil,
 * derselbe Eintrag zweimal hintereinander zählt einmal. Gemeinsam für alle Bereiche des Command Deck.
 *
 * @param <T> ein Ort des Bereichs (Bibliothek, Mappe, Dokument …)
 */
public class NavigationHistory<T> {

    private final List<T> entries = new ArrayList<>();
    private int index = -1;

    public void add(T entry) {
        Objects.requireNonNull(entry, "entry");
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

    public Optional<T> back() {
        if (!canBack()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(--index));
    }

    public Optional<T> forward() {
        if (!canForward()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(++index));
    }
}
