package com.aresstack.spaceharvester.app.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Ein Abbruch-Schalter, der sich meldet (ADR 0027, ohne Abfragen): wer auf etwas wartet, etwa auf das Ende eines
 * Auftrags, meldet mit {@link #onRequest} an, was beim Abbrechen geschehen soll, statt den Schalter immer wieder zu
 * fragen. Als {@link BooleanSupplier} sagt er, ob abgebrochen wurde.
 */
public final class Cancellation implements BooleanSupplier {

    private final AtomicBoolean requested = new AtomicBoolean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Ob abgebrochen wurde. */
    @Override
    public boolean getAsBoolean() {
        return requested.get();
    }

    /** Bricht ab; jeder angemeldete Empfänger läuft einmal, im aufrufenden Thread. Ein zweiter Aufruf tut nichts. */
    public void request() {
        request(Runnable::run);
    }

    /**
     * Bricht sofort ab ({@link #getAsBoolean()} ist danach wahr); die angemeldeten Empfänger laufen auf {@code on},
     * etwa abseits des EDT. Ein zweiter Aufruf tut nichts.
     */
    public void request(java.util.concurrent.Executor on) {
        Objects.requireNonNull(on, "on");
        if (requested.compareAndSet(false, true)) {
            List<Runnable> now = List.copyOf(listeners);
            listeners.removeAll(now);
            on.execute(() -> now.forEach(Runnable::run));
        }
    }

    /** Meldet an, was beim Abbrechen geschehen soll; war schon abgebrochen, läuft es sofort. */
    public void onRequest(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        // Kam der Abbruch dazwischen, läuft der Empfänger hier, genau einmal.
        if (requested.get() && listeners.remove(listener)) {
            listener.run();
        }
    }
}
