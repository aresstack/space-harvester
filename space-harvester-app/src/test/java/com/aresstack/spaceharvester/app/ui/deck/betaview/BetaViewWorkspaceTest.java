package com.aresstack.spaceharvester.app.ui.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSession;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSessions;
import com.aresstack.spaceharvester.deck.betaview.ConnectionState;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadChoices;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.InfoLine;
import com.aresstack.spaceharvester.deck.betaview.NameAndDescription;
import com.aresstack.spaceharvester.deck.betaview.http.FakeBetaViewServer;
import com.aresstack.spaceharvester.deck.betaview.http.HttpBetaViewServer;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Reiter „BetaView“ gegen den erfundenen BetaView-Server: Anmelden, Suchen, Lesen, Herunterladen. */
class BetaViewWorkspaceTest {

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private BetaViewWorkspace workspace;

    @TempDir
    Path folder;

    @AfterEach
    void shutdown() throws Exception {
        if (workspace != null) {
            SwingUtilities.invokeAndWait(workspace::shutdown);
        }
        background.shutdownNow();
    }

    @Test
    void nothingIsRequestedBeforeLoginAndManualLoginSearchesReadsAndDownloads() throws Exception {
        try (FakeBetaViewServer server = new FakeBetaViewServer()) {
            Path target = folder.resolve("Tagesabschluss.txt");
            build(ServerAccounts.NONE, new FakePrompts(target));
            assertThat(server.requests()).as("Öffnen des Reiters fragt den Server nicht").isEmpty();

            SwingUtilities.invokeAndWait(() -> {
                BetaViewConnectForm form = workspace.form();
                form.login.select(BetaViewConnectForm.MANUAL);
                form.address.setText(server.address().toString());
                form.user.setText(FakeBetaViewServer.USER);
                form.password.setText(FakeBetaViewServer.PASSWORD);
                form.submit();
            });
            BetaViewSession session = connected();
            SessionPanel panel = onEdt(() -> workspace.panel(session));

            SwingUtilities.invokeAndWait(() -> {
                panel.search.favorite.setText(FakeBetaViewServer.FAVORITE);
                // Vorgabe aus MainframeMate ist Form APZF; die erfundenen Dokumente haben andere Formen.
                panel.search.form.apply("*");
                panel.search.search();
            });
            await(() -> panel.search.table.getRowCount() == 3, "drei Treffer");
            // Während einer neuen Suche lässt sich die alte Trefferliste nicht anklicken.
            SwingUtilities.invokeAndWait(() -> {
                panel.search.busy(true, "Suche läuft …");
                assertThat(panel.search.table.isEnabled()).isFalse();
                panel.search.busy(false, null);
                assertThat(panel.search.table.isEnabled()).isTrue();
            });

            SwingUtilities.invokeAndWait(() -> panel.search.table.setRowSelectionInterval(0, 0));
            await(() -> panel.documents.text.getText().contains("LAUF"), "Seite 1 im Text");
            assertThat(onEdt(panel::statusText)).contains("Geöffnet");
            // Der Stand der gewählten Sitzung steht in der Statuszeile der App, nicht in der unteren Leiste.
            assertThat(onEdt(() -> workspace.statusInfo().getAccessibleContext().getAccessibleName()))
                    .contains("Geöffnet");

            DocumentTab current = onEdt(() -> panel.documents.current().orElseThrow());
            SwingUtilities.invokeAndWait(() -> panel.download(current));
            await(() -> Files.exists(target), "heruntergeladene Datei");
            assertThat(Files.size(target)).isPositive();
            assertThat(onEdt(panel::statusText)).contains("Gespeichert");
        }
    }

