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

    /** Springt {@code steps} Einträge weit (negativ zurück, positiv vor), etwa aus dem Verlauf des Zurück-Knopfs. */
    public Optional<T> go(int steps) {
        int target = index + steps;
        if (steps == 0 || target < 0 || target >= entries.size()) {
            return Optional.empty();
        }
        index = target;
        return Optional.of(entries.get(target));
    }

    /** Die Einträge zurück, der vorige zuerst. */
    public List<T> backEntries() {
        List<T> back = new ArrayList<>();
        for (int i = index - 1; i >= 0; i--) {
            back.add(entries.get(i));
        }
        return back;
    }

    /** Die Einträge vor, der nächste zuerst. */
    public List<T> forwardEntries() {
        return index < 0 ? List.of() : List.copyOf(entries.subList(index + 1, entries.size()));
    }
}
