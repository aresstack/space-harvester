package com.aresstack.spaceharvester.application.terms;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Anmeldestelle einer Ablage für {@link TermIndex#subscribe}: Wer mitzählt (etwa {@link TermLedger}), erfährt, welche
 * Seite oder Quelle sich geändert haben kann, und fragt nicht nach (kein Polling, ADR 0027).
 */
public final class TermChanges {

    private static final System.Logger LOG = System.getLogger(TermChanges.class.getName());

    private final List<Consumer<TermIndex.Change>> listeners = new CopyOnWriteArrayList<>();

    /** @return Abmelden */
    public Runnable subscribe(Consumer<TermIndex.Change> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Im schreibenden Thread; ein Zuhörer darf nur vermerken, nicht rechnen. */
    public void fire(String sourceId, String pageId) {
        if (listeners.isEmpty()) {
            return;
        }
        TermIndex.Change change = new TermIndex.Change(sourceId, pageId);
        for (Consumer<TermIndex.Change> listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Zuhörer des Begriffsindex ist gescheitert ({0})",
                        e.getClass().getName());
            }
        }
    }
}
