package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NDV-Analyse im Bereich wie in MainframeMate: Vorabladen nach dem Auflisten, Beziehungen des geöffneten Objekts,
 * Suche im Quelltext, Sperrliste mit „Gesperrte ausblenden“, Cache leeren und Öffnen aus der JCL. Erfundene Daten.
 */
class NdvAnalysisUiTest {

    private static final ServerAccount HOST = new ServerAccount("Testhost NDV", "10.0.0.7", "PILOT");

    @TempDir
    Path cache;
    private final FakeNdvServer server = FakeNdvServer.sample();
    private final NdvSessions sessions = new NdvSessions(server, Runnable::run);
    private final EditorSessions editors = new EditorSessions(Runnable::run);
    private final ServerAccounts accounts = new ServerAccounts() {
        @Override
        public List<ServerAccount> accounts() {
            return List.of(HOST);
        }

        @Override
        public ServerCredentials credentials(String entry) {
            return new ServerCredentials(HOST, "geheim".toCharArray());
        }
    };

    @BeforeEach
    void reset() {
        new NdvWorkspaceTest().resetNavigator();
        System.setProperty(NdvAnalysisSettings.CACHE_PROPERTY, cache.toString());
        NdvAnalysisSettings.forgetAnalyses();
        AppState.shared().put(NdvAnalysisSettings.RULES_ALL_KEY, false);
        AppState.shared().put(NdvAnalysisSettings.RULES_WHITE_KEY, "");
        AppState.shared().put(NdvAnalysisSettings.RULES_BLACK_KEY, "");
        AppState.shared().put(NdvAnalysisSettings.SEARCH_ORDER_KEY, "");
        AppState.shared().put(NdvAnalysisSettings.STEPLIB_KEY, "");
        NdvAnalysisSettings.reload();
        new NdvNavigatorState().hideBlacklisted(false);
        new NdvNavigatorState().indexVisible(false);
    }

    @AfterEach
    void cleanUp() {
        reset();
        System.clearProperty(NdvAnalysisSettings.CACHE_PROPERTY);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    private NdvBrowser connect() throws Exception {
        NdvWorkspace[] workspace = new NdvWorkspace[1];
        SwingUtilities.invokeAndWait(() -> {
            workspace[0] = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
            NdvConnectForm form = workspace[0].form();
            form.show(List.of(HOST));
            form.server.setSelectedItem(HOST);
            form.library.setText("LAGER");
            form.submit();
        });
        flush();
        NdvSession session = sessions.all().getFirst();
        return workspace[0].browser(session);
    }

    @Test
    void listingPrefetchesAndTheOpenObjectShowsCallsCallersAndData() throws Exception {
        NdvBrowser browser = connect();
        assertThat(browser.library()).isEqualTo("LAGER");
        int cached = browser.analysis().cache().size("LAGER");
        assertThat(cached).as("nach dem Auflisten vorab geladen").isEqualTo(7);
        assertThat(browser.analysis().graph("LAGER")).isPresent();
        SwingUtilities.invokeAndWait(() -> browser.indexToggle().doClick());
        assertThat(browser.indexPanel().isVisible()).isTrue();
        assertThat(browser.indexPanel().statusText()).isEqualTo("Zwischengespeichert");
        assertThat(browser.indexPanel().countText()).startsWith("7 / 7");
        assertThat(server.writes()).isEmpty();

        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER002");
            browser.openSelected();
        });
        flush();
        assertThat(browser.relationsPanel().rows()).containsExactly("LAGERN01  [CALLNAT]  Zeile 2");
        // Ein Doppelklick öffnet das Ziel; dort zeigt „Aufrufer“, wer es aufruft.
        SwingUtilities.invokeAndWait(() -> browser.openByName(browser.relationsPanel().entryAt(0).target(), 0));
        flush();
        assertThat(browser.shown().displayName()).isEqualTo("LAGERN01");
        SwingUtilities.invokeAndWait(() -> browser.relationsPanel().select(NdvRelationsPanel.CALLERS));
        assertThat(browser.relationsPanel().rows()).containsExactly("LAGER002  [CALLNAT]  Zeile 2");
        SwingUtilities.invokeAndWait(() -> browser.relationsPanel().select(NdvRelationsPanel.DATA));
        assertThat(browser.relationsPanel().noteText()).isEqualTo("Keine Daten / Data Areas.");

