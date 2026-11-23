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
    private String note;
    /** Der KI-Maskenassistent dieser Sitzung (#1151); er sieht nur den Port {@link TerminalMask}. */
    private final MaskAssistant assistant = new MaskAssistant(this, () -> view(TerminalMask.class));
    /** Was der Mensch eingibt, als Makro für Lesezeichen (läuft immer, wie in MainframeMate). */
    private final TerminalMacro macro = new TerminalMacro();
    /** Woher die Anmeldung kam (liest erst beim Aufruf, hält kein Geheimnis) – für „Neu verbinden“. */
    private volatile TerminalSessions.LoginSource loginSource;
    /** Das Makro, das nach dem Verbinden abgespielt wurde (Lesezeichen) – für „Neu verbinden“. */
    private volatile List<TerminalMacro.Step> replay = List.of();
    /** Der wievielte automatische Neuaufbau diese Sitzung ist; 0 für eine von Hand geöffnete. */
    private volatile int attempt;
    /** Seit wann die Sitzung verbunden ist (Millisekunden), 0 solange nie verbunden. */
    private volatile long connectedSince;

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

    /** Ein Hinweis zur laufenden Sitzung, etwa das Ergebnis der automatischen Anmeldung. */
    public Optional<String> note() {
        lock.lock();
        try {
            return Optional.ofNullable(note);
        } finally {
            lock.unlock();
        }
    }

    /** Setzt den Hinweis und meldet ihn den Beobachtern. */
    void note(String text) {
        lock.lock();
        try {
            note = text;
        } finally {
            lock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
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

    /**
     * Der KI-Maskenassistent dieser Sitzung: Maske lesen, Felder lokal ausfüllen, Schreibmarke lokal bewegen. Er
     * kann nichts an den Host schicken (#1151).
     */
    public MaskAssistant assistant() {
        return assistant;
    }

    /** Die Aufzeichnung der Eingaben dieser Sitzung (Verlauf, Lesezeichen). */
    public TerminalMacro macro() {
        return macro;
    }

    /** Die Anmeldung, mit der die Sitzung geöffnet wurde; {@code null} ohne automatische Anmeldung. */
    public TerminalSessions.LoginSource loginSource() {
        return loginSource;
    }

    /** Das nach dem Verbinden abgespielte Makro (leer ohne Lesezeichen). */
    public List<TerminalMacro.Step> replay() {
        return replay;
    }

    /** Der wievielte automatische Neuaufbau diese Sitzung ist; 0 für eine von Hand geöffnete. */
    public int attempt() {
        return attempt;
    }

    void attempt(int number) {
        attempt = number;
    }

    /** Seit wann die Sitzung verbunden ist (Millisekunden seit 1970), 0 solange nie verbunden. */
    long connectedSince() {
        return connectedSince;
    }

    void origin(TerminalSessions.LoginSource login, List<TerminalMacro.Step> steps) {
        loginSource = login;
        replay = steps == null ? List.of() : List.copyOf(steps);
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
            if (next == SessionState.CONNECTED && connectedSince == 0) {
                connectedSince = System.currentTimeMillis();
            }
            failure = next == SessionState.FAILED ? reason : null;
        } finally {
            lock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
    }
}
