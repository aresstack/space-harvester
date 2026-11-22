package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * NDV-Reiter: Server aus KeePass, Port 2700 als Vorgabe, Bibliotheken und Objekte wie in MainframeMate, Quelltext nur
 * lesend. Server ist ein erfundener Schein-Server im Speicher.
 */
class NdvWorkspaceTest {

    private static final ServerAccount HOST = new ServerAccount("Testhost NDV", "tn3270://10.0.0.7:2023", "PILOT");
    private static final ServerAccount NO_USER = new ServerAccount("Ohne Benutzer", "10.0.0.8", "");

    private final FakeNdvServer server = FakeNdvServer.sample().pageSize(2);
    private final List<String> read = new CopyOnWriteArrayList<>();
    private final ServerAccounts accounts = new ServerAccounts() {
        @Override
        public List<ServerAccount> accounts() {
            return List.of(HOST, NO_USER);
        }

        @Override
        public ServerCredentials credentials(String entry) {
            read.add(entry);
            return new ServerCredentials(HOST, "geheim".toCharArray());
        }
    };
    /** Alles sofort auf dem aufrufenden Thread; die Ergebnisse kommen über invokeLater. */
    private final NdvSessions sessions = new NdvSessions(server, Runnable::run);

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void theFormNeedsAServerWithUserAndTakesPort2700() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NdvWorkspace workspace = new NdvWorkspace(sessions, accounts, Runnable::run);
            NdvConnectForm form = workspace.form();
            form.show(List.of(HOST, NO_USER));
            assertThat(form.serverStateText()).contains("2 Einträge");

            form.server.setSelectedItem(null);
            form.submit();
            assertThat(form.problemText()).contains("Server wählen");

            form.server.setSelectedItem(NO_USER);
            form.submit();
            assertThat(form.problemText()).contains("keinen Benutzer");

            form.server.setSelectedItem(HOST);
            form.host.setText("");
            form.port.setText("");
            assertThat(form.profile().host()).isEqualTo("10.0.0.7");
            assertThat(form.profile().port()).as("Port des Eintrags gilt nur fürs Terminal").isEqualTo(2700);
            form.port.setText("x");
            form.submit();
            assertThat(form.problemText()).contains("Port muss eine Zahl sein");

            // Ein abweichender Host gilt nur für den gewählten Server; ein Wechsel nimmt ihn zurück.
            form.host.setText("10.9.9.9");
            form.port.setText("2701");
            form.server.setSelectedItem(NO_USER);
            assertThat(form.host.getText()).isEmpty();
            assertThat(form.port.getText()).isEmpty();
            form.server.setSelectedItem(HOST);
            assertThat(form.profile().host()).isEqualTo("10.0.0.7");
            assertThat(sessions.all()).isEmpty();
            assertThat(read).as("ohne Verbinden kein Passwort gelesen").isEmpty();
        });
    }

    @Test
    void connectListsLibrariesAndObjectsAndOpensSourceWithoutWriting() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> {
            AppState.shared().put(NdvBrowser.LIBRARY_KEY, "");
            workspace[0] = new NdvWorkspace(sessions, accounts, Runnable::run);
            NdvConnectForm form = workspace[0].form();
            form.show(List.of(HOST));
            form.server.setSelectedItem(HOST);
            form.host.setText("");
            form.port.setText("");
            form.submit();
        });
        flush();
        NdvSession session = sessions.all().getFirst();
        assertThat(session.state()).isEqualTo(NdvState.CONNECTED);
        assertThat(session.label()).isEqualTo("Testhost NDV");
        assertThat(read).containsExactly("Testhost NDV");
        NdvBrowser browser = workspace[0].browser(session);
        assertThat(workspace[0].selected()).isSameAs(session);
        assertThat(browser.visibleLibraries()).containsExactly("LAGER", "KASSE", "SYSTEM");
        assertThat(browser.statusText()).contains("10.0.0.7:2700").contains("PILOT").contains("3 Bibliotheken");

        SwingUtilities.invokeAndWait(() -> browser.libraryFilter().setText("ka"));
        assertThat(browser.visibleLibraries()).containsExactly("KASSE");
        SwingUtilities.invokeAndWait(() -> {
            browser.libraryFilter().setText("");
            browser.openLibrary("LAGER");
        });
        flush();
        assertThat(browser.visibleObjects()).hasSize(7).contains("LAGER001", "ARTIKEL");
        assertThat(browser.statusText()).contains("7 Objekte in LAGER");
        assertThat(browser.objectsLoading()).isFalse();
        assertThat(AppState.shared().get(NdvBrowser.LIBRARY_KEY)).isEqualTo("LAGER");

        SwingUtilities.invokeAndWait(() -> browser.objectFilter().setText("*N0"));
        assertThat(browser.visibleObjects()).containsExactly("LAGERN01");
        SwingUtilities.invokeAndWait(() -> {
            browser.objectFilter().setText("");
            browser.typeFilter().setSelectedItem(FakeNdvServer.PROGRAM);
        });
        assertThat(browser.visibleObjects()).containsExactly("LAGER001", "LAGER002");

        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER001");
            browser.openSelected();
        });
        flush();
        assertThat(browser.sourceText()).contains("READ ARTIKEL BY NUMMER");
        assertThat(browser.shown().path()).isEqualTo("LAGER/LAGER001.NSP");
        assertThat(browser.statusText()).contains("Geöffnet: LAGER/LAGER001.NSP");
        assertThat(server.writes()).as("Auflisten und Öffnen schreiben nichts").isEmpty();
    }

    @Test
    void aLostConnectionHealsAndAFailedOneShowsItsReason() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> workspace[0] = new NdvWorkspace(sessions, accounts, Runnable::run));
        NdvSession session = sessions.open(new com.aresstack.spaceharvester.deck.ndv.NdvProfile("10.0.0.7", 2700),
                "Testhost NDV", () -> accounts.credentials("Testhost NDV"));
        flush();
        NdvBrowser browser = workspace[0].browser(session);

        server.dropConnections();
        SwingUtilities.invokeAndWait(() -> browser.openLibrary("KASSE"));
        flush();
        assertThat(browser.visibleObjects()).containsExactly("KASSE001", "KASSEN01");
        assertThat(server.connects()).isEqualTo(2);

        server.dropConnections();
        server.refuse("Verbindung abgelehnt: 10.0.0.7:2700");
        SwingUtilities.invokeAndWait(browser::refresh);
        flush();
        assertThat(session.state()).isEqualTo(NdvState.FAILED);
        assertThat(browser.statusText()).contains("Fehler").contains("abgelehnt");
    }

    @Test
    void openingTheDeckConnectsNothing() throws Exception {
        CommandDeck[] deck = new CommandDeck[1];
        SwingUtilities.invokeAndWait(() -> {
            deck[0] = new CommandDeck();
            deck[0].show(DeckArea.NDV);
            assertThat(deck[0].area()).isEqualTo(DeckArea.NDV);
            assertThat(deck[0].ndvSessions().all()).isEmpty();
            assertThat(deck[0].ndv().selected()).isNull();
            deck[0].show(DeckArea.TERMINAL);
            deck[0].shutdown();
        });
    }
}
