package com.aresstack.spaceharvester.deck.terminal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Eine interaktive Terminal-Sitzung. Sie meldet jeden Zustandswechsel an ihre Beobachter (kein Abfragen); die
 * Meldungen kommen auf dem Thread, der den Wechsel ausgelöst hat – die Oberfläche reicht sie selbst an den EDT weiter.
 */
public final class TerminalSession {

    private final String id;
    private final TerminalProfile profile;
    private final List<Consumer<TerminalSession>> observers = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private TerminalConnection connection;
    private SessionState state = SessionState.CONNECTING;
    private String failure;

    TerminalSession(String id, TerminalProfile profile) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public String id() {
        return id;
    }

    public TerminalProfile profile() {
        return profile;
    }

    public SessionState state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** Der Grund, wenn die Verbindung scheiterte. */
    public Optional<String> failure() {
        lock.lock();
        try {
            return Optional.ofNullable(failure);
        } finally {
            lock.unlock();
        }
    }

    /** Die Darstellung des Adapters, sobald die Verbindung angelegt ist. */
    public <T> Optional<T> view(Class<T> type) {
        TerminalConnection current;
        lock.lock();
        try {
            current = connection;
        } finally {
            lock.unlock();
        }
        return current == null ? Optional.empty() : current.view(type);
    }

    /** Meldet künftige Zustandswechsel. */
    public void observe(Consumer<TerminalSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<TerminalSession> observer) {
        observers.remove(observer);
    }

    void attach(TerminalConnection created) {
        lock.lock();
        try {
            connection = created;
        } finally {
            lock.unlock();
        }
    }

    /** Trennt die Sitzung; eine schon beendete bleibt, wie sie ist. */
    public void disconnect() {
        TerminalConnection current;
        lock.lock();
        try {
            current = connection;
        } finally {
            lock.unlock();
        }
        if (current != null) {
            current.disconnect();
        }
        change(SessionState.DISCONNECTED, null);
    }

    /** Setzt den Zustand; nach dem Ende ändert sich nichts mehr. */
    void change(SessionState next, String reason) {
        lock.lock();
        try {
            if (state.ended() || state == next) {
                return;
            }
            state = next;
            failure = next == SessionState.FAILED ? reason : null;
        } finally {
            lock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
    }
}
