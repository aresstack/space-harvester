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
    void visitedLibraryReportsOnlyDirectSourceObjectsForAcquisition() throws Exception {
        var previous = NdvAnalysisSettings.searchLink();
        var visited = new java.util.concurrent.CopyOnWriteArrayList<String>();
        NdvAnalysisSettings.searchLink(new NdvSearchLink() {
            public java.util.Optional<Rule> rule(com.aresstack.spaceharvester.deck.ndv.NdvProfile profile) {
                return java.util.Optional.empty();
            }
            public boolean available() { return true; }
            public void createRule(java.awt.Component parent,
                    com.aresstack.spaceharvester.deck.ndv.NdvProfile profile, String account,
                    List<String> libraries, java.util.function.Consumer<String> saved) { }
            public String indexNow(String sourceId) { return ""; }
            public void observe(Runnable changed) { }
            public void visited(com.aresstack.spaceharvester.deck.ndv.NdvProfile profile,
                    String account, List<String> paths) {
                visited.addAll(paths);
            }
        });
        try {
            connect();
            flush();
            assertThat(visited).isNotEmpty();
            assertThat(visited).allMatch(path -> path.startsWith("LAGER/") && path.contains(".NS"));
            assertThat(visited).doesNotHaveDuplicates();
        } finally {
            NdvAnalysisSettings.searchLink(previous);
        }
    }

    @Test
    void besideTheSourceThereIsNothingTheRelationsAreTabsOfTheSideBarWithItsOneSearch() throws Exception {
        NdvBrowser browser = connect();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER002");
            browser.openSelected();
        });
        flush();
        SwingUtilities.invokeAndWait(() -> {
            com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor editor = browser.editor();
            com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor.SidePanels side = editor.detachSidePanels();
            // Ressourcen und Gliederung liegen nicht mehr im Editor, sondern in der Seitenleiste.
            for (javax.swing.JComponent panel : List.of(side.resources(), side.outline())) {
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(panel, editor)).isFalse();
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(panel, browser)).isTrue();
            }
            assertThat(javax.swing.SwingUtilities.isDescendingFrom(browser.relationsPanel(), browser.editor()
                    .getParent().getParent())).as("Aufrufe/Daten nicht mehr rechts neben dem Quelltext").isFalse();
            // Callees/Caller des Editors zeigte dasselbe wie „Aufrufe/Daten“: kein eigener Reiter mehr.
            browser.selectSide(EditorSideBar.CALLS);
            assertThat(browser.sideTab()).isNotEqualTo(EditorSideBar.CALLS);
            browser.selectSide(NdvBrowser.SIDE_RELATIONS);
            assertThat(browser.sideTab()).isEqualTo(NdvBrowser.SIDE_RELATIONS);
            assertThat(browser.relationsPanel().isShowing()).isFalse(); // ohne Fenster nie „showing“
            assertThat(browser.relationsPanel().rows()).containsExactly("LAGERN01  [CALLNAT]  Zeile 2");
            browser.sideFilter(NdvBrowser.SIDE_RELATIONS).setText("gibt es nicht");
            assertThat(browser.relationsPanel().rows()).isEmpty();
            browser.sideFilter(NdvBrowser.SIDE_RELATIONS).setText("n01");
            assertThat(browser.relationsPanel().rows()).containsExactly("LAGERN01  [CALLNAT]  Zeile 2");
            browser.sideFilter(NdvBrowser.SIDE_RELATIONS).setText("");
            // LAGER002 ruft niemand in LAGER auf: leer, und der Hinweis sagt, worin gesucht wurde.
            browser.relationsPanel().select(NdvRelationsPanel.CALLERS);
            assertThat(browser.relationsPanel().rows()).isEmpty();
            assertThat(browser.relationsPanel().noteText()).startsWith("Kein Aufrufer in LAGER (")
                    .endsWith(" Quelltexte durchsucht)");
            browser.relationsPanel().select(NdvRelationsPanel.CALLEES);
            browser.selectSide(NdvBrowser.SIDE_LIBRARIES);
        });
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
        // Nichts geleert; das nachträglich gesperrte LAGERM01 zählt aber nicht mehr mit.
        assertThat(browser.analysis().cache().size("LAGER")).isEqualTo(6);
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

    @Test
    void aLibraryWithAnAllowedObjectStaysReachableAndUnsavedTextIsNotCached() throws Exception {
        NdvBrowser browser = connect();
        // „Alles sperren“ und nur ein Objekt freigegeben: die Bibliothek bleibt begehbar, die übrigen nicht.
        NdvAnalysisSettings.rules().blacklistAll(true);
        NdvAnalysisSettings.rules().whitelist(List.of(NdvAccessRules.path("LAGER", "LAGER001")));
        flush();
        assertThat(browser.libraryAccessible("LAGER")).isTrue();
        assertThat(browser.libraryAccessible("KASSE")).isFalse();
        SwingUtilities.invokeAndWait(() -> browser.hideBlockedToggle().doClick());
        flush();
        assertThat(browser.visibleLibraries()).contains("LAGER").doesNotContain("KASSE");
        SwingUtilities.invokeAndWait(() -> browser.hideBlockedToggle().doClick());
        NdvAnalysisSettings.rules().blacklistAll(false);
        flush();

        // Ungespeicherter Text kommt nie in den Zwischenspeicher, auch nicht beim Zurückkehren zum Objekt.
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER002");
            browser.openSelected();
        });
        flush();
        SwingUtilities.invokeAndWait(() -> browser.document().edit("* ungespeichert\nEND"));
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER001");
            browser.openSelected();
        });
        flush();
        SwingUtilities.invokeAndWait(() -> {
            browser.selectObject("LAGER002");
            browser.openSelected();
        });
        flush();
        assertThat(browser.document().dirty()).isTrue();
        assertThat(browser.analysis().cache().source("LAGER", "LAGER002")).hasValueSatisfying(text ->
                assertThat(text).contains("CALLNAT").doesNotContain("ungespeichert"));
        assertThat(server.writes()).isEmpty();
    }

    @Test
    void indexingCreatesARuleForTheGlobalSearchAndStartsIt() throws Exception {
        java.util.List<String> created = new java.util.ArrayList<>();
        java.util.List<String> indexed = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<NdvSearchLink.Rule> rule =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<Runnable> observers = new java.util.ArrayList<>();
        NdvAnalysisSettings.searchLink(new NdvSearchLink() {
            @Override
            public java.util.Optional<Rule> rule(com.aresstack.spaceharvester.deck.ndv.NdvProfile profile) {
                assertThat(profile.host()).isEqualTo("10.0.0.7");
                return java.util.Optional.ofNullable(rule.get());
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void createRule(java.awt.Component parent, com.aresstack.spaceharvester.deck.ndv.NdvProfile profile,
                                   String account, List<String> libraries, java.util.function.Consumer<String> saved) {
                created.add(account + " " + libraries);
                rule.set(new Rule("natural", "NDV Lager", libraries));
                observers.forEach(Runnable::run);
                saved.accept("natural");
            }

            @Override
            public String indexNow(String sourceId) {
                indexed.add(sourceId);
                return "Indexieren gestartet: " + sourceId;
            }

            @Override
            public void observe(Runnable changed) {
                observers.add(changed);
            }
        });
        NdvBrowser browser = connect();
        flush();
        assertThat(browser.indexPanel().searchRuleText()).isEqualTo("Nicht in der globalen Suche");
        assertThat(browser.indexPanel().indexRule.isEnabled()).isTrue();
        assertThat(browser.indexPanel().indexNow.isEnabled()).as("ohne Regel nichts zu indexieren").isFalse();
        assertThat(findItem(browser.contextMenu(), "Indexieren (Regel anlegen)").isEnabled()).isTrue();

        SwingUtilities.invokeAndWait(() -> browser.indexPanel().indexRule.doClick());
        flush();
        assertThat(created).containsExactly("Testhost NDV [LAGER]");
        assertThat(indexed).as("nach dem Speichern gleich indexiert").containsExactly("natural");
        assertThat(browser.indexPanel().searchRuleText()).isEqualTo("In „NDV Lager“, global durchsuchbar");
        assertThat(browser.indexPanel().indexNow.isEnabled()).isTrue();

        SwingUtilities.invokeAndWait(() -> browser.indexPanel().indexNow.doClick());
        flush();
        assertThat(indexed).containsExactly("natural", "natural");

        // Eine Regel des Servers ohne diese Bibliothek: gelb, Anlegen ergänzt sie.
        rule.set(new NdvSearchLink.Rule("natural", "NDV Lager", List.of("KASSE")));
        observers.forEach(Runnable::run);
        flush();
        assertThat(browser.indexPanel().searchRuleText()).isEqualTo("„NDV Lager“ ohne LAGER");

        // Gesperrte Bibliotheken kommen nicht in die globale Suche.
        NdvAnalysisSettings.rules().blacklist(List.of(NdvAccessRules.path("LAGER", "")));
        SwingUtilities.invokeAndWait(browser::createSearchRule);
        flush();
        assertThat(created).hasSize(1);
        assertThat(server.writes()).isEmpty();
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
