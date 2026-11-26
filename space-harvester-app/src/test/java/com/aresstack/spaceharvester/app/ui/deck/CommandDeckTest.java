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
            assertThat(names).containsExactly("Home", "Terminal", "FTP", "NDV", "BetaView", "Dateien", "E-Akte");
            assertThat(deck.area()).as("Start auf Home, damit nichts verbindet").isEqualTo(DeckArea.HOME);
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
            // Der Zustand wechselt im Netz-Thread, die Statuszeile folgt danach über die Ereignis-Warteschlange.
            waitFor(() -> deck.terminal().statusText().contains("Verbunden"), until);
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

    /** Der Stand eines nicht gezeigten Bereichs nimmt in der Statuszeile keinen Platz (Codex zu #1200). */
    @Test
    void theStatusIsOnlyAsWideAsThatOfTheShownArea() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            ((com.aresstack.spaceharvester.app.ui.controls.StatusLabel) deck.files().statusInfo())
                    .show(com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(
                            "ein sehr langer erfundener Stand der Dateien, der viel Platz braucht"));
            deck.show(DeckArea.FILES);
            int files = deck.statusPanel().getPreferredSize().width;
            deck.show(DeckArea.TERMINAL);
            assertThat(deck.statusPanel().getPreferredSize().width)
                    .isEqualTo(deck.terminal().statusInfo().getPreferredSize().width).isLessThan(files);
        });
    }

    /** Die Leiste mit der Lupe wie im Space Harvester; sie sucht im gezeigten Bereich (Angelo, 23.11.2026). */
    @Test
    void theFootBarWithTheMagnifierSearchesTheShownArea() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            javax.swing.JComponent stage = (javax.swing.JComponent) deck.perspective().workspace();
            assertThat(SwingUtilities.isDescendingFrom(deck.findBar(), stage)).isTrue();
            assertThat(stage.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,
                            java.awt.event.InputEvent.CTRL_DOWN_MASK))).isEqualTo("find");
            for (DeckArea area : DeckArea.values()) {
                deck.show(area);
                assertThat(deck.findBar().isShowing() || deck.findBar().getParent() == stage).as(area.name()).isTrue();
            }
        });
    }

    /**
     * Jeder Bereich hat seine eigene Suchleiste mit eigenem Text: Was im Terminal gesucht wird, steht nicht unter FTP,
     * NDV, BetaView, Dateien oder E-Akte (Angelo 24.11.2026: gemeinsame Klasse, nie gemeinsame Instanz).
     */
    @Test
    void everyAreaHasItsOwnSearchBarAndText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            javax.swing.JComponent stage = (javax.swing.JComponent) deck.perspective().workspace();
            java.util.Set<com.aresstack.spaceharvester.app.ui.FindBar> bars = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (DeckArea area : DeckArea.values()) {
                bars.add(deck.findBar(area));
            }
            assertThat(bars).hasSize(DeckArea.values().length);
            deck.show(DeckArea.TERMINAL);
            deck.findBar().search("JOB4711");
            for (DeckArea area : DeckArea.values()) {
                deck.show(area);
                assertThat(deck.findBar()).isSameAs(deck.findBar(area));
                assertThat(deck.findBar().query()).as(area.name())
                        .isEqualTo(area == DeckArea.TERMINAL ? "JOB4711" : "");
                for (DeckArea other : DeckArea.values()) {
                    assertThat(SwingUtilities.isDescendingFrom(deck.findBar(other), stage)).as(area + "/" + other)
                            .isEqualTo(other == area);
                }
            }
        });
    }

    @Test
    void homeIsChosenOnEveryArrivalAndItsTilesOpenTheAreaWithoutConnecting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            DeckHome home = deck.home();
            assertThat(home.statusOf(DeckArea.TERMINAL)).startsWith("nicht verbunden");
            assertThat(home.statusOf(DeckArea.FTP)).startsWith("nicht verbunden");
            assertThat(home.statusOf(DeckArea.FILES)).contains("ohne Anmeldung");

            // Ein Klick auf eine Kachel wechselt in den Bereich, wie das Icon oben.
            java.awt.Component ftpTile = home.tileOf(DeckArea.FTP);
            for (java.awt.event.MouseListener listener : ftpTile.getMouseListeners()) {
                listener.mouseClicked(new java.awt.event.MouseEvent(ftpTile, java.awt.event.MouseEvent.MOUSE_CLICKED,
                        0, 0, 1, 1, 1, false));
            }
            assertThat(deck.area()).isEqualTo(DeckArea.FTP);

            // Schon beim Verlassen zurück auf Home: Beim Wiederkommen wird nichts anderes sichtbar.
            deck.show(DeckArea.TERMINAL);
            deck.perspective().onHidden().run();
            assertThat(deck.area()).isEqualTo(DeckArea.HOME);
            deck.show(DeckArea.TERMINAL);
            deck.perspective().onShown().run();
            assertThat(deck.area()).isEqualTo(DeckArea.HOME);
            assertThat(deck.sessions().all()).isEmpty();
            assertThat(CommandDeck.chatHints(DeckArea.HOME).hint()).contains("verbunden wird erst");

            // Schmal (Chat breit, Fenster klein): eine Spalte, alle Kacheln erreichbar.
            int tile = home.tileOf(DeckArea.FTP).getPreferredSize().width;
            assertThat(DeckHome.columnsFor(320, tile)).isEqualTo(1);
            assertThat(DeckHome.columnsFor(3 * tile + 100, tile)).isEqualTo(3);
            home.setSize(320, 600);
            home.doLayout();
            javax.swing.JViewport viewport = ((javax.swing.JScrollPane) home.getComponent(0)).getViewport();
            viewport.setSize(320, 600);
            for (java.awt.event.ComponentListener listener : viewport.getComponentListeners()) {
                listener.componentResized(new java.awt.event.ComponentEvent(viewport,
                        java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            }
            assertThat(home.columns()).isEqualTo(1);
        });
    }
}
