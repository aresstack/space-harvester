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
                new FakeTn3270Host.Line(4, 2, "", true),
                new FakeTn3270Host.Line(23, 1, "F3=Ende  F8=Vor  F15=Mehr", false)), "Cp273")) {
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck();
                ConnectForm form = deck.terminal().form();
                form.host.setText("127.0.0.1");
                form.port.setText(String.valueOf(host.port()));
                form.tls.setSelected(false); // der Schein-Host spricht kein TLS
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
            });
            // Die PF-Leiste liest die Belegung aus dem Bild; F13–F24 erscheinen, weil F15 belegt ist.
            waitFor(() -> deck.terminal().functionKeys().legend().containsKey(3), until);
            SwingUtilities.invokeAndWait(() -> {
                var keys = deck.terminal().functionKeys();
                assertThat(keys.isVisible()).isTrue();
                assertThat(keys.legend()).containsEntry(3, "Ende").containsEntry(8, "Vor").containsEntry(15, "Mehr");
                assertThat(keys.key(3).getText()).isEqualTo("F3-Ende");
                assertThat(keys.key(5).getText()).isEqualTo("F5");
                assertThat(keys.key(24)).isNotNull();
                keys.key(3).doClick();
            });
            waitFor(() -> !host.aids().isEmpty(), until);
            assertThat(host.aids()).containsExactly(0xF3);
            SwingUtilities.invokeAndWait(() -> deck.show(DeckArea.FTP)); // ein anderer Bereich trennt nichts
            assertThat(session.state()).isEqualTo(SessionState.CONNECTED);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long until) throws Exception {
        while (System.nanoTime() < until) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