    @Test
    void theSharedKeePassAccountsLogIn() throws Exception {
        try (FakeBetaViewServer server = new FakeBetaViewServer()) {
            AtomicReference<String> asked = new AtomicReference<>();
            ServerAccount account = new ServerAccount("BetaView Test", server.address().toString(),
                    FakeBetaViewServer.USER);
            // Dieselben Host-Zugänge wie Terminal, FTP und NDV (im Betrieb KeePassServerAccounts).
            ServerAccounts shared = new ServerAccounts() {
                @Override
                public List<ServerAccount> accounts() {
                    return List.of(account);
                }

                @Override
                public ServerCredentials credentials(String entry) {
                    asked.set(entry);
                    return new ServerCredentials(account, FakeBetaViewServer.PASSWORD.toCharArray());
                }
            };
            build(shared, new FakePrompts(folder.resolve("x")));
            SwingUtilities.invokeAndWait(() -> {
                BetaViewConnectForm form = workspace.form();
                form.login.select(BetaViewConnectForm.KEEPASS);
                form.address.setText(server.address().toString());
                form.loadEntries();
            });
            await(() -> workspace.form().entry.getItemCount() == 1, "Eintrag aus den gemeinsamen Zugängen");
            SwingUtilities.invokeAndWait(() -> {
                BetaViewConnectForm form = workspace.form();
                form.entry.setSelectedItem("BetaView Test");
                form.submit();
            });
            BetaViewSession session = connected();
            assertThat(asked.get()).isEqualTo("BetaView Test");
            assertThat(session.profile().user()).isEqualTo(FakeBetaViewServer.USER);
        }
    }

    @Test
    void fileNamesFollowTheTitle() {
        assertThat(SessionPanel.fileName("Lauf 21.11.: A/B")).isEqualTo("Lauf 21.11._ A_B");
        assertThat(SessionPanel.fileName("  ")).isEqualTo("Dokument");
        DocumentTab longTitle = new DocumentTab("D1", "F1", "L1", "Sehr langer Titel eines Tagesabschlusses im Lauf",
                "21.11.2026 06:12", "", false);
        assertThat(DocumentPane.shortLabel(longTitle)).hasSizeLessThanOrEqualTo(DocumentPane.MAX_PILL).endsWith("…");
    }

    @Test
    void theFirstLoginFormShowsTheMainframeMateAddress() throws Exception {
        String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(BetaViewConnectForm.ADDRESS_KEY);
        try {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY, (String) null);
            build(ServerAccounts.NONE, new FakePrompts(folder.resolve("x.txt")));
            assertThat(onEdt(() -> workspace.form().address.getText()))
                    .isEqualTo(com.aresstack.spaceharvester.deck.betaview.BetaViewProfile.DEFAULT_ADDRESS)
                    .isEqualTo("https://webenabler.zivit.iv.bfinv.de/betaview/");
            assertThat(onEdt(() -> workspace.form().address.placeholder()))
                    .isEqualTo(com.aresstack.spaceharvester.deck.betaview.BetaViewProfile.DEFAULT_ADDRESS);
        } finally {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY, before);
        }
    }

    private void build(ServerAccounts accounts, Prompts prompts) throws Exception {
        SwingUtilities.invokeAndWait(() -> workspace = new BetaViewWorkspace(new BetaViewSessions(
                new HttpBetaViewServer(BetaViewAccess.direct().http()), background), accounts, prompts));
    }

    private BetaViewSession connected() throws Exception {
        await(() -> workspace.selected() != null && workspace.selected().state() != ConnectionState.CONNECTING,
                "Anmeldung abgeschlossen");
        BetaViewSession session = onEdt(workspace::selected);
        assertThat(session.state()).as(session.failure().orElse("")).isEqualTo(ConnectionState.CONNECTED);
        return session;
    }

    private static <T> T onEdt(Supplier<T> supplier) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(supplier.get()));
        return value.get();
    }

    /** Wartet (nur im Test) auf einen Zustand, den Hintergrund und EDT gemeinsam herstellen. */
    private static void await(Supplier<Boolean> condition, String what) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            if (onEdt(condition)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("Nicht erreicht: " + what);
    }

    private record FakePrompts(Path target) implements Prompts {
        @Override
        public Optional<NameAndDescription> nameAndDescription(Component parent, String title,
                NameAndDescription proposal, String action) {
            return Optional.of(proposal);
        }

        @Override
        public Optional<DownloadOptions> download(Component parent, DownloadChoices choices) {
            return Optional.of(DownloadOptions.quick());
        }

        @Override
        public Optional<Path> saveAs(Component parent, String fileName) {
            return Optional.of(target);
        }

        @Override
        public void show(Component parent, String title, List<InfoLine> lines) {
        }
    }
}
