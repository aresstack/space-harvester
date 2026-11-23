package com.aresstack.spaceharvester.deck.eakte;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Anwendungsfall „E-Akte-Sitzungen“ des Command Deck: anmelden, auflisten, trennen. Eine Sitzung entsteht nur auf
 * ausdrücklichen Wunsch (Klick auf den Bereich oder „Neue Verbindung“); die Sitzungen überleben jeden Wechsel von
 * Bereich und Perspektive.
 */
public final class EakteSessions {

    private final EakteServer server;
    private final Executor background;
    private final List<EakteSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<EakteSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    public EakteSessions(EakteServer server, Executor background) {
        this.server = Objects.requireNonNull(server, "server");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Meldet im Hintergrund an. Die Sitzung wird sofort gemeldet (CONNECTING); das Kennwort wird nach dem Versuch
     * überschrieben, auch wenn er scheitert.
     *
     * @param password gehört ab hier der Sitzung
     */
    public EakteSession open(EakteProfile profile, char[] password) {
        Objects.requireNonNull(profile, "profile");
        char[] secret = password == null ? new char[0] : password;
        EakteSession session = new EakteSession("eakte-" + counter.incrementAndGet(), profile, background);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        if (secret.length == 0) {
            session.change(EakteState.FAILED, "Kein Kennwort");
            return session;
        }
        background.execute(() -> {
            try {
                session.connected(server.connect(profile, secret));
            } catch (Exception e) {
                session.change(EakteState.FAILED, describe(e));
            } finally {
                Arrays.fill(secret, '\0');
            }
        });
        return session;
    }

    public List<EakteSession> all() {
        return List.copyOf(sessions);
    }

    public void close(EakteSession session) {
        session.disconnect();
        sessions.remove(session);
    }

    public void closeAll() {
        sessions.forEach(EakteSession::disconnect);
    }

    public void onOpened(Consumer<EakteSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /**
     * Ein lesbarer Grund. Eine Ablehnung durch den Anmeldedienst beginnt immer mit „Anmeldung abgelehnt“ (daran
     * erkennt der Bereich, dass er den KeePass-Eintrag sperren muss).
     */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while (!(cause instanceof LoginRefusedException) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        if (cause instanceof LoginRefusedException && !message.startsWith("Anmeldung abgelehnt")) {
            return "Anmeldung abgelehnt: " + message;
        }
        return cause instanceof java.io.IOException ? message : cause.getClass().getSimpleName() + ": " + message;
    }
}
