package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Öffnen, Zustände und Trennen der Terminal-Sitzungen mit einem Schein-Adapter (#1142). */
class TerminalSessionsTest {

    /** Verbindung, die der Test von außen steuert. */
    static final class FakeConnection implements TerminalConnection {
        final TerminalConnection.Listener listener;
        IOException failOnConnect;
        int connects;
        int disconnects;

        FakeConnection(TerminalConnection.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void connect() throws IOException {
            connects++;
            if (failOnConnect != null) {
                throw failOnConnect;
            }
            listener.connected();
        }

        @Override
        public void disconnect() {
            disconnects++;
        }

        @Override
        public <T> Optional<T> view(Class<T> type) {
            return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
        }
    }

    private final List<FakeConnection> created = new ArrayList<>();
    private IOException nextFailure;

    private TerminalSessions sessions(boolean failCreate) {
        return new TerminalSessions((profile, listener) -> {
            if (failCreate) {
                throw new IllegalStateException("kein Terminaltyp");
            }
            FakeConnection connection = new FakeConnection(listener);
            connection.failOnConnect = nextFailure;
            created.add(connection);
            return connection;
        }, Runnable::run);
    }

    @Test
    void openingConnectsAndReportsEveryChange() {
        TerminalSessions sessions = sessions(false);
        List<String> opened = new ArrayList<>();
        sessions.onOpened(session -> opened.add(session.id() + " " + session.state()));
        List<SessionState> changes = new ArrayList<>();
        sessions.onOpened(session -> session.observe(s -> changes.add(s.state())));

        TerminalSession session = sessions.open(TerminalProfile.of("host.example", 23));

        assertThat(opened).containsExactly("terminal-1 CONNECTING");
        assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
        assertThat(changes).containsExactly(SessionState.CONNECTED);
        assertThat(session.view(FakeConnection.class)).containsSame(created.get(0));

        session.disconnect();
        assertThat(session.state()).isEqualTo(SessionState.DISCONNECTED);
        assertThat(created.get(0).disconnects).isEqualTo(1);
        // Beendet bleibt beendet, auch wenn der Adapter noch etwas meldet.
        created.get(0).listener.connected();
        assertThat(session.state()).isEqualTo(SessionState.DISCONNECTED);
        assertThat(changes).containsExactly(SessionState.CONNECTED, SessionState.DISCONNECTED);
    }

    @Test
    void aFailedConnectKeepsTheReasonAndClosesTheConnection() {
        nextFailure = new IOException("Connection refused");
        TerminalSessions sessions = sessions(false);

        TerminalSession session = sessions.open(TerminalProfile.of("host.example", 23));

        assertThat(session.state()).isEqualTo(SessionState.FAILED);
        assertThat(session.failure()).contains("IOException: Connection refused");
        assertThat(created.get(0).disconnects).isEqualTo(1);
        assertThat(sessions.all()).containsExactly(session);
    }

    @Test
    void theHostClosingTheSessionEndsItWithoutAReason() {
        TerminalSessions sessions = sessions(false);
        TerminalSession session = sessions.open(TerminalProfile.of("host.example", 23));

        created.get(0).listener.closed(null);

        assertThat(session.state()).isEqualTo(SessionState.DISCONNECTED);
        assertThat(session.failure()).isEmpty();
    }

    @Test
    void anAdapterThatCannotCreateTheConnectionFailsTheSessionAtOnce() {
        TerminalSessions sessions = sessions(true);
        TerminalSession session = sessions.open(TerminalProfile.of("host.example", 23));
        assertThat(session.state()).isEqualTo(SessionState.FAILED);
        assertThat(session.failure()).contains("IllegalStateException: kein Terminaltyp");
        assertThat(session.view(Object.class)).isEmpty();
    }

    @Test
    void closeForgetsTheSession() {
        TerminalSessions sessions = sessions(false);
        TerminalSession first = sessions.open(TerminalProfile.of("a.example", 23));
        TerminalSession second = sessions.open(TerminalProfile.of("b.example", 23));
        sessions.close(first);
        assertThat(sessions.all()).containsExactly(second);
        assertThat(first.state()).isEqualTo(SessionState.DISCONNECTED);
        assertThat(second.id()).isEqualTo("terminal-2");
    }

    @Test
    void reopenUsesTheSameTargetLoginAndMacro() {
        TerminalSessions sessions = sessions(false);
        TerminalSessions.LoginSource login = () -> {
            throw new IllegalStateException("nicht gekoppelt");
        };
        List<TerminalMacro.Step> steps = List.of(TerminalMacro.Step.aid(AidKey.PF3));
        TerminalSession first = sessions.open(TerminalProfile.of("host.example", 23), login, steps);
        first.disconnect();

        TerminalSession second = sessions.reopen(first);

        assertThat(second).isNotSameAs(first);
        assertThat(second.profile()).isEqualTo(first.profile());
        assertThat(second.loginSource()).isSameAs(login);
        assertThat(second.replay()).isEqualTo(steps);
        assertThat(created).hasSize(2);
    }

    /** Zeitgeber, den der Test von Hand auslöst. */
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<java.time.Duration> delays = new ArrayList<>();

    private TerminalSessions reconnecting(java.util.concurrent.atomic.AtomicBoolean enabled) {
        TerminalSessions sessions = sessions(false);
        sessions.autoReconnect(TerminalSessions.Reconnect.standard(enabled::get), (delay, task) -> {
            delays.add(delay);
            scheduled.add(task);
        });
        return sessions;
    }

    @Test
    void aSessionDroppedByTheHostReconnectsWithTheSameLoginAndMacro() {
        TerminalSessions sessions = reconnecting(new java.util.concurrent.atomic.AtomicBoolean(true));
        List<String> reopened = new ArrayList<>();
        sessions.onReopened((lost, fresh) -> reopened.add(lost.id() + "->" + fresh.id()));
        TerminalSessions.LoginSource login = () -> null;
        List<TerminalMacro.Step> steps = List.of(TerminalMacro.Step.aid(AidKey.PF3));
        TerminalSession first = sessions.open(TerminalProfile.of("host.example", 992), login, steps);

        created.get(0).listener.closed(null);

        assertThat(first.state()).isEqualTo(SessionState.DISCONNECTED);
        assertThat(first.note()).contains("Verbindung verloren · neuer Versuch in 2 s (1/3)");
        assertThat(delays).containsExactly(java.time.Duration.ofSeconds(2));
        scheduled.remove(0).run();
        assertThat(reopened).containsExactly("terminal-1->terminal-2");
        TerminalSession second = sessions.all().get(0);
        assertThat(sessions.all()).hasSize(1).doesNotContain(first);
        assertThat(second.state()).isEqualTo(SessionState.CONNECTED);
        assertThat(second.loginSource()).isSameAs(login);
        assertThat(second.replay()).isEqualTo(steps);
        assertThat(second.attempt()).isEqualTo(1);
    }

    @Test
    void failedAttemptsWaitLongerAndStopAfterThree() {
        TerminalSessions sessions = reconnecting(new java.util.concurrent.atomic.AtomicBoolean(true));
        sessions.open(TerminalProfile.of("host.example", 992));
        nextFailure = new IOException("Connection refused");
        created.get(0).listener.closed("Verbindungsfehler");
        scheduled.remove(0).run();
        scheduled.remove(0).run();
        scheduled.remove(0).run();

        assertThat(delays).containsExactly(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(30));
        assertThat(scheduled).isEmpty();
        TerminalSession last = sessions.all().get(0);
        assertThat(last.state()).isEqualTo(SessionState.FAILED);
        assertThat(last.note()).contains("Kein automatischer Neuaufbau mehr: 3 Versuche gescheitert");
    }

    @Test
    void noReconnectWhenSwitchedOffClosedByHandOrNeverConnected() {
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        TerminalSessions sessions = reconnecting(enabled);
        sessions.open(TerminalProfile.of("host.example", 992));
        created.get(0).listener.closed(null);
        assertThat(scheduled).as("ausgeschaltet").isEmpty();

        enabled.set(true);
        TerminalSession byHand = sessions.open(TerminalProfile.of("host.example", 992));
        byHand.disconnect();
        assertThat(scheduled).as("von Hand getrennt").isEmpty();

        nextFailure = new IOException("Connection refused");
        sessions.open(TerminalProfile.of("host.example", 23));
        assertThat(scheduled).as("nie verbunden: der Dialog ist dran, nicht der Neuaufbau").isEmpty();

        nextFailure = null;
        // Von Hand getrennt, während ein Neuaufbau-Versuch noch verband: kein weiterer Versuch.
        TerminalSession lostOne = sessions.open(TerminalProfile.of("host.example", 992));
        created.get(created.size() - 1).listener.closed(null);
        lostOne.disconnect();
        int pending = scheduled.size();
        scheduled.remove(pending - 1).run();
        assertThat(sessions.all()).as("abgesagt").contains(lostOne);

        TerminalSession closedWhileWaiting = sessions.open(TerminalProfile.of("host.example", 992));
        created.get(created.size() - 1).listener.closed(null);
        sessions.close(closedWhileWaiting);
        int before = created.size();
        scheduled.remove(0).run();
        assertThat(created).as("geschlossen, während der Versuch wartete").hasSize(before);
    }

    @Test
    void aFailedCreationDuringReconnectStillUsesTheRemainingAttempts() {
        boolean[] failCreate = {false};
        TerminalSessions sessions = new TerminalSessions((profile, listener) -> {
            if (failCreate[0]) {
                throw new IllegalStateException("Codepage belegt");
            }
            FakeConnection connection = new FakeConnection(listener);
            created.add(connection);
            return connection;
        }, Runnable::run);
        sessions.autoReconnect(TerminalSessions.Reconnect.standard(() -> true), (delay, task) -> {
            delays.add(delay);
            scheduled.add(task);
        });
        sessions.open(TerminalProfile.of("host.example", 992));
        created.get(0).listener.closed(null);
        failCreate[0] = true;
        scheduled.remove(0).run();
        assertThat(delays).containsExactly(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(10));
        failCreate[0] = false;
        scheduled.remove(0).run();
        assertThat(sessions.all().get(0).state()).isEqualTo(SessionState.CONNECTED);
    }

    /** Anmeldung über den KeePass-Eintrag „Testhost“, automatisch (nicht von Hand). */
    private static TerminalSessions.LoginSource automaticLogin(int[] reads) {
        return new TerminalSessions.LoginSource() {
            @Override
            public TerminalLogin login() {
                reads[0]++;
                return new TerminalLogin("TESTUSR", "geheim1".toCharArray(), "");
            }

            @Override
            public String entry() {
                return "Testhost";
            }
        };
    }

    @Test
    void aBlockedEntryIsNeitherReadNorTypedAutomatically() {
        TerminalSessions sessions = sessions(false);
        var guard = new com.aresstack.spaceharvester.deck.access.LoginGuard(
                com.aresstack.spaceharvester.deck.access.LoginGuard.Store.memory());
        guard.failed("Testhost", "Passwort abgelaufen");
        sessions.guard(guard);
        int[] reads = new int[1];

        TerminalSession session = sessions.open(TerminalProfile.of("host.example", 23), automaticLogin(reads));

        assertThat(reads[0]).as("Passwort nicht einmal gelesen").isZero();
        assertThat(session.note()).hasValueSatisfying(note -> assertThat(note).contains("fehlgeschlagen"));
        assertThat(sessions.mayTry(automaticLogin(reads))).isFalse();
        guard.succeeded("Testhost");
        assertThat(sessions.mayTry(automaticLogin(reads))).isTrue();
    }

    /** Verbindung mit Tastatur, deren Bild nach der Anmeldung eine Ablehnung zeigt. */
    static final class RejectingConnection implements TerminalConnection, TerminalKeyboard {
        final TerminalConnection.Listener listener;

        RejectingConnection(TerminalConnection.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void connect() {
            listener.connected();
        }

        @Override
        public void disconnect() {
        }

        @Override
        public <T> Optional<T> view(Class<T> type) {
            return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
        }

        @Override
        public boolean awaitUnlocked(java.time.Duration timeout) {
            return true;
        }

        @Override
        public void press(AidKey key) {
        }

        @Override
        public boolean firstInputField() {
            return true;
        }

        @Override
        public boolean nextInputField() {
            return true;
        }

        @Override
        public void type(char[] text) {
        }

        @Override
        public String screenText() {
            return "LOGON\nKennwort ungueltig\n";
        }
    }

    @Test
    void aRejectedLoginBlocksTheEntryForEveryFurtherAutomaticTry() {
        TerminalSessions sessions = new TerminalSessions((profile, listener) -> new RejectingConnection(listener), Runnable::run,
                new LoginScript(java.time.Duration.ZERO));
        var guard = new com.aresstack.spaceharvester.deck.access.LoginGuard(
                com.aresstack.spaceharvester.deck.access.LoginGuard.Store.memory());
        sessions.guard(guard);
        List<LoginScript.Result> results = new ArrayList<>();
        sessions.onLogin((session, result) -> results.add(result));

        sessions.open(TerminalProfile.of("host.example", 23), automaticLogin(new int[1]));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).accepted()).isFalse();
        assertThat(guard.mayTry("Testhost")).isFalse();
        assertThat(guard.failure("Testhost")).hasValueSatisfying(
                failure -> assertThat(failure.reason()).isEqualTo("Kennwort ungueltig"));
    }

    @Test
    void theHandExceptionCoversOnlyTheOneTryFromTheForm() {
        List<RejectingConnection> connections = new ArrayList<>();
        TerminalSessions sessions = new TerminalSessions((profile, listener) -> {
            RejectingConnection connection = new RejectingConnection(listener);
            connections.add(connection);
            return connection;
        }, Runnable::run, new LoginScript(java.time.Duration.ZERO));
        sessions.autoReconnect(TerminalSessions.Reconnect.standard(() -> true), (delay, task) -> scheduled.add(task));
        var guard = new com.aresstack.spaceharvester.deck.access.LoginGuard(
                com.aresstack.spaceharvester.deck.access.LoginGuard.Store.memory());
        guard.failed("Testhost", "Passwort abgelaufen");
        sessions.guard(guard);
        int[] reads = new int[1];
        TerminalSessions.LoginSource fromForm = new TerminalSessions.LoginSource() {
            @Override
            public TerminalLogin login() {
                reads[0]++;
                return new TerminalLogin("TESTUSR", "geheim1".toCharArray(), "");
            }

            @Override
            public String entry() {
                return "Testhost";
            }

            @Override
            public boolean byHand() {
                return true;
            }
        };

        TerminalSession first = sessions.open(TerminalProfile.of("host.example", 23), fromForm);
        assertThat(reads[0]).as("der Handversuch darf trotz Sperre").isEqualTo(1);
        assertThat(guard.mayTry("Testhost")).isFalse();
        assertThat(first.loginSource().byHand()).isFalse();

        // Der Host trennt: Der Neuaufbau darf das abgelehnte Passwort nicht noch einmal schicken.
        connections.get(0).listener.closed(null);
        scheduled.forEach(Runnable::run);
        assertThat(reads[0]).isEqualTo(1);
        assertThat(first.note()).hasValueSatisfying(note -> assertThat(note).contains("Kein automatischer Neuaufbau"));

        // Auch das Wiederöffnen der Sitzung tippt nichts, solange die Sperre gilt.
        TerminalSession again = sessions.reopen(first);
        assertThat(reads[0]).isEqualTo(1);
        assertThat(again.note()).hasValueSatisfying(note -> assertThat(note).contains("fehlgeschlagen"));
    }
}
