package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.LoginScript;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalLogin;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Automatische Anmeldung wie in MainframeMate gegen den Schein-Host (erfundene Kennung, erfundenes Passwort). */
class AutoLoginTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void clearUserPasswordEnterAndTheStartCommandReachTheHost() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "ANMELDUNG", false),
                new FakeTn3270Host.Line(3, 2, "", true),
                new FakeTn3270Host.Line(4, 2, "", true)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable), new LoginScript(Duration.ofMillis(100)));

            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()),
                    () -> new TerminalLogin("TESTUSR", "geheim1".toCharArray(), "MENU"));

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (session.note().filter(note -> note.startsWith("Automatisch")).isEmpty()
                    && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            // Die letzte Eingabe kann den Host einen Augenblick nach der Meldung erreichen.
            while (host.inputs() < 3 && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
            assertThat(session.note()).contains("Automatisch angemeldet als TESTUSR");
            String sent = new String(host.received(), Charset.forName("Cp273"));
            assertThat(sent).contains("TESTUSR").contains("geheim1").contains("MENU");
            // CLEAR, ENTER nach der Anmeldung, ENTER nach dem Startkommando.
            assertThat(host.inputs()).isGreaterThanOrEqualTo(3);
            session.disconnect();
        }
    }

    @Test
    void unreadableCredentialsLeaveTheSessionOpenWithAHint() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(3, 2, "", true)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable), new LoginScript(Duration.ZERO));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()), () -> {
                throw new IllegalStateException("KeePass ist nicht gekoppelt");
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (session.note().isEmpty() && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
            assertThat(session.note()).hasValueSatisfying(note -> assertThat(note)
                    .contains("Zugangsdaten nicht lesbar").contains("KeePass ist nicht gekoppelt"));
            session.disconnect();
        }
    }
}
