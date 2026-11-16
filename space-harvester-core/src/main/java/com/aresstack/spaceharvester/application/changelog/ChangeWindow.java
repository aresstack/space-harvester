package com.aresstack.spaceharvester.application.changelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Sammelt Treffer in ihrer Reihenfolge zu einer Seite: jede Zeile geht durch die Sicht des Aufrufers (Rechte je Zeile,
 * {@code null} heißt unsichtbar); gezählt wird nur, was sichtbar bleibt.
 */
public final class ChangeWindow {

    private final UnaryOperator<ChangeEntry> view;
    private final int offset;
    private final int limit;
    private final List<ChangeEntry> entries = new ArrayList<>();
    private int total;

    public ChangeWindow(UnaryOperator<ChangeEntry> view, int offset, int limit) {
        this.view = Objects.requireNonNull(view, "view");
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("offset oder limit < 0");
        }
        this.offset = offset;
        this.limit = limit;
    }

    /** Nimmt den nächsten Treffer auf. */
    public void offer(ChangeEntry raw) {
        ChangeEntry seen = view.apply(raw);
        if (seen == null) {
            return;
        }
        if (total >= offset && entries.size() < limit) {
            entries.add(seen);
        }
        total++;
    }

    public ChangeEntries result() {
        return new ChangeEntries(entries, total, offset, limit);
    }
}
