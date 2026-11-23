package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.openterm.FakeTn3270Host;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * „Neue Verbindung“ wie von Angelo gewünscht (23.11.2026): Einmal eingerichtet, verbindet sie direkt. Das Formular
 * kommt nur beim ersten Mal und wenn es scheitert; eine gescheiterte Verbindung hinterlässt keinen toten Reiter.
 */
class TerminalConnectFlowTest {

    private static final List<String> KEYS = List.of(ConnectForm.ENTRY_KEY, ConnectForm.CONFIGURED_KEY,
            ConnectForm.TLS_KEY, ConnectForm.AUTO_LOGIN_KEY, ConnectForm.HOST_KEY, ConnectForm.PORT_KEY,
            ConnectForm.COMMAND_KEY);

    private CommandDeck deck;

    @BeforeEach
    @AfterEach
    void clean() {
        if (deck != null) {
            deck.shutdown();
            deck = null;
        }
        KEYS.forEach(key -> AppState.shared().put(key, (String) null));
    }

    private static ServerAccounts accounts(int port) {
        ServerAccount account = new ServerAccount("Testhost", "tn3270://127.0.0.1:" + port, "TESTUSR");
        return new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, "geheim1".toCharArray());
            }
        };
    }

    /** Wie unter Einstellungen › Mainframe gespeichert: Eintrag „Testhost“, ohne TLS und Anmeldung. */
    private static void configured() {
        AppState state = AppState.shared();
        state.put(ConnectForm.ENTRY_KEY, "Testhost");
        state.put(ConnectForm.TLS_KEY, "false");
        state.put(ConnectForm.AUTO_LOGIN_KEY, "false");
        state.put(ConnectForm.CONFIGURED_KEY, "true");
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void onceConfiguredTheEmptyTerminalShowsNoFormAndTheCrossClosesAtOnce() throws Exception {
        configured();
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            ConnectForm form = deck.terminal().form();
            assertThat(form.getParent()).as("kein Formular bei jedem Start").isNull();
            deck.terminal().showForm();
            assertThat(form.getParent()).as("auf Wunsch doch").isNotNull();
        });
    }

    @Test
    void aFailedFirstConnectionLeavesNoTabAndShowsTheFormWithTheReason() throws Exception {
        int port = closedPort();
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            ConnectForm form = deck.terminal().form();
            form.host.setText("127.0.0.1");
            form.port.setText(String.valueOf(port));
            form.tls.setSelected(false);
            form.submit();
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        waitFor(() -> deck.sessions().all().isEmpty() && deck.terminal().form().problemText().contains("refused"),
                until);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(deck.sessions().all()).as("kein toter Reiter").isEmpty();
            assertThat(deck.terminal().selected()).as("das Formular steht").isNull();
            assertThat(deck.terminal().form().problemText()).contains("127.0.0.1:" + port, "Connection refused",
                    "Port oder TLS prüfen");
            assertThat(ConnectForm.isConfigured()).isFalse();
        });
    }

    @Test
    void onceConfiguredNewConnectionConnectsWithoutTheForm() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "WILLKOMMEN", false)), "Cp273")) {
            configured();
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(accounts(host.port()));
                deck.terminal().newConnection();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> deck.sessions().all().size() == 1
                    && deck.sessions().all().get(0).state() == SessionState.CONNECTED, until);
            TerminalSession session = deck.sessions().all().get(0);
            waitFor(() -> deck.terminal().selected() == session, until);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(session.profile().port()).isEqualTo(host.port());
                assertThat(session.profile().tls()).isFalse();
                assertThat(deck.terminal().selected()).as("gleich das Terminal, kein Formular").isSameAs(session);
                // Kopfzeile so hoch wie die übrigen; Trennen und Schließen stehen im ▾-Menü, der Stand unten links.
                assertThat(TerminalWorkspace.HEAD_HEIGHT).isEqualTo(35);
                javax.swing.JPopupMenu menu = deck.terminal().moreMenu();
                List<String> items = new java.util.ArrayList<>();
                for (java.awt.Component part : menu.getComponents()) {
                    if (part instanceof javax.swing.JMenuItem item) {
                        items.add(item.getText() + (item.isEnabled() ? "" : " (aus)"));
                    }
                }
                assertThat(items).containsExactly("Eingabe-Verlauf …", "Neu verbinden (aus)", "Trennen", "Schließen");
                assertThat(deck.terminal().statusText()).contains("Verbunden");
                // ✕ auf einer laufenden Sitzung fragt; eine beendete verschwindet sofort.
                session.disconnect();
                deck.terminal().askClose(session);
                assertThat(deck.sessions().all()).isEmpty();
                assertThat(deck.terminal().selected()).isNull();
            });
        }
    }

    @Test
    void aConfiguredConnectionThatFailsOpensTheFormAgain() throws Exception {
        int port = closedPort();
        configured();
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck(accounts(port));
            deck.terminal().newConnection();
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        waitFor(() -> deck.terminal().form().problemText().contains("refused"), until);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(deck.sessions().all()).isEmpty();
            assertThat(deck.terminal().selected()).isNull();
            assertThat(deck.terminal().form().selectedEntryForTest()).isEqualTo("Testhost");
        });
    }

    @Test
    void savingInTheSettingsMakesTheConnectionConfigured() throws Exception {
        int port = closedPort();
        SwingUtilities.invokeAndWait(() -> {
            ConnectForm settings = ConnectForm.forSettings(accounts(port));
            settings.show(List.of(new ServerAccount("Testhost", "tn3270://127.0.0.1:" + port, "TESTUSR")));
            settings.server.setSelectedIndex(1);
            settings.save();
            assertThat(settings.problemText()).contains("Gespeichert");
            assertThat(settings.bookmarksShown()).isFalse();
            assertThat(ConnectForm.isConfigured()).isTrue();
            assertThat(AppState.shared().get(ConnectForm.ENTRY_KEY)).isEqualTo("Testhost");
        });
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
