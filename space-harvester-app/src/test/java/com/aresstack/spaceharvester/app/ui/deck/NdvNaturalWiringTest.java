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
import com.aresstack.spaceharvester.mainframe.dependency.CallNode;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NDV in den Editoren (wie MainframeMate {@code openNaturalFromJcl} und {@code ndv://}-Ziele): Aufrufhierarchie und
 * Aufrufer aus dem Abhängigkeitsgraphen der Sitzung, Öffnen mit Zeile, Natural aus der JCL. Erfundene Daten.
 */
class NdvNaturalWiringTest {

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
    private NdvWorkspace workspace;

    @BeforeEach
    void reset() {
        new NdvWorkspaceTest().resetNavigator();
        System.setProperty(NdvAnalysisSettings.CACHE_PROPERTY, cache.toString());
        NdvAnalysisSettings.forgetAnalyses();
        AppState.shared().put(NdvAnalysisSettings.SEARCH_ORDER_KEY, "");
        AppState.shared().put(NdvAnalysisSettings.STEPLIB_KEY, "");
        NdvAnalysisSettings.reload();
    }

    @AfterEach
    void cleanUp() {
        reset();
        System.clearProperty(NdvAnalysisSettings.CACHE_PROPERTY);
    }

    private static void flush() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private NdvBrowser connect() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            workspace = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
            NdvConnectForm form = workspace.form();
            form.show(List.of(HOST));
            form.server.setSelectedItem(HOST);
            form.library.setText("LAGER");
            form.submit();
        });
        flush();
        NdvSession session = sessions.all().getFirst();
        return workspace.browser(session);
    }

    @Test
    void calleesAndCallersComeFromTheGraphOfTheOpenSession() throws Exception {
        NdvBrowser browser = connect();
        NdvNaturalRelations relations = new NdvNaturalRelations(workspace::analyses);
        assertThat(workspace.analyses()).containsExactly(browser.analysis());

        CallNode callees = relations.callees("lager", "LAGER002", 3).orElseThrow();
        assertThat(callees.target()).isEqualTo("ndv://LAGER/LAGER002");
        assertThat(callees.children()).hasSize(1);
        CallNode called = callees.children().getFirst();
        assertThat(called.target()).isEqualTo("ndv://LAGER/LAGERN01");
        assertThat(called.label()).isEqualTo("LAGERN01  [CALLNAT]  Zeile 2");
        assertThat(called.kind()).isEqualTo("CALLNAT");
        assertThat(called.line()).isEqualTo(2);
        assertThat(called.sourceFile()).as("die Zeile steht im Aufrufer").isEqualTo("ndv://LAGER/LAGER002");

        CallNode callers = relations.callers("LAGER", "LAGERN01", 3).orElseThrow();
        CallNode caller = callers.children().getFirst();
        assertThat(caller.target()).isEqualTo("ndv://LAGER/LAGER002");
        assertThat(caller.sourceFile()).isEqualTo("ndv://LAGER/LAGER002");

        assertThat(relations.callees("UNBEKANNT", "LAGER002", 3)).as("Graph nicht gebaut").isEmpty();
        assertThat(relations.callers("", "LAGER002", 3)).isEmpty();
    }

    @Test
    void anNdvTargetOpensInTheSelectedSessionAtItsLine() throws Exception {
        NdvBrowser browser = connect();
        boolean[] opened = new boolean[1];
        SwingUtilities.invokeAndWait(() -> opened[0] = workspace.openNatural("LAGER", "LAGER001", 5));
        flush();
        assertThat(opened[0]).isTrue();
        assertThat(browser.shown().displayName()).isEqualTo("LAGER001");
        assertThat(browser.editor().caretLine()).isEqualTo(5);
    }

    @Test
    void naturalFromJclOpensViaTheStepLibOfTheSelectedSession() throws Exception {
        NdvBrowser browser = connect();
        browser.askLibrary = (stepLib, suggestion) -> "kasse";
        boolean[] opened = new boolean[1];
        SwingUtilities.invokeAndWait(() -> opened[0] = workspace.openFromJcl("KASSE-M", "kasse001"));
        flush();
        assertThat(opened[0]).isTrue();
        assertThat(browser.shown().path()).isEqualTo("KASSE/KASSE001.NSP");
    }

    @Test
    void withoutASessionTheStatusBarSaysToConnectFirst() throws Exception {
        boolean[] opened = new boolean[2];
        SwingUtilities.invokeAndWait(() -> {
            workspace = new NdvWorkspace(sessions, accounts, editors, Runnable::run);
            opened[0] = workspace.openNatural("LAGER", "LAGER001", 0);
            opened[1] = workspace.openFromJcl("KASSE-M", "KASSE001");
        });
        assertThat(opened).containsExactly(false, false);
        assertThat(workspace.statusLine()).isEqualTo("„KASSE001“ aus der JCL: zuerst mit NDV verbinden");
        assertThat(workspace.analyses()).isEmpty();
    }
}