        // Suche im zwischengespeicherten Quelltext; ein Treffer öffnet die Zeile.
        SwingUtilities.invokeAndWait(() -> browser.indexPanel().search.setText("display"));
        assertThat(browser.indexPanel().hitCount()).isEqualTo(1);
        SwingUtilities.invokeAndWait(() -> browser.openByName("LAGER001", 5));
        flush();
        flush();
        assertThat(browser.shown().displayName()).isEqualTo("LAGER001");
        assertThat(browser.editor().caretLine()).isEqualTo(5);
    }

    @Test
    void blockedObjectsAreNotCachedAndCanBeHidden() throws Exception {
        NdvBrowser browser = connect();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGERM01");
            JMenuItem block = findItem(browser.contextMenu(), "Sperren");
            block.doClick();
        });
        flush();
        assertThat(NdvAnalysisSettings.rules().isBlacklisted(NdvAccessRules.path("LAGER", "LAGERM01"))).isTrue();
        assertThat(AppState.shared().get(NdvAnalysisSettings.RULES_BLACK_KEY)).isEqualTo("ndv://LAGER/LAGERM01");
        assertThat(browser.visibleObjects()).contains("LAGERM01");
        assertThat(browser.indexPanel().filterText()).isEqualTo("gesperrt");
        SwingUtilities.invokeAndWait(() -> browser.hideBlockedToggle().doClick());
        assertThat(browser.visibleObjects()).hasSize(6).doesNotContain("LAGERM01");

        // Cache leeren fragt; nach „Ja“ ist alles weg, das gesperrte Objekt kommt beim Neuladen nicht wieder.
        browser.confirm = (title, question) -> false;
        SwingUtilities.invokeAndWait(browser::clearCache);
        assertThat(browser.analysis().cache().size("LAGER")).isEqualTo(7);
        browser.confirm = (title, question) -> question.contains("wirklich");
        SwingUtilities.invokeAndWait(browser::clearCache);
        flush();
        assertThat(browser.analysis().cache().size("LAGER")).isZero();
        assertThat(browser.analysis().graph("LAGER")).isEmpty();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectNone();
            browser.cacheSelectedOrAll();
        });
        flush();
        assertThat(browser.analysis().cache().names("LAGER")).hasSize(6).doesNotContain("LAGERM01");
    }

    @Test
    void jclProgramsOpenViaStepLibMappingAndTheAnswerIsRemembered() throws Exception {
        NdvBrowser browser = connect();
        browser.askLibrary = (stepLib, suggestion) -> {
            assertThat(suggestion).isEqualTo("KASSE-T");
            return "kasse";
        };
        SwingUtilities.invokeAndWait(() -> browser.openFromJcl("KASSE-M", "kasse001"));
        flush();
        flush();
        assertThat(browser.shown().path()).isEqualTo("KASSE/KASSE001.NSP");
        assertThat(NdvAnalysisSettings.stepLibs()).isEqualTo(Map.of("KASSE-M", "KASSE"));
        browser.askLibrary = (stepLib, suggestion) -> {
            throw new AssertionError("Zuordnung ist gemerkt");
        };
        SwingUtilities.invokeAndWait(() -> browser.openFromJcl("kasse-m", "KASSEN01"));
        flush();
        flush();
        assertThat(browser.shown().displayName()).isEqualTo("KASSEN01");
    }

    private static JMenuItem findItem(javax.swing.JPopupMenu menu, String text) {
        for (java.awt.Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getText().equals(text)) {
                return item;
            }
        }
        throw new AssertionError(text);
    }
}
