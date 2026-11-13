package com.aresstack.spaceharvester.application.observe;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Die angemeldeten Empfänger eines Beobachters (ADR 0027). Anmelden und Abmelden sind jederzeit erlaubt, auch während
 * einer Zustellung. Eine Ausnahme eines Empfängers wird mit dem Namen des Beobachters und der Ausnahmeklasse
 * protokolliert, nie mit ihrer Meldung; die übrigen Empfänger bekommen ihre Meldung trotzdem, und der Empfänger bleibt
 * angemeldet.
 *
 * @param <L> Art des Empfängers
 */
public final class Listeners<L> {

    private static final System.Logger LOG = System.getLogger(Listeners.class.getName());

    private final String observer;
    private final CopyOnWriteArrayList<Entry<L>> entries = new CopyOnWriteArrayList<>();

    /** @param observer Name des Beobachters für das Protokoll, etwa {@code Aufträge} */
    public Listeners(String observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Meldet einen Empfänger an.
     *
     * @param filter welche Meldungen er bekommt, ausgewertet bei jeder Zustellung an einem Merkmal des Beobachters
     *               (etwa dem Eigentümer eines Auftrags)
     */
    public Subscription add(L listener, Predicate<Object> filter) {
        Entry<L> entry = new Entry<>(Objects.requireNonNull(listener, "listener"),
                Objects.requireNonNull(filter, "filter"), new AtomicBoolean(true));
        entries.add(entry);
        return () -> {
            // Erst sperren, dann austragen: eine Zustellung, die schon über die Liste läuft, ruft ihn danach nicht mehr.
            if (entry.open().compareAndSet(true, false)) {
                entries.remove(entry);
            }
        };
    }

    /** Meldet einen Empfänger für alle Meldungen an. */
    public Subscription add(L listener) {
        return add(listener, ignored -> true);
    }

    /** Ob niemand angemeldet ist; dann muss ein Melder nichts vormerken. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Stellt allen Empfängern zu, deren Filter das Merkmal annimmt. Ein Empfänger, der vor seinem Aufruf abgemeldet
     * wurde, auch während dieser Zustellung, wird nicht mehr gerufen.
     *
     * @param subject Merkmal der Meldung für die Filter, etwa der Eigentümer; darf {@code null} sein
     */
    public void deliver(Object subject, Consumer<? super L> call) {
        for (Entry<L> entry : entries) {
            try {
                if (entry.open().get() && entry.filter().test(subject)) {
                    call.accept(entry.listener());
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Empfänger des Beobachters {0} scheiterte ({1})", observer,
                        e.getClass().getName());
            }
        }
    }

    private record Entry<L>(L listener, Predicate<Object> filter, AtomicBoolean open) {
    }
}
