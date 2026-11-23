package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import org.junit.jupiter.api.Test;

/** Der OpenTerm-Adapter ohne echten Host: Codepage, Anzeige, gescheiterte Verbindung. */
class OpenTermConnectorTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void germanCodePageMapsUmlauts() {
        int[] table = CodePages.ebcdicToUnicode(Charset.forName("Cp273"));
        assertThat((char) table[0xC0]).isEqualTo('ä');
        assertThat((char) table[0xC1]).isEqualTo('A');
        int[] back = CodePages.unicodeToEbcdic(Charset.forName("Cp273"));
        assertThat(back['ä']).isEqualTo(0xC0);
    }

    @Test
    void aRefusedConnectionFailsTheSessionWithAReason() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                runnable -> Thread.ofVirtual().start(runnable));
        CountDownLatch ended = new CountDownLatch(1);
        sessions.onOpened(session -> session.observe(s -> {
            if (s.state().ended()) {
                ended.countDown();
            }
        }));

        TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", port));

        assertThat(session.view(JComponent.class)).isPresent();
        assertThat(session.view(TerminalView.class)).isPresent();
        assertThat(ended.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.FAILED);
        assertThat(session.failure()).isPresent();
    }

    @Test
    void screenTextIsSplitIntoLinesAndPastedTextIsFlat() {
        assertThat(OpenTermScreen.lines(new char[] {'a', 'b', 'c', 0}, 2)).isEqualTo("ab\nc ");
        assertThat(OpenTermScreen.flatten("a\r\nb\tc\n")).isEqualTo("a b c ");
    }

    @Test
    void aSessionToATn3270HostShowsItsScreenAndCanBeDisconnected() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(java.util.List.of(
                new FakeTn3270Host.Line(1, 2, "SPACE HARVESTER TESTHOST", false),
                new FakeTn3270Host.Line(3, 2, "Grüße an Bord", false),
                new FakeTn3270Host.Line(5, 2, "", true)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            CountDownLatch connected = new CountDownLatch(1);
            sessions.onOpened(session -> session.observe(s -> {
                if (s.state() == SessionState.CONNECTED) {
                    connected.countDown();
                }
            }));

            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));

            assertThat(connected.await(20, TimeUnit.SECONDS)).isTrue();
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!view.text().contains("Grüße an Bord") && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(view.text()).contains("SPACE HARVESTER TESTHOST").contains("Grüße an Bord");
            assertThat(host.received()).as("Terminaltyp gemeldet").isNotEmpty();

            session.disconnect();
            assertThat(session.state()).isEqualTo(SessionState.DISCONNECTED);
        }
    }

    @Test
    void withProxyTheSessionGoesThroughTheTunnel() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(java.util.List.of(
                new FakeTn3270Host.Line(1, 2, "UEBER DEN PROXY", false)), "Cp273");
             com.aresstack.spaceharvester.deck.terminal.FakeConnectProxy proxy =
                     new com.aresstack.spaceharvester.deck.terminal.FakeConnectProxy(200)) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector((target, port, timeout) ->
                    com.aresstack.spaceharvester.deck.terminal.HttpConnectTunnel.open("127.0.0.1", proxy.port(),
                            null, null, target, port, timeout)), runnable -> Thread.ofVirtual().start(runnable));
            CountDownLatch connected = new CountDownLatch(1);
            sessions.onOpened(session -> session.observe(s -> {
                if (s.state() == SessionState.CONNECTED) {
                    connected.countDown();
                }
            }));
            // Erfundenes Ziel, das der Schein-Proxy auflöst: Der Host wird nur über den Tunnel erreicht.
            TerminalSession session = sessions.open(new TerminalProfile("127.0.0.1", host.port(),
                    com.aresstack.spaceharvester.deck.terminal.TerminalModel.MODEL_2, false, 0, "Cp273", true));

            assertThat(connected.await(20, TimeUnit.SECONDS)).isTrue();
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!view.text().contains("UEBER DEN PROXY") && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(view.text()).contains("UEBER DEN PROXY");
            assertThat(proxy.requests()).singleElement().asString().startsWith("CONNECT 127.0.0.1:" + host.port());
            session.disconnect();
        }
    }

    @Test
    void aRefusedTunnelFailsTheSessionWithTheProxyReason() throws Exception {
        TerminalSessions sessions = new TerminalSessions(new OpenTermConnector((target, port, timeout) -> {
            throw new java.io.IOException("Der Proxy verlangt eine Anmeldung (HTTP 407)");
        }), runnable -> Thread.ofVirtual().start(runnable));
        CountDownLatch ended = new CountDownLatch(1);
        sessions.onOpened(session -> session.observe(s -> {
            if (s.state().ended()) {
                ended.countDown();
            }
        }));
        TerminalSession session = sessions.open(new TerminalProfile("10.0.0.7", 992,
                com.aresstack.spaceharvester.deck.terminal.TerminalModel.MODEL_2, false, 0, "Cp273", true));
        assertThat(ended.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.FAILED);
        assertThat(session.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("Proxy")
                .contains("407"));
    }

    @Test
    void aSecondCodePageIsRefusedWhileASessionIsOpenAndAllowedAfterwards() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(java.util.List.of(
                new FakeTn3270Host.Line(1, 2, "BEREIT", false)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            int before = CodePages.users();
            TerminalSession german = sessions.open(new TerminalProfile("127.0.0.1", host.port(),
                    com.aresstack.spaceharvester.deck.terminal.TerminalModel.MODEL_2, false, 0, "Cp273"));
            TerminalSession us = sessions.open(new TerminalProfile("127.0.0.1", host.port(),
                    com.aresstack.spaceharvester.deck.terminal.TerminalModel.MODEL_2, false, 0, "Cp037"));
            assertThat(us.state()).isEqualTo(SessionState.FAILED);
            assertThat(us.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("Cp273"));
            assertThat((char) com.ascert.open.term.i3270.Tn3270StreamParser.ebc2asc[0xC0]).isEqualTo('ä');
            german.disconnect();
            assertThat(CodePages.users()).isEqualTo(before);
            TerminalSession again = sessions.open(new TerminalProfile("127.0.0.1", host.port(),
                    com.aresstack.spaceharvester.deck.terminal.TerminalModel.MODEL_2, false, 0, "Cp037"));
            assertThat(again.state()).isNotEqualTo(SessionState.FAILED);
            again.disconnect();
            assertThat(CodePages.users()).isEqualTo(before);
        }
    }

    @Test
    void aConnectionClosedBeforeConnectingNeverReachesTheHost() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(java.util.List.of(), "Cp273")) {
            java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            var connection = new OpenTermConnector().create(TerminalProfile.of("127.0.0.1", host.port()),
                    new com.aresstack.spaceharvester.deck.terminal.TerminalConnection.Listener() {
                        @Override
                        public void connected() {
                            events.add("connected");
                        }

                        @Override
                        public void closed(String reason) {
                            events.add("closed");
                        }
                    });
            connection.disconnect();
            connection.connect();
            Thread.sleep(500);
            assertThat(events).doesNotContain("connected");
            assertThat(host.received()).as("keine Verbindung aufgebaut").isEmpty();
        }
    }
}
