package com.aresstack.spaceharvester.application.configuration;

import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eine austauschbare Instanz, etwa ein KI-Client oder ein Transport. Neue Aufgaben bekommen nach {@link #swap} die
 * neue Instanz; wer mit {@link #acquire()} eine alte hält, arbeitet bis zum {@link Lease#close()} damit weiter. Die
 * alte wird erst freigegeben, wenn niemand sie mehr hält. So beenden laufende Aufträge ihren Lauf mit der
 * Konfiguration, mit der sie begonnen haben.
 *
 * @param <T> die Instanz
 */
public final class Swappable<T> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Swappable.class);

    private final Consumer<? super T> release;
    private Generation<T> current;
    private boolean closed;

    /**
     * @param initial erste Instanz
     * @param release gibt eine ausgediente Instanz frei, sobald niemand sie mehr hält; Fehler darin werden
     *                protokolliert und verschluckt
     */
    public Swappable(T initial, Consumer<? super T> release) {
        this.current = new Generation<>(Objects.requireNonNull(initial, "initial"));
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Ohne Freigabe, für Instanzen ohne Ressourcen. */
    public static <T> Swappable<T> of(T initial) {
        return new Swappable<>(initial, value -> {
        });
    }

    /** Schließt ausgediente Instanzen. */
    public static <T extends AutoCloseable> Swappable<T> closing(T initial) {
        return new Swappable<>(initial, value -> {
            try {
                value.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Die aktuelle Instanz für einen kurzen Aufruf; wer länger arbeitet, nimmt {@link #acquire()}. */
    public synchronized T get() {
        ensureOpen();
        return current.value;
    }

    /** Hält die aktuelle Instanz, bis das Lease geschlossen wird. */
    public synchronized Lease<T> acquire() {
        ensureOpen();
        current.leases++;
        return new Lease<>(this, current);
    }

    /**
     * Setzt die neue Instanz; die bisherige wird freigegeben, sobald ihr letztes Lease endet (sofort, wenn keines
     * offen ist). Dieselbe Instanz erneut zu setzen, ändert nichts.
     */
    public void swap(T next) {
        Objects.requireNonNull(next, "next");
        Generation<T> retired;
        synchronized (this) {
            ensureOpen();
            if (current.value == next) {
                return;
            }
            retired = current;
            retired.retired = true;
            current = new Generation<>(next);
            if (retired.leases > 0) {
                retired = null;
            }
        }
        if (retired != null) {
            free(retired.value);
        }
    }

    /** Gibt die aktuelle Instanz frei, sobald ihr letztes Lease endet; danach kein {@link #acquire()} mehr. */
    @Override
    public void close() {
        Generation<T> retired;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            retired = current;
            retired.retired = true;
            if (retired.leases > 0) {
                retired = null;
            }
        }
        if (retired != null) {
            free(retired.value);
        }
    }

    private void release(Generation<T> generation) {
        boolean free;
        synchronized (this) {
            generation.leases--;
            free = generation.retired && generation.leases == 0;
        }
        if (free) {
            free(generation.value);
        }
    }

    private void free(T value) {
        try {
            release.accept(value);
        } catch (RuntimeException e) {
            LOG.warn("Ausgediente Instanz {} ließ sich nicht freigeben: {}", value.getClass().getSimpleName(),
                    e.getClass().getSimpleName());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bereits geschlossen");
        }
    }

    private static final class Generation<T> {

        private final T value;
        private int leases;
        private boolean retired;

        private Generation(T value) {
            this.value = value;
        }
    }

    /** Eine gehaltene Instanz; {@link #close()} genau einmal wirksam. */
    public static final class Lease<T> implements AutoCloseable {

        private final Swappable<T> owner;
        private final Generation<T> generation;
        private boolean closed;

        private Lease(Swappable<T> owner, Generation<T> generation) {
            this.owner = owner;
            this.generation = generation;
        }

        public T get() {
            return generation.value;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            owner.release(generation);
        }
    }
}
