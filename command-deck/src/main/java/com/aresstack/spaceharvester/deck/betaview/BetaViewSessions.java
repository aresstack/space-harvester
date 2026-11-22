package com.aresstack.spaceharvester.deck.betaview;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Anwendungsfall „BetaView-Sitzungen“ des Command Deck: anmelden, auflisten, abmelden. Eine Sitzung entsteht nur auf
 * ausdrücklichen Wunsch; der Wechsel in die Perspektive oder den Bereich meldet nirgends an und schreibt nichts
 * (#1142). Die Sitzungen gehören nicht zur Oberfläche und überleben jeden Wechsel.
 */
public final class BetaViewSessions {

    private final BetaViewServer server;
    private final Executor background;
    private final List<BetaViewSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<BetaViewSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param server     Adapter zum Server
     * @param background führt Anmeldung und alle Vorgänge der Sitzungen aus (nie der EDT)
     */
    public BetaViewSessions(BetaViewServer server, Executor background) {
        this.server = Objects.requireNonNull(server, "server");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Meldet im Hintergrund an. Die Sitzung wird sofort gemeldet (Zustand CONNECTING), damit die Oberfläche sie zeigt;
     * das Kennwort wird nach dem Versuch überschrieben, auch wenn er scheitert.
     *
     * @param password Kennwort; gehört ab hier der Sitzung, der Aufrufer darf es nicht weiter benutzen
     */
    public BetaViewSession open(BetaViewProfile profile, char[] password) {
        Objects.requireNonNull(profile, "profile");
        char[] secret = password == null ? new char[0] : password;
        BetaViewSession session = new BetaViewSession("betaview-" + counter.incrementAndGet(), profile, background);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        if (secret.length == 0) {
            session.change(ConnectionState.FAILED, "Kein Kennwort");
            return session;
        }
        background.execute(() -> {
            try {
                session.connected(server.login(profile, secret));
            } catch (Exception e) {
                session.change(ConnectionState.FAILED, describe(e));
            } finally {
                Arrays.fill(secret, '\0');
            }
        });
        return session;
    }

    /** Alle Sitzungen dieses Programmlaufs in der Reihenfolge des Öffnens, auch beendete. */
    public List<BetaViewSession> all() {
        return List.copyOf(sessions);
    }

    /** Meldet ab und vergisst die Sitzung. */
    public void close(BetaViewSession session) {
        session.disconnect();
        sessions.remove(session);
    }

    /** Meldet alle ab (Programmende). */
    public void closeAll() {
        sessions.forEach(BetaViewSession::disconnect);
    }

    /** Meldet jede neu geöffnete Sitzung. */
    public void onOpened(Consumer<BetaViewSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Ein lesbarer Grund: Klasse und Meldung der innersten Ursache, ohne Stacktrace. */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        if (cause instanceof java.io.IOException && message != null && !message.isBlank()) {
            return message;
        }
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
