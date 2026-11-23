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
    private final LoginScript loginScript;

    /** Liefert die Zugangsdaten erst im Hintergrund, nach dem Verbinden (etwa aus KeePass). */
    @FunctionalInterface
    public interface LoginSource {
        TerminalLogin login() throws Exception;
    }

    /**
     * @param connector  Adapter zum Host
     * @param background führt den blockierenden Verbindungsaufbau aus (nie der EDT)
     */
    public TerminalSessions(TerminalConnector connector, Executor background) {
        this(connector, background, new LoginScript(LoginScript.DEFAULT_DELAY));
    }

    public TerminalSessions(TerminalConnector connector, Executor background, LoginScript loginScript) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.background = Objects.requireNonNull(background, "background");
        this.loginScript = Objects.requireNonNull(loginScript, "loginScript");
    }

    /**
     * Öffnet eine Sitzung: legt die Verbindung an, meldet die Sitzung (damit die Oberfläche ihre Darstellung einhängt)
     * und baut die Verbindung dann im Hintergrund auf. Scheitert schon das Anlegen, ist die Sitzung sofort FAILED.
     */
    public TerminalSession open(TerminalProfile profile) {
        return open(profile, null);
    }

    /**
     * Wie {@link #open(TerminalProfile)}; mit {@code login} meldet sich die Sitzung nach dem Verbinden selbst an
     * (Ablauf {@link LoginScript}). Das Ergebnis steht als Hinweis in der Sitzung; die Verbindung bleibt auch bei
     * einer gescheiterten Anmeldung offen, man meldet sich dann von Hand an.
     */
    public TerminalSession open(TerminalProfile profile, LoginSource login) {
        return open(profile, login, List.of());
    }

    /**
     * Wie {@link #open(TerminalProfile, LoginSource)}; danach spielt die Sitzung {@code replay} ab (Lesezeichen mit
     * aufgezeichnetem Makro, wie in MainframeMate). Das Ergebnis steht als Hinweis in der Sitzung.
     */
    public TerminalSession open(TerminalProfile profile, LoginSource login, List<TerminalMacro.Step> replay) {
        TerminalSession session = new TerminalSession("terminal-" + counter.incrementAndGet(), profile);
        session.origin(login, replay);
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
                return;
            }
            boolean loggedIn = true;
            if (login != null && session.state() == SessionState.CONNECTED) {
                loggedIn = logIn(session, connection, login);
            }
            if (loggedIn && !session.replay().isEmpty() && session.state() == SessionState.CONNECTED) {
                play(session, connection, login != null);
            }
        });
        return session;
    }

    /**
     * Öffnet eine beendete Sitzung neu: dasselbe Ziel, dieselbe Anmeldung (frisch aus KeePass gelesen), dasselbe Makro
     * (Knopf „Verbinden“ in MainframeMate). Die alte Sitzung bleibt, wie sie ist; die Oberfläche nimmt sie heraus.
     */
    public TerminalSession reopen(TerminalSession ended) {
        return open(ended.profile(), ended.loginSource(), ended.replay());
    }

    private void play(TerminalSession session, TerminalConnection connection, boolean afterLogin) {
        TerminalKeyboard keyboard = connection.view(TerminalKeyboard.class).orElse(null);
        if (keyboard == null) {
            session.note("Makro nicht abspielbar: Terminal ohne Tastatursteuerung");
            return;
        }
        String before = afterLogin ? session.note().map(note -> note + " · ").orElse("") : "";
        session.note(before + "Spiele Makro ab …");
        try {
            if (!keyboard.awaitUnlocked(LoginScript.UNLOCK_TIMEOUT)) {
                session.note(before + "Makro nicht abgespielt: Tastatur nicht frei");
                return;
            }
            Thread.sleep(loginScript.delay());
            session.note(before + new MacroPlayer(loginScript::delay).play(keyboard, session.replay())
                    .map(reason -> "Makro abgebrochen: " + reason)
                    .orElse("Makro abgespielt (" + session.replay().size() + " Schritte)"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.note(before + "Makro unterbrochen");
        } catch (RuntimeException e) {
            session.note(before + "Makro abgebrochen: " + describe(e));
        }
    }

    /** @return ob die Anmeldung durchlief (nur dann wird ein Makro abgespielt, wie in MainframeMate) */
    private boolean logIn(TerminalSession session, TerminalConnection connection, LoginSource source) {
        TerminalKeyboard keyboard = connection.view(TerminalKeyboard.class).orElse(null);
        if (keyboard == null) {
            session.note("Automatische Anmeldung nicht möglich: Terminal ohne Tastatursteuerung");
            return false;
        }
        TerminalLogin login;
        try {
            login = source.login();
        } catch (Exception e) {
            session.note("Automatische Anmeldung: Zugangsdaten nicht lesbar – " + describe(e));
            return false;
        }
        session.note("Melde automatisch an …");
        String user = login.user();
        try {
            java.util.Optional<String> failed = loginScript.run(keyboard, login);
            session.note(failed.map(reason -> "Automatische Anmeldung abgebrochen: " + reason)
                    .orElse("Automatisch angemeldet als " + user));
            return failed.isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.note("Automatische Anmeldung unterbrochen");
        } catch (RuntimeException e) {
            session.note("Automatische Anmeldung abgebrochen: " + describe(e));
        }
        return false;
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
