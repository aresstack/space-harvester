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
}
