package com.aresstack.spaceharvester.deck.terminal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
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
    private final List<BiConsumer<TerminalSession, TerminalSession>> reopenedObservers = new CopyOnWriteArrayList<>();
    private volatile Reconnect reconnect = Reconnect.OFF;
    /** Programmende: nichts mehr neu aufbauen. */
    private volatile boolean closing;
    private volatile Scheduler scheduler = (delay, task) -> {
    };

    /** Führt eine Aufgabe einmal nach einer Wartezeit aus (ein Zeitgeber, kein Abfragen). */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Duration delay, Runnable task);
    }

    /**
     * Regel für den automatischen Neuaufbau: Trennt der Host eine verbundene Sitzung oder bricht die Leitung ab, öffnet
     * die Sitzung sich nach {@code delays[0]} neu, mit derselben Anmeldung und demselben Makro. Scheitert auch das,
     * folgen die weiteren Wartezeiten; danach ist Schluss. War die Sitzung mindestens {@code stable} lang verbunden,
     * zählen die Versuche wieder von vorn.
     *
     * @param enabled ob der Neuaufbau gerade erlaubt ist (Einstellung, jederzeit änderbar)
     */
    public record Reconnect(BooleanSupplier enabled, List<Duration> delays, Duration stable) {

        /** Kein Neuaufbau. */
        public static final Reconnect OFF = new Reconnect(() -> false, List.of(), Duration.ZERO);

        /** Vorgabe: nach 2 s, 10 s und 30 s, neu gezählt nach einer Minute Verbindung. */
        public static Reconnect standard(BooleanSupplier enabled) {
            return new Reconnect(enabled, List.of(Duration.ofSeconds(2), Duration.ofSeconds(10),
                    Duration.ofSeconds(30)), Duration.ofMinutes(1));
        }

        public Reconnect {
            Objects.requireNonNull(enabled, "enabled");
            delays = List.copyOf(delays);
            Objects.requireNonNull(stable, "stable");
        }
    }

    /** Liefert die Zugangsdaten erst im Hintergrund, nach dem Verbinden (etwa aus KeePass). */
    @FunctionalInterface
    public interface LoginSource {
        TerminalLogin login() throws Exception;

        /** Der KeePass-Eintrag der Zugangsdaten (Schlüssel der Sperre gegen Kontosperren); {@code null} ohne. */
        default String entry() {
            return null;
        }

        /** Ob der Mensch selbst im Formular „Verbinden“ gedrückt hat: Dann darf trotz Sperre versucht werden. */
        default boolean byHand() {
            return false;
        }
    }

    /** Schutz vor der Kontosperre: je KeePass-Eintrag kein automatischer Versuch nach einem Fehlschlag. */
    private volatile com.aresstack.spaceharvester.deck.access.LoginGuard guard;
    private final List<BiConsumer<TerminalSession, LoginScript.Result>> loginObservers = new CopyOnWriteArrayList<>();

    /** Setzt den Wächter gegen Kontosperren (in der App der gemeinsame aller Bereiche). */
    public void guard(com.aresstack.spaceharvester.deck.access.LoginGuard guard) {
        this.guard = guard;
    }

    /** Meldet das Ergebnis jeder automatischen Anmeldung. */
    public void onLogin(BiConsumer<TerminalSession, LoginScript.Result> observer) {
        loginObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Ob mit dieser Anmeldung automatisch versucht werden darf (nicht von Hand, Eintrag gesperrt: nein). */
    public boolean mayTry(LoginSource login) {
        var current = guard;
        return login == null || login.byHand() || login.entry() == null || current == null
                || current.mayTry(login.entry());
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
        return open(profile, login, replay, 0);
    }

    private TerminalSession open(TerminalProfile profile, LoginSource login, List<TerminalMacro.Step> replay,
                                 int attempt) {
        TerminalSession session = new TerminalSession("terminal-" + counter.incrementAndGet(), profile);
        session.origin(login, replay);
        session.attempt(attempt);
        TerminalConnection connection;
        try {
            connection = connector.create(profile, new TerminalConnection.Listener() {
                @Override
                public void connected() {
                    session.change(SessionState.CONNECTED, null);
                }

                @Override
                public void closed(String reason) {
                    boolean wasConnected = session.state() == SessionState.CONNECTED;
                    session.change(reason == null ? SessionState.DISCONNECTED : SessionState.FAILED, reason);
                    if (wasConnected) {
                        // Vom Host getrennt oder Leitung weg: Das Terminal soll nicht tot bleiben.
                        reconnectLater(session);
                    }
                }
            });
        } catch (Exception e) {
            sessions.add(session);
            openedObservers.forEach(observer -> observer.accept(session));
            session.change(SessionState.FAILED, describe(e));
            if (attempt > 0) {
                // Auch ein gescheitertes Anlegen (etwa Codepage belegt) verbraucht nur diesen Versuch.
                reconnectLater(session);
            }
            return session;
        }
        session.attach(connection);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        background.execute(() -> {
            // Wie MainframeMate: Die Zugangsdaten liegen vor dem Verbinden bereit, damit die Anmeldung sofort nach dem
            // ersten Bild läuft und nicht erst, wenn man schon selbst tippt.
            TerminalLogin credentials = null;
            String unreadable = null;
            if (login != null && !mayTry(login)) {
                // Gesperrt (letzte Anmeldung mit diesem Eintrag fehlgeschlagen): verbinden ja, nichts tippen.
                unreadable = "letzte Anmeldung mit „" + login.entry() + "“ fehlgeschlagen, bitte Zugangsdaten prüfen";
            } else if (login != null) {
                try {
                    credentials = login.login();
                } catch (Exception e) {
                    unreadable = describe(e);
                }
            }
            try {
                connection.connect();
            } catch (Exception e) {
                if (credentials != null) {
                    credentials.close();
                }
                connection.disconnect();
                session.change(SessionState.FAILED, describe(e));
                if (session.attempt() > 0) {
                    reconnectLater(session);
                }
                return;
            }
            boolean loggedIn = true;
            if (login != null && session.state() == SessionState.CONNECTED) {
                loggedIn = logIn(session, connection, credentials, unreadable);
            } else if (credentials != null) {
                credentials.close();
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

    /**
     * Schaltet den automatischen Neuaufbau ein (Vorgabe: aus).
     *
     * @param policy    wann und wie oft
     * @param scheduler führt den Versuch nach der Wartezeit aus
     */
    public void autoReconnect(Reconnect policy, Scheduler scheduler) {
        this.reconnect = Objects.requireNonNull(policy, "policy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Meldet jeden automatischen Neuaufbau: die verlorene Sitzung und ihre Nachfolgerin. */
    public void onReopened(BiConsumer<TerminalSession, TerminalSession> observer) {
        reopenedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /** Plant den nächsten Versuch für eine verlorene Sitzung; der Grund steht als Hinweis in ihr. */
    private void reconnectLater(TerminalSession lost) {
        Reconnect policy = reconnect;
        if (closing || lost.disconnectedByHand() || !policy.enabled().getAsBoolean() || policy.delays().isEmpty()) {
            return;
        }
        if (!mayTry(lost.loginSource())) {
            lost.note("Kein automatischer Neuaufbau: letzte Anmeldung mit „" + lost.loginSource().entry()
                    + "“ fehlgeschlagen, bitte Zugangsdaten prüfen");
            return;
        }
        long since = lost.connectedSince();
        boolean stable = since > 0 && System.currentTimeMillis() - since >= policy.stable().toMillis();
        int next = stable ? 1 : lost.attempt() + 1;
        int limit = policy.delays().size();
        if (next > limit) {
            lost.note("Kein automatischer Neuaufbau mehr: " + limit + " Versuche gescheitert");
            return;
        }
        Duration delay = policy.delays().get(next - 1);
        lost.note("Verbindung verloren · neuer Versuch in " + delay.toSeconds() + " s (" + next + "/" + limit + ")");
        scheduler.schedule(delay, () -> {
            // Von Hand geschlossen oder schon neu verbunden: nichts mehr tun.
            if (closing || lost.disconnectedByHand() || !sessions.contains(lost)
                    || !policy.enabled().getAsBoolean()) {
                return;
            }
            sessions.remove(lost);
            TerminalSession fresh = open(lost.profile(), lost.loginSource(), lost.replay(), next);
            reopenedObservers.forEach(observer -> observer.accept(lost, fresh));
        });
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
    private boolean logIn(TerminalSession session, TerminalConnection connection, TerminalLogin login,
                          String unreadable) {
        if (login == null) {
            session.note("Automatische Anmeldung: Zugangsdaten nicht lesbar – " + unreadable);
            return false;
        }
        TerminalKeyboard keyboard = connection.view(TerminalKeyboard.class).orElse(null);
        if (keyboard == null) {
            login.close();
            session.note("Automatische Anmeldung nicht möglich: Terminal ohne Tastatursteuerung");
            return false;
        }
        session.note("Melde automatisch an …");
        String user = login.user();
        try {
            // Tippt der Mensch ab jetzt selbst, hört die Anmeldung auf (nie dazwischentippen).
            LoginScript.Result result = loginScript.attempt(keyboard, login, System.nanoTime());
            report(session, result);
            session.note(result.problem().map(reason -> (result.sent() && !result.accepted()
                    ? "Automatische Anmeldung fehlgeschlagen: " : "Automatische Anmeldung abgebrochen: ") + reason)
                    .orElse("Automatisch angemeldet als " + user));
            return result.problem().isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.note("Automatische Anmeldung unterbrochen");
        } catch (RuntimeException e) {
            session.note("Automatische Anmeldung abgebrochen: " + describe(e));
        }
        return false;
    }

    /** Meldet einen abgeschickten Versuch an den Wächter (nur ein Erfolg löst die Sperre) und an die Beobachter. */
    private void report(TerminalSession session, LoginScript.Result result) {
        var current = guard;
        LoginSource source = session.loginSource();
        String entry = source == null ? null : source.entry();
        if (current != null && entry != null && result.sent()) {
            if (result.accepted()) {
                current.succeeded(entry);
            } else {
                current.failed(entry, result.problem().orElse("unklar"));
            }
        }
        loginObservers.forEach(observer -> observer.accept(session, result));
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
        closing = true;
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
