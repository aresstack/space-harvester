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
}
