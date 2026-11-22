package com.aresstack.spaceharvester.deck.ndv;

import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Anwendungsfall „NDV-Sitzungen“ des Command Deck: öffnen, auflisten, trennen. Eine Sitzung entsteht nur auf
 * ausdrücklichen Wunsch; der Wechsel in die Perspektive oder den Bereich verbindet nicht und schreibt nichts. Die
 * Sitzungen überleben jeden Perspektivenwechsel, denn sie gehören nicht zur Oberfläche. NDV-Bibliotheken sind hier
 * keine Wissensquellen und werden nicht indiziert (das macht die Quelle {@code ndv} des Harvesters).
 */
public final class NdvSessions {

    private final NdvConnector connector;
    private final Executor background;
    private final List<NdvSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<NdvSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param connector  Adapter zum Server
     * @param background führt den blockierenden Verbindungsaufbau aus (nie der EDT)
     */
    public NdvSessions(NdvConnector connector, Executor background) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Öffnet eine Sitzung: meldet sie sofort (die Oberfläche zeigt sie als „Verbinde …“) und verbindet im
     * Hintergrund. Das Passwort liest erst der Hintergrund aus {@code login}.
     *
     * @param account Titel des KeePass-Eintrags für die Anzeige
     */
    public NdvSession open(NdvProfile profile, String account, LoginSource login) {
        NdvSession session = new NdvSession("ndv-" + counter.incrementAndGet(), profile, account, connector, login);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        background.execute(session::connect);
        return session;
    }

    /** Baut eine gescheiterte oder getrennte Sitzung im Hintergrund neu auf. */
    public void reconnect(NdvSession session) {
        background.execute(session::connect);
    }

    /** Alle Sitzungen dieses Programmlaufs in der Reihenfolge des Öffnens. */
    public List<NdvSession> all() {
        return List.copyOf(sessions);
    }

    /** Vergisst eine Sitzung und trennt sie vorher. */
    public void close(NdvSession session) {
        session.disconnect();
        sessions.remove(session);
    }

    /** Trennt alle Sitzungen (Programmende). */
    public void closeAll() {
        sessions.forEach(NdvSession::disconnect);
    }

    /** Meldet jede neu geöffnete Sitzung. */
    public void onOpened(Consumer<NdvSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Liefert Benutzer und Passwort, erst wenn verbunden wird (auch beim Wiederverbinden erneut). */
    @FunctionalInterface
    public interface LoginSource {

        /** Der Aufrufer schließt das Ergebnis und löscht damit das Passwort. */
        ServerCredentials read() throws Exception;
    }

    /** Ein lesbarer Grund ohne Stacktrace: die Meldung der innersten Ursache, sonst ihre Klasse. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getMessage();
        }
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message.strip();
    }
}
