package com.aresstack.spaceharvester.application.observe;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Gebündelte Zustellung auf einem eigenen Thread (ADR 0027). Ein Melder merkt nur einen Schlüssel als geändert vor,
 * auf Wunsch mit Werten (etwa Seiten-IDs); das kostet keinen Wartezustand und hält keinen Schreiber auf. Je Schlüssel
 * wird höchstens einmal je {@link #INTERVAL} zugestellt: Die erste Vormerkung plant die Zustellung nach dem Intervall,
 * weitere Vormerkungen bis dahin sammeln nur Werte. Die Zustellung bekommt den Schlüssel und alle seither gesammelten
 * Werte und liest den jüngsten Stand selbst. Alle Zustellungen laufen nacheinander auf einem Daemon-Thread, der erst
 * mit der ersten Vormerkung entsteht; die Reihenfolge je Schlüssel bleibt so erhalten.
 *
 * @param <K> Schlüssel, etwa die Auftrags-ID oder Quelle und Space
 * @param <V> gesammelte Werte, etwa Seiten-IDs
 */
public final class Coalescer<K, V> implements AutoCloseable {

    /** Höchstens eine Zustellung je Schlüssel in diesem Abstand. */
    public static final Duration INTERVAL = Duration.ofMillis(250);

    private static final System.Logger LOG = System.getLogger(Coalescer.class.getName());

    private final String name;
    private final long intervalNanos;
    private final BiConsumer<K, Set<V>> delivery;
    private final Map<K, Set<V>> pending = new ConcurrentHashMap<>();
    private ScheduledExecutorService thread;
    private boolean closed;

    /**
     * @param name     Name des Threads und des Beobachters für das Protokoll
     * @param delivery stellt zu, auf dem Thread dieses Bündlers; eine Ausnahme wird protokolliert und beendet nichts
     */
    public Coalescer(String name, BiConsumer<K, Set<V>> delivery) {
        this(name, INTERVAL, delivery);
    }

    /** Mit eigenem Intervall, etwa für Tests. */
    public Coalescer(String name, Duration interval, BiConsumer<K, Set<V>> delivery) {
        this.name = Objects.requireNonNull(name, "name");
        if (interval.isNegative()) {
            throw new IllegalArgumentException("Intervall darf nicht negativ sein");
        }
        this.intervalNanos = interval.toNanos();
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    /** Merkt einen Schlüssel ohne Werte vor. */
    public void mark(K key) {
        mark(key, Set.of());
    }

    /** Merkt einen Schlüssel vor und sammelt Werte zu ihm; nach {@link #close()} ohne Wirkung. */
    public void mark(K key, Collection<? extends V> values) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(values, "values");
        pending.compute(key, (k, collected) -> {
            Set<V> set = collected;
            if (set == null) {
                if (!schedule(k)) {
                    return null;
                }
                set = new LinkedHashSet<>();
            }
            set.addAll(values);
            return set;
        });
    }

    /**
     * Merkt einen Schlüssel erst nach einer Wartezeit vor, etwa um nach Ablauf einer Frist den Stand neu zu lesen; nach
     * {@link #close()} ohne Wirkung.
     */
    public void markLater(K key, Collection<? extends V> values, Duration delay) {
        Objects.requireNonNull(key, "key");
        List<V> copy = List.copyOf(values);
        ScheduledExecutorService executor = executor();
        if (executor == null) {
            return;
        }
        try {
            executor.schedule(() -> mark(key, copy), delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            // beendet
        }
    }

    private boolean schedule(K key) {
        ScheduledExecutorService executor = executor();
        if (executor == null) {
            return false;
        }
        try {
            executor.schedule(() -> deliver(key), intervalNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Der Thread, beim ersten Bedarf angelegt; {@code null} nach {@link #close()}. */
    private synchronized ScheduledExecutorService executor() {
        if (closed) {
            return null;
        }
        if (thread == null) {
            thread = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "space-harvester-observe-" + name);
                worker.setDaemon(true);
                return worker;
            });
        }
        return thread;
    }

    private void deliver(K key) {
        Set<V> values = pending.remove(key);
        if (values == null) {
            return;
        }
        try {
            delivery.accept(key, values);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Zustellung des Beobachters {0} scheiterte ({1})", name,
                    e.getClass().getName());
        }
    }

    /**
     * Führt eine einzelne Zustellung ohne Bündeln auf dem Thread aus, sofort und in der Reihenfolge der Aufrufe, etwa ein
     * Diagnoseereignis; nach {@link #close()} ohne Wirkung.
     */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        ScheduledExecutorService executor = executor();
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Zustellung des Beobachters {0} scheiterte ({1})", name,
                            e.getClass().getName());
                }
            });
        } catch (RejectedExecutionException e) {
            // beendet
        }
    }

    /** Beendet den Thread; noch geplante Zustellungen entfallen. */
    @Override
    public void close() {
        ScheduledExecutorService executor;
        synchronized (this) {
            closed = true;
            executor = thread;
            thread = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        pending.clear();
    }
}
