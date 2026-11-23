package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * NDV-Reiter: Server aus KeePass, Port 8011 als Vorgabe (wie MainframeMate), Bibliotheken und Objekte wie in MainframeMate, Quelltext im
 * gemeinsamen Editor; geschrieben wird nur mit „Speichern“. Server ist ein erfundener Schein-Server im Speicher.
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
    private final EditorSessions editors = new EditorSessions(Runnable::run);

    /** Der Navigator merkt sich Ansicht, Filter und Lesezeichen; jeder Test beginnt mit der Vorgabe. */
    @org.junit.jupiter.api.BeforeEach
    void resetNavigator() {
        NdvNavigatorState state = new NdvNavigatorState();
        state.groupByType(false);
        state.showDetails(false);
        state.linkWithEditor(false);
        state.sort("");
        state.hiddenTypes(java.util.Set.of());
        state.expandedTypes(null);
        state.bookmarks("10.0.0.7", List.of());
        for (String scope : List.of("LAGER", "KASSE", "SYSTEM", NdvNavigatorState.SCOPE_LIBRARIES)) {
            state.saveFilter(new com.aresstack.spaceharvester.deck.ndv.NdvNameFilter(), "10.0.0.7", "PILOT", scope);
        }
        AppState.shared().put(NdvBrowser.LIBRARY_KEY, "");
        AppState.shared().put(NdvConnectForm.LIBRARY_KEY, "");
        // Der NDV-Zwischenspeicher der Tests liegt nie im echten App-Ordner.
        if (System.getProperty(NdvAnalysisSettings.CACHE_PROPERTY) == null) {
            try {
                System.setProperty(NdvAnalysisSettings.CACHE_PROPERTY,
                        java.nio.file.Files.createTempDirectory("ndv-cache").toString());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        NdvAnalysisSettings.forgetAnalyses();
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void theFormNeedsAServerWithUserAndTakesPort8011() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NdvWorkspace workspace = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
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
            assertThat(form.profile().port()).as("Port des Eintrags gilt nur fürs Terminal").isEqualTo(8011);
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
            workspace[0] = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
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
        assertThat(browser.statusText()).contains("10.0.0.7:8011").contains("PILOT").contains("3 Bibliotheken");

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
            for (int typeId : com.aresstack.spaceharvester.deck.ndv.NdvTypeGroups.ORDER) {
                browser.hideType(typeId, typeId != FakeNdvServer.PROGRAM.id());
            }
        });
        assertThat(browser.visibleObjects()).containsExactly("LAGER001", "LAGER002");
        assertThat(browser.typeFilterButton().marked()).as("aktiver Artenfilter ist markiert").isTrue();
        SwingUtilities.invokeAndWait(() -> ((javax.swing.JMenuItem) browser.typeMenu().getComponent(0)).doClick());
        assertThat(browser.visibleObjects()).hasSize(7);
        assertThat(browser.typeFilterButton().marked()).isFalse();

        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER001");
            browser.openSelected();
        });
        flush();
        assertThat(browser.sourceText()).contains("READ ARTIKEL BY NUMMER");
        assertThat(browser.shown().path()).isEqualTo("LAGER/LAGER001.NSP");
        assertThat(browser.statusText()).contains("Geöffnet: LAGER/LAGER001.NSP");
        assertThat(browser.saveEnabled()).as("unverändert gibt es nichts zu speichern").isFalse();
        assertThat(server.writes()).as("Auflisten und Öffnen schreiben nichts").isEmpty();
    }

    @Test
    void editingAndSavingWritesOnlyOnSaveAndStopsOnAConflict() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> workspace[0] = new NdvWorkspace(sessions, accounts, editors,
                Runnable::run));
        NdvSession session = sessions.open(new com.aresstack.spaceharvester.deck.ndv.NdvProfile("10.0.0.7", 2700),
                "Testhost NDV", () -> accounts.credentials("Testhost NDV"));
        flush();
        NdvBrowser browser = workspace[0].browser(session);
        SwingUtilities.invokeAndWait(() -> browser.openLibrary("KASSE"));
        flush();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("KASSE001");
            browser.openSelected();
        });
        flush();
        EditorSession document = browser.document();
        assertThat(document.store()).isSameAs(browser.store());
        assertThat(editors.all()).containsExactly(document);
        assertThat(browser.sourceText()).isEqualTo("* Tagesabschluss\nEND");

        SwingUtilities.invokeAndWait(() -> browser.editor().type("* geprüft\n"));
        flush();
        assertThat(document.dirty()).isTrue();
        assertThat(browser.saveEnabled()).isTrue();
        assertThat(server.writes()).as("Tippen schreibt nichts").isEmpty();

        SwingUtilities.invokeAndWait(browser::save);
        flush();
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* geprüft\n* Tagesabschluss\nEND");
        assertThat(document.dirty()).isFalse();
        assertThat(browser.statusText()).contains("Gespeichert: KASSE/KASSE001.NSP");

        // Jemand anderes ändert den Quelltext auf dem Server: Speichern schreibt nicht darüber.
        session.write(browser.shown(), "* fremde Änderung\nEND");
        SwingUtilities.invokeAndWait(() -> browser.editor().type("* noch mal\n"));
        SwingUtilities.invokeAndWait(browser::save);
        flush();
        assertThat(document.state()).isEqualTo(EditorSession.State.CONFLICT);
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* fremde Änderung\nEND");
        assertThat(browser.statusText()).contains("Nicht gespeichert: KASSE/KASSE001.NSP");
        assertThat(browser.editor().plateSentence()).contains("seit dem Laden geändert");

        // Schließen der Sitzung nimmt ihre Dokumente aus dem gemeinsamen Editor (unverändert, ohne Rückfrage).
        SwingUtilities.invokeAndWait(document::load);
        flush();
        session.disconnect();
        flush();
        SwingUtilities.invokeAndWait(() -> workspace[0].closeSession(session));
        assertThat(editors.all()).isEmpty();
    }

    @Test
    void aLostConnectionHealsAndAFailedOneShowsItsReason() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> workspace[0] = new NdvWorkspace(sessions, accounts, editors, Runnable::run));
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
    void theNavigatorWorksLikeMainframeMate() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> {
            workspace[0] = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
            NdvConnectForm form = workspace[0].form();
            form.show(List.of(HOST));
            form.server.setSelectedItem(HOST);
            form.library.setText("lager");
            form.submit();
        });
        flush();
        flush();
        NdvSession session = sessions.all().getFirst();
        NdvBrowser browser = workspace[0].browser(session);
        assertThat(session.profile().defaultLibrary()).isEqualTo("LAGER");
        assertThat(AppState.shared().get(NdvConnectForm.LIBRARY_KEY)).isEqualTo("LAGER");
        assertThat(browser.library()).as("Standard-Bibliothek wird nach dem Verbinden geöffnet").isEqualTo("LAGER");
        assertThat(browser.visibleObjects()).hasSize(7);
        assertThat(browser.pathField().getText()).isEqualTo("10.0.0.7:8011/LAGER");
        assertThat(browser.tooltip()).isEqualTo("NDV: PILOT@10.0.0.7:8011 – LAGER");

        // Nach Art gruppiert, in der Reihenfolge von MainframeMate; leere Gruppen fehlen.
        SwingUtilities.invokeAndWait(() -> browser.viewToggle().select("Nach Art"));
        assertThat(browser.tree().groups()).containsExactly("Programs (2)", "Subprograms (1)", "Copycodes (1)",
                "Local Data Areas (1)", "Maps (1)", "DDMs (1)");
        assertThat(new NdvNavigatorState().groupByType()).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGERN01");
            browser.openSelected();
        });
        flush();
        assertThat(browser.shown().displayName()).isEqualTo("LAGERN01");
        assertThat(browser.editor().syntaxStyle()).as("Natural-Hervorhebung wie MainframeMate")
                .isEqualTo(com.aresstack.spaceharvester.app.ui.deck.editor.SyntaxStyles.NATURAL);
        assertThat(browser.sourceDetailsText()).contains("Library: LAGER").contains("Subprogram (Structured)")
                .contains("User: PILOT").contains("CP: IBM01141").contains("GP-User: PILOT")
                .contains("Zugriff: 2026-11-21 08:00");
        SwingUtilities.invokeAndWait(() -> browser.viewToggle().select("Liste"));

        // Details zeigen Modus, DBID und FNR; Sortierung wird gemerkt.
        assertThat(browser.visibleColumns()).isEqualTo(5);
        SwingUtilities.invokeAndWait(() -> browser.detailsToggle().doClick());
        assertThat(browser.visibleColumns()).isEqualTo(8);
        assertThat(browser.detailsToggle().marked()).isTrue();
        SwingUtilities.invokeAndWait(() -> browser.sortBy(0, javax.swing.SortOrder.DESCENDING));
        assertThat(browser.visibleObjects().getFirst()).isEqualTo("LAGERN01");
        assertThat(new NdvNavigatorState().sort()).isEqualTo("0:DESCENDING");

        // Namens-Ausdruck je Bibliothek, mit Präfixen; bleibt beim Wechsel der Bibliothek gemerkt.
        SwingUtilities.invokeAndWait(() -> browser.nameFilter("^lagern"));
        assertThat(browser.visibleObjects()).containsExactly("LAGERN01");
        SwingUtilities.invokeAndWait(() -> browser.openLibrary("KASSE"));
        flush();
        assertThat(browser.visibleObjects()).as("KASSE hat keinen Ausdruck").hasSize(2);
        SwingUtilities.invokeAndWait(() -> browser.namePrefix("KAS", true));
        assertThat(browser.visibleObjects()).hasSize(2);
        SwingUtilities.invokeAndWait(() -> browser.namePrefix("KAS", false));

        // Zurück und Vorwärts wie im Browser; die Pfadleiste führt zur Bibliotheksliste.
        assertThat(browser.canBack()).isTrue();
        SwingUtilities.invokeAndWait(browser::navigateBack);
        flush();
        assertThat(browser.library()).isEqualTo("LAGER");
        assertThat(browser.visibleObjects()).containsExactly("LAGERN01");
        assertThat(browser.canForward()).isTrue();
        SwingUtilities.invokeAndWait(browser::navigateForward);
        flush();
        assertThat(browser.library()).isEqualTo("KASSE");
        SwingUtilities.invokeAndWait(() -> {
            browser.pathField().setText("10.0.0.7:8011");
            browser.navigateToPath();
        });
        assertThat(browser.library()).isNull();
        assertThat(browser.visibleLibraries()).containsExactly("LAGER", "KASSE", "SYSTEM");
        SwingUtilities.invokeAndWait(() -> {
            browser.pathField().setText("system");
            browser.navigateToPath();
        });
        flush();
        assertThat(browser.visibleObjects()).containsExactly("MENU");

        // Lesezeichen: Stern merkt die Bibliothek, ein Objekt-Lesezeichen öffnet nach dem Laden.
        SwingUtilities.invokeAndWait(() -> browser.bookmarkStar().doClick());
        assertThat(new NdvNavigatorState().bookmarks("10.0.0.7"))
                .containsExactly(new com.aresstack.spaceharvester.deck.ndv.NdvBookmark("SYSTEM", ""));
        SwingUtilities.invokeAndWait(() -> browser.openBookmark(
                new com.aresstack.spaceharvester.deck.ndv.NdvBookmark("KASSE", "KASSE001.NSP")));
        flush();
        flush();
        assertThat(browser.shown().path()).isEqualTo("KASSE/KASSE001.NSP");

        // Mehrfachauswahl mit Kontextmenü.
        SwingUtilities.invokeAndWait(browser::selectAll);
        assertThat(browser.selectedObjects()).hasSize(2);
        SwingUtilities.invokeAndWait(browser::selectNone);
        assertThat(browser.selectedObjects()).isEmpty();
        // Öffnen, Alles/Nichts auswählen, Zwischenspeichern, Sperren, Freigeben, Filter entfernen (mit Trennern)
        assertThat(browser.contextMenu().getComponentCount()).isEqualTo(10);
        assertThat(server.writes()).isEmpty();
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
