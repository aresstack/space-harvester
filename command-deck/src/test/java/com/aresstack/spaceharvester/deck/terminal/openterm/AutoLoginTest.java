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
            // Wie ein echter Host: Die Anmeldemaske steht, bevor jemand tippt. OpenTerm gibt die Tastatur schon vor dem
            // ersten Bild frei; ohne diese Bedingung läge es am Zufall der Fadenplanung, ob CLEAR noch vor dem Ende der
            // Telnet-Aushandlung (ohne IAC EOR) hinausgeht und der Host es mit der nächsten Eingabe zu einer verschmilzt.
            TerminalSessions sessions = new TerminalSessions(untilScreenShows("ANMELDUNG"),
                    runnable -> Thread.ofVirtual().start(runnable), new LoginScript(Duration.ofMillis(300)));
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()),
                    () -> new TerminalLogin("TESTUSR", "geheim1".toCharArray(), "MENU"));
            session.observe(changed -> {
                if (changed.note().filter(note -> note.startsWith("Automatisch")).isPresent()) {
                    done.countDown();
                }
            });
            if (session.note().filter(note -> note.startsWith("Automatisch")).isPresent()) {
                done.countDown();
            }

            assertThat(done.await(30, TimeUnit.SECONDS)).as("Anmeldung abgeschlossen").isTrue();
            // Die letzte Eingabe kann den Host einen Augenblick nach der Meldung erreichen: auf sie warten, nicht schlafen.
            boolean arrived = host.awaitInputs(3, Duration.ofSeconds(10));
            assertThat(arrived).as("Eingaben am Host: " + host.aids()).isTrue();
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
            assertThat(session.note()).hasValue("Automatisch angemeldet als TESTUSR");
            String sent = new String(host.received(), Charset.forName("Cp273"));
            assertThat(sent).contains("TESTUSR").contains("geheim1").contains("MENU");
            // CLEAR, ENTER nach der Anmeldung, ENTER nach dem Startkommando.
            assertThat(host.aids()).containsExactly(0x6D, 0x7D, 0x7D);
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

    /**
     * OpenTerm, dessen Verbindungsaufbau erst zurückkehrt, wenn das Bild des Hosts {@code text} zeigt (geweckt vom
     * Bildbeobachter der Anzeige, kein Abfragen). Danach beginnt die Anmeldung, wie bei einem Menschen, der die Maske
     * sieht. Am Ablauf der Anmeldung selbst ändert das nichts.
     */
    private static com.aresstack.spaceharvester.deck.terminal.TerminalConnector untilScreenShows(String text) {
        OpenTermConnector openTerm = new OpenTermConnector();
        return (profile, listener) -> {
            com.aresstack.spaceharvester.deck.terminal.TerminalConnection connection = openTerm.create(profile, listener);
            return new com.aresstack.spaceharvester.deck.terminal.TerminalConnection() {
                @Override
                public void connect() throws java.io.IOException {
                    com.aresstack.spaceharvester.deck.terminal.TerminalView view =
                            connection.view(com.aresstack.spaceharvester.deck.terminal.TerminalView.class).orElseThrow();
                    java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
                    Runnable watch = () -> {
                        if (view.text().contains(text)) {
                            shown.countDown();
                        }
                    };
                    view.addScreenListener(watch);
                    try {
                        connection.connect();
                        watch.run();
                        if (!shown.await(20, TimeUnit.SECONDS)) {
                            throw new java.io.IOException("Bild des Hosts kam nicht: " + text);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("unterbrochen");
                    } finally {
                        view.removeScreenListener(watch);
                    }
                }

                @Override
                public void disconnect() {
                    connection.disconnect();
                }

                @Override
                public <T> java.util.Optional<T> view(Class<T> type) {
                    return connection.view(type);
                }
            };
        };
    }
}
