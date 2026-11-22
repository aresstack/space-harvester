package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.openterm.FakeTn3270Host;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Das Command Deck (#1142): fünf Bereiche in fester Reihenfolge, Terminal-Sitzung nur auf Wunsch. */
class CommandDeckTest {

    private CommandDeck deck;

    @AfterEach
    void shutdown() {
        if (deck != null) {
            deck.shutdown();
        }
    }

    @Test
    void fiveAreasInOrderAndNoSessionWithoutAsking() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            List<String> names = java.util.Arrays.stream(deck.navigation().getComponents())
                    .map(c -> ((AbstractButton) c).getAccessibleContext().getAccessibleName()).toList();
            assertThat(names).containsExactly("Terminal", "FTP", "NDV", "BetaView", "Dateien");
            assertThat(deck.area()).isEqualTo(DeckArea.TERMINAL);
            assertThat(deck.sessions().all()).as("Öffnen baut keine Verbindung auf").isEmpty();
            assertThat(deck.perspective().drawer()).isNull();
            deck.show(DeckArea.BETAVIEW);
            assertThat(deck.area()).isEqualTo(DeckArea.BETAVIEW);
            // Eine falsche Eingabe öffnet nichts und sagt, was fehlt.
            ConnectForm form = deck.terminal().form();
            form.host.setText("host.example");
            form.port.setText("abc");
            form.submit();
            assertThat(form.problemText()).contains("Port muss eine Zahl sein");
            assertThat(deck.sessions().all()).isEmpty();
        });
    }

    @Test
    void aSessionOpenedFromTheFormShowsTheHostScreen() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "TESTHOST BEREIT", false),
                new FakeTn3270Host.Line(4, 2, "", true)), "Cp273")) {
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck();
                ConnectForm form = deck.terminal().form();
                form.host.setText("127.0.0.1");
                form.port.setText(String.valueOf(host.port()));
                form.submit();
            });
            TerminalSession session = deck.sessions().all().get(0);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (session.state() != SessionState.CONNECTED && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(deck.terminal().selected()).isSameAs(session);
                Component screen = session.view(JComponent.class).orElseThrow();
                assertThat(SwingUtilities.isDescendingFrom(screen, deck.terminal())).isTrue();
                assertThat(deck.terminal().statusText()).contains("127.0.0.1").contains("Verbunden");
                // Ein anderer Bereich trennt nichts.
                deck.show(DeckArea.FTP);
            });
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
        }
    }
}
