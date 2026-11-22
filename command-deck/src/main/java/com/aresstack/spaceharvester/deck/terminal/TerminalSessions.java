package com.aresstack.spaceharvester.deck.terminal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Anwendungsfall „Terminal-Sitzungen“ des Command Deck: öffnen, auflisten, trennen. Eine Sitzung entsteht nur auf
 * ausdrücklichen Wunsch; der Wechsel in die Perspektive baut keine Verbindung auf und schreibt nichts (#1142).
 * Die Sitzungen überleben jeden Perspektivenwechsel, denn sie gehören nicht zur Oberfläche.
 */
public final class TerminalSessions {

    private final TerminalConnector connector;
    private final Executor background;
    private final List<TerminalSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<TerminalSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param connector  Adapter zum Host
     * @param background führt den blockierenden Verbindungsaufbau aus (nie der EDT)
     */
    public TerminalSessions(TerminalConnector connector, Executor background) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Öffnet eine Sitzung: legt die Verbindung an, meldet die Sitzung (damit die Oberfläche ihre Darstellung einhängt)
     * und baut die Verbindung dann im Hintergrund auf. Scheitert schon das Anlegen, ist die Sitzung sofort FAILED.
     */
    public TerminalSession open(TerminalProfile profile) {
        TerminalSession session = new TerminalSession("terminal-" + counter.incrementAndGet(), profile);
        TerminalConnection connection;
        try {
            connection = connector.create(profile, new TerminalConnection.Listener() {
                @Override
                public void connected() {
                    session.change(SessionState.CONNECTED, null);
                }

                @Override
                public void closed(String reason) {
                    session.change(reason == null ? SessionState.DISCONNECTED : SessionState.FAILED, reason);
                }
            });
        } catch (Exception e) {
            sessions.add(session);
            openedObservers.forEach(observer -> observer.accept(session));
            session.change(SessionState.FAILED, describe(e));
            return session;
        }
        session.attach(connection);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        background.execute(() -> {
            try {
                connection.connect();
            } catch (Exception e) {
                connection.disconnect();
                session.change(SessionState.FAILED, describe(e));
            }
        });
        return session;
    }

    /** Alle Sitzungen dieses Programmlaufs in der Reihenfolge des Öffnens, auch beendete. */
    public List<TerminalSession> all() {
        return List.copyOf(sessions);
    }

    /** Vergisst eine Sitzung und trennt sie vorher. */
    public void close(TerminalSession session) {
        session.disconnect();
        sessions.remove(session);
    }

    /** Trennt alle Sitzungen (Programmende). */
    public void closeAll() {
        sessions.forEach(TerminalSession::disconnect);
    }

    /** Meldet jede neu geöffnete Sitzung. */
    public void onOpened(Consumer<TerminalSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Ein lesbarer Grund: Klasse und Meldung, ohne Stacktrace. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
