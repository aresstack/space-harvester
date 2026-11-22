package com.aresstack.spaceharvester.deck.ftp;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Anwendungsfall „FTP-Sitzungen“ des Command Deck: öffnen, auflisten, trennen. Eine Sitzung entsteht nur auf
 * ausdrücklichen Wunsch („Verbinden“); der Wechsel in die Perspektive oder den Bereich verbindet nichts und schreibt
 * nichts (#1142). Die Sitzungen gehören nicht zur Oberfläche und überleben jeden Wechsel.
 */
public final class FtpSessions {

    private final FtpConnector connector;
    private final Executor background;
    private final List<FtpSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<FtpSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param connector  Adapter zum Host
     * @param background führt die blockierenden FTP-Aufrufe aus (nie der EDT)
     */
    public FtpSessions(FtpConnector connector, Executor background) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Öffnet eine Sitzung, meldet sie (damit die Oberfläche sie zeigt) und verbindet im Hintergrund.
     *
     * @param label       Anzeigename, meist der KeePass-Eintrag
     * @param credentials liefert Benutzer und Passwort erst beim Anmelden
     */
    public FtpSession open(FtpProfile profile, String label, FtpSession.CredentialSource credentials) {
        FtpSession session = new FtpSession("ftp-" + counter.incrementAndGet(), label, profile, credentials, connector,
                background);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        session.connect();
        return session;
    }

    /** Alle Sitzungen dieses Programmlaufs in der Reihenfolge des Öffnens. */
    public List<FtpSession> all() {
        return List.copyOf(sessions);
    }

    /** Trennt und vergisst eine Sitzung. */
    public void close(FtpSession session) {
        session.disconnect();
        sessions.remove(session);
    }

    /** Trennt alle (Programmende). */
    public void closeAll() {
        sessions.forEach(FtpSession::disconnect);
    }

    public void onOpened(Consumer<FtpSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }
}
