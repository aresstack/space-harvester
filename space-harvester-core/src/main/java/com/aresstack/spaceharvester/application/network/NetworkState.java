package com.aresstack.spaceharvester.application.network;

import com.aresstack.spaceharvester.application.jobs.JobTimer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Der Netzzustand des Programms (Angelo 22.11.2026: „Er müsste feststellen, ob er Internet hat … nicht alle testen, wenn
 * keine Verbindung besteht. Man muss eigentlich nur darauf warten, dass das Internet wiederkommt.“).
 *
 * <p>Ein Netzfehler ({@link #networkFailure}: keine Verbindung, kein Name, keine Route, keine HTTP-Antwort) schaltet
 * auf offline ({@link #failed}); jeder Abruf mit Antwort schaltet zurück ({@link #succeeded}). Offline prüft genau
 * einer, ob das Netz zurück ist: Nach einer Frist ({@link #delay}, wachsend bis {@link #LONGEST_WAIT}) bekommt der
 * erste Wartende die Erlaubnis für einen einzigen Abruf ({@link #permit()}, {@link #tryPermit()}); alle übrigen warten
 * weiter. Antwortet die Quelle, sind alle wieder frei; scheitert er am Netz, beginnt die nächste, längere Frist. Meldet
 * sich der Prüfende gar nicht, gilt seine Erlaubnis mit der nächsten Frist als verfallen. Keine Abfragen in festen
 * Abständen (ADR 0027): Nur die eine Frist läuft, und jede Änderung geht an die Beobachter ({@link #subscribe}).
 *
 * <p>Wer Arbeit über das Netz tut, meldet jedes Ergebnis hierher und wartet offline, statt seine Elemente
 * scheitern zu lassen: Ein Netzfehler verbraucht keinen Versuch und macht nichts rot.
 */
public final class NetworkState implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(NetworkState.class.getName());

    /** Die erste Frist nach dem Verlust der Verbindung. */
    public static final Duration FIRST_WAIT = Duration.ofSeconds(5);
    /** Längste Frist zwischen zwei Prüfungen. */
    public static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    /**
     * Der Stand für Anzeigen.
     *
     * @param online    ob das Netz als erreichbar gilt
     * @param reason    offline: die Art des Netzfehlers, etwa {@code ConnectException}; sonst {@code null}
     * @param since     offline: seit wann; sonst {@code null}
     * @param nextCheck offline: wann die nächste Prüfung frei wird; sonst {@code null}
     */
    public record Status(boolean online, String reason, Instant since, Instant nextCheck) {

        /** Erreichbar. */
        public static final Status ONLINE = new Status(true, null, null, null);
    }

    private final Clock clock;
    private final JobTimer timer;
    private final ScheduledExecutorService own;
    private final ReentrantLock lock = new ReentrantLock();
    private final Listeners<Consumer<Status>> listeners = new Listeners<>("Netzzustand");
    /** Wer auf die Erlaubnis wartet, in der Reihenfolge der Anfragen; unter {@link #lock}. */
    private final Deque<CompletableFuture<Void>> waiting = new ArrayDeque<>();
    private boolean online = true;
    private String reason;
    private Instant since;
    private Instant nextCheck;
    /** Fehlgeschlagene Prüfungen seit dem Verlust der Verbindung; bestimmt die nächste Frist. */
    private int checks;
    /** Die Frist ist um, aber noch niemand hat die Erlaubnis abgeholt. */
    private boolean checkOpen;
    /** Einer prüft gerade. */
    private boolean checking;
    /** Gilt für die laufende Frist; eine ältere, die noch feuert, tut nichts. */
    private Object token;
    private boolean closed;

    /** Mit eigener Zeitsteuerung. */
    public NetworkState() {
        this(Clock.systemUTC(), null);
    }

    /**
     * @param timer Zeitsteuerung der Fristen, etwa in Tests von Hand; {@code null} für eine eigene
     */
    public NetworkState(Clock clock, JobTimer timer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timer == null) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "network-state");
                thread.setDaemon(true);
                return thread;
            });
            this.own = scheduler;
            this.timer = (delay, task) -> {
                try {
                    scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // Wird geschlossen.
                }
            };
        } else {
            this.own = null;
            this.timer = timer;
        }
    }

    /**
     * Ob ein Fehler am Netz liegt und nicht an der Gegenseite: keine Verbindung, kein Name, keine Route, Zeit beim
     * Verbindungsaufbau abgelaufen. Eine HTTP-Antwort (auch 5xx), TLS-Befunde und Zeitüberschreitungen einer laufenden
     * Anfrage zählen nicht.
     */
    public static boolean networkFailure(Throwable failure) {
        return cause(failure) != null;
    }

    /** Der Netzfehler in der Kette, oder {@code null}. */
    private static Throwable cause(Throwable failure) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = failure; current != null && seen.put(current, true) == null;
             current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException || current instanceof PortUnreachableException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof UnresolvedAddressException) {
                return current;
            }
            if (current instanceof SocketException && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains("unreachable")) {
                return current;
            }
        }
        return null;
    }

    /** Die Frist vor der Prüfung nach {@code checks} gescheiterten Prüfungen: 5 s, 10 s, 20 s … bis 5 min. */
    public static Duration delay(int checks) {
        long seconds = FIRST_WAIT.getSeconds() << Math.min(Math.max(0, checks), 10);
        return seconds >= LONGEST_WAIT.getSeconds() ? LONGEST_WAIT : Duration.ofSeconds(seconds);
    }

    /** Ob das Netz als erreichbar gilt. */
    public boolean online() {
        lock.lock();
        try {
            return online;
        } finally {
            lock.unlock();
        }
    }

    /** Der jetzige Stand. */
    public Status status() {
        lock.lock();
        try {
            return online ? Status.ONLINE : new Status(false, reason, since, nextCheck);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Meldet einen gescheiterten Abruf. Liegt es am Netz, gilt das Netz als weg (oder die Prüfung als gescheitert) und
     * der Aufrufer soll das Element zurücklegen und warten, statt es scheitern zu lassen.
     *
     * @return ob es ein Netzfehler war
     */
    public boolean failed(Throwable failure) {
        Throwable network = cause(failure);
        if (network == null) {
            return false;
        }
        boolean changed = false;
        lock.lock();
        try {
            if (closed) {
                return true;
            }
            reason = network.getClass().getSimpleName();
            if (online) {
                online = false;
                since = clock.instant();
                checks = 0;
                checking = false;
                checkOpen = false;
                schedule();
                changed = true;
                LOG.log(System.Logger.Level.INFO, "Keine Netzverbindung ({0}); nächste Prüfung in {1} s", reason,
                        delay(0).getSeconds());
            } else if (checking) {
                // Die Prüfung scheiterte: die nächste, längere Frist.
                checking = false;
                checks++;
                schedule();
                changed = true;
            }
        } finally {
            lock.unlock();
        }
        if (changed) {
            deliver();
        }
        return true;
    }

    /** Meldet einen Abruf mit Antwort der Gegenseite: Das Netz ist da, alle Wartenden dürfen weiter. */
    public void succeeded() {
        java.util.List<CompletableFuture<Void>> released;
        lock.lock();
        try {
            if (online) {
                return;
            }
            online = true;
            LOG.log(System.Logger.Level.INFO, "Netzverbindung zurück nach {0} s",
                    Duration.between(since, clock.instant()).getSeconds());
            reason = null;
            since = null;
            nextCheck = null;
            checks = 0;
            checking = false;
            checkOpen = false;
            token = null;
            released = new java.util.ArrayList<>(waiting);
            waiting.clear();
        } finally {
            lock.unlock();
        }
        released.forEach(waiter -> waiter.complete(null));
        deliver();
    }

    /**
     * Erlaubnis für einen Abruf, ohne zu warten: online immer; offline nur der Erste nach Ablauf der Frist, der damit
     * prüft und sein Ergebnis meldet.
     */
    public boolean tryPermit() {
        lock.lock();
        try {
            if (online) {
                return true;
            }
            if (checkOpen) {
                claim();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wie {@link #tryPermit()}, wartend: erfüllt, sobald der Aufrufer abrufen darf. Wer nicht mehr warten will, bricht
     * die Zukunft ab ({@link CompletableFuture#cancel}); geschlossen endet sie mit {@link CancellationException}.
     */
    public CompletableFuture<Void> permit() {
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(new CancellationException("Netzzustand geschlossen"));
            }
            if (online) {
                return CompletableFuture.completedFuture(null);
            }
            if (checkOpen) {
                claim();
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            waiting.addLast(waiter);
            return waiter;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Meldet jede Änderung (offline, zurück, Prüfung frei); der Empfänger liest den Stand, der bei der Zustellung gilt.
     * Er wird auf dem meldenden Thread gerufen und darf nicht blockieren.
     */
    public Subscription subscribe(Consumer<Status> listener) {
        return listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Unter {@link #lock}: plant die nächste Frist. */
    private void schedule() {
        Object mine = new Object();
        token = mine;
        Duration wait = delay(checks);
        nextCheck = clock.instant().plus(wait);
        timer.schedule(wait, () -> due(mine));
    }

    /**
     * Unter {@link #lock}: Einer prüft jetzt. Meldet er sich nicht (etwa abgebrochen), gilt seine Erlaubnis mit der
     * nächsten Frist als verfallen.
     */
    private void claim() {
        checkOpen = false;
        checking = true;
        schedule();
    }

    /** Die Frist ist um: Der erste Wartende prüft, sonst der nächste, der fragt. */
    private void due(Object mine) {
        CompletableFuture<Void> granted = null;
        lock.lock();
        try {
            if (closed || online || token != mine) {
                return;
            }
            if (checking) {
                // Der Prüfende meldete sich nicht: seine Erlaubnis verfällt, und die Frist wächst.
                checks++;
                checking = false;
            }
            while (!waiting.isEmpty() && granted == null) {
                CompletableFuture<Void> next = waiting.pollFirst();
                if (!next.isDone()) {
                    granted = next;
                }
            }
            if (granted != null) {
                claim();
            } else {
                // Niemand wartet: Die Prüfung bleibt frei für den Nächsten, der fragt; bis dahin läuft keine Frist.
                checkOpen = true;
                token = null;
                nextCheck = clock.instant();
            }
        } finally {
            lock.unlock();
        }
        if (granted != null) {
            granted.complete(null);
        }
        deliver();
    }

    private void deliver() {
        if (listeners.isEmpty()) {
            return;
        }
        Status now = status();
        listeners.deliver(null, listener -> listener.accept(now));
    }

    @Override
    public void close() {
        java.util.List<CompletableFuture<Void>> released;
        lock.lock();
        try {
            closed = true;
            token = null;
            released = new java.util.ArrayList<>(waiting);
            waiting.clear();
        } finally {
            lock.unlock();
        }
        released.forEach(waiter -> waiter.completeExceptionally(new CancellationException("Netzzustand geschlossen")));
        if (own != null) {
            own.shutdownNow();
        }
    }
}
