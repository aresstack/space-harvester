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
            AtomicReference<String> secret = new AtomicReference<>(FakeBetaViewServer.PASSWORD);
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
                    return new ServerCredentials(account, secret.get().toCharArray());
                }
            };
            clearLoginState();
            build(shared, new FakePrompts(folder.resolve("x")));
            assertThat(BetaViewConnectForm.isConfigured()).as("vor dem ersten Erfolg").isFalse();
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

            // Nach dem ersten Erfolg meldet „Neue Anmeldung“ direkt an, ohne Formular.
            await(BetaViewConnectForm::isConfigured, "Anmeldung gemerkt");
            SwingUtilities.invokeAndWait(workspace::newLogin);
            await(() -> workspace.sessionCount() == 2 && workspace.selected() != session
                    && workspace.selected() != null
                    && workspace.selected().state() == ConnectionState.CONNECTED, "zweite Anmeldung direkt");

            // Scheitert die direkte Anmeldung, bleibt keine tote Sitzung; das Formular zeigt den Grund.
            secret.set("falsch");
            SwingUtilities.invokeAndWait(workspace::newLogin);
            await(() -> workspace.selected() == null && !workspace.form().problemText().isBlank(),
                    "Formular mit Grund");
            assertThat(onEdt(workspace::sessionCount)).isEqualTo(2);
        } finally {
            clearLoginState();
        }
    }

    @Test
    void theSharedKeePassEntryOfAnotherAreaLogsInWithoutTheForm() throws Exception {
        try (FakeBetaViewServer server = new FakeBetaViewServer()) {
            ServerAccount account = new ServerAccount("Host", server.address().toString(), FakeBetaViewServer.USER);
            ServerAccounts shared = new ServerAccounts() {
                @Override
                public List<ServerAccount> accounts() {
                    return List.of(account);
                }

                @Override
                public ServerCredentials credentials(String entry) {
                    return new ServerCredentials(account, FakeBetaViewServer.PASSWORD.toCharArray());
                }
            };
            clearLoginState();
            // Nur NDV kennt den Eintrag; BetaView nimmt denselben Host-Zugang und die gemerkte Adresse.
            com.aresstack.spaceharvester.app.ui.AppState.shared().put("deck.ndv.entry", "Host");
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY,
                    server.address().toString());
            build(shared, new FakePrompts(folder.resolve("x")));
            assertThat(BetaViewConnectForm.isConfigured()).isTrue();
            SwingUtilities.invokeAndWait(workspace::newLogin);
            connected();
        } finally {
            clearLoginState();
        }
    }

    @Test
    void aLockedKeePassEntryIsNotTriedAutomaticallyAndTheFormSaysWhy() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ServerAccount account = new ServerAccount("Host", "https://betaview.example/", "U1");
        ServerAccounts counting = new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                reads.incrementAndGet();
                return new ServerCredentials(account, "falsch".toCharArray());
            }
        };
        try {
            clearLoginState();
            // Das Terminal ist mit demselben Eintrag gescheitert: BetaView darf es nicht noch einmal versuchen.
            com.aresstack.spaceharvester.app.ui.AppState.shared().put("deck.terminal.entry", "Host");
            com.aresstack.spaceharvester.app.ui.deck.DeckLogin.guard().failed("Host", "Kennwort abgelaufen");
            build(counting, new FakePrompts(folder.resolve("x")));
            SwingUtilities.invokeAndWait(workspace::newLogin);
            Thread.sleep(300);
            assertThat(reads.get()).as("kein Anmeldeversuch").isZero();
            assertThat(onEdt(workspace::sessionCount)).isZero();
            assertThat(onEdt(workspace::selected)).isNull();
            assertThat(onEdt(() -> workspace.form().problemText()))
                    .isEqualTo(com.aresstack.spaceharvester.app.ui.deck.DeckLogin.notice("Host"))
                    .contains("Kennwort abgelaufen");
        } finally {
            clearLoginState();
        }
    }

    @Test
    void aRejectedLoginLocksTheEntryAndASuccessfulOneUnlocksIt() throws Exception {
        try (FakeBetaViewServer server = new FakeBetaViewServer()) {
            ServerAccount account = new ServerAccount("Host", server.address().toString(), FakeBetaViewServer.USER);
            AtomicReference<String> password = new AtomicReference<>("falsch");
            ServerAccounts shared = new ServerAccounts() {
                @Override
                public List<ServerAccount> accounts() {
                    return List.of(account);
                }

                @Override
                public ServerCredentials credentials(String entry) {
                    return new ServerCredentials(account, password.get().toCharArray());
                }
            };
            clearLoginState();
            com.aresstack.spaceharvester.app.ui.AppState.shared().put("deck.ftp.entry", "Host");
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY,
                    server.address().toString());
            build(shared, new FakePrompts(folder.resolve("x")));
            SwingUtilities.invokeAndWait(workspace::newLogin);
            await(() -> !workspace.form().problemText().isBlank(), "Formular mit dem Grund");
            assertThat(com.aresstack.spaceharvester.app.ui.deck.DeckLogin.guard().mayTry("Host")).isFalse();
            assertThat(onEdt(workspace::sessionCount)).isZero();

            // Der Mensch meldet im Formular an (darf er immer): Erfolg löst die Sperre.
            password.set(FakeBetaViewServer.PASSWORD);
            SwingUtilities.invokeAndWait(() -> {
                BetaViewConnectForm form = workspace.form();
                form.entry.setSelectedItem("Host");
                form.submit();
            });
            connected();
            assertThat(com.aresstack.spaceharvester.app.ui.deck.DeckLogin.guard().mayTry("Host")).isTrue();
        } finally {
            clearLoginState();
        }
    }

    @Test
    void changingCredentialsShowsTheFormAndReadsKeePassAgainWithoutLoggingIn() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ServerAccount account = new ServerAccount("Host", "https://betaview.example/", "U1");
        ServerAccounts counting = new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                reads.incrementAndGet();
                return new ServerCredentials(account, "neu".toCharArray());
            }
        };
        try {
            clearLoginState();
            // Eingerichtet: „Neue Anmeldung“ würde direkt anmelden. „Zugangsdaten ändern“ zeigt trotzdem das Formular.
            com.aresstack.spaceharvester.app.ui.AppState.shared().put("deck.terminal.entry", "Host");
            build(counting, new FakePrompts(folder.resolve("x")));
            assertThat(BetaViewConnectForm.isConfigured()).isTrue();
            SwingUtilities.invokeAndWait(workspace::editCredentials);
            await(() -> workspace.form().entry.getItemCount() == 1, "Einträge neu aus KeePass");
            Thread.sleep(200);
            assertThat(reads.get()).as("kein Anmeldeversuch").isZero();
            assertThat(onEdt(workspace::sessionCount)).isZero();
            assertThat(onEdt(workspace::selected)).isNull();

            // Mit gesperrtem Eintrag nennt das Formular den Grund.
            com.aresstack.spaceharvester.app.ui.deck.DeckLogin.guard().failed("Host", "Kennwort abgelaufen");
            SwingUtilities.invokeAndWait(workspace::editCredentials);
            assertThat(onEdt(() -> workspace.form().problemText())).contains("Kennwort abgelaufen");
        } finally {
            clearLoginState();
        }
    }

    @Test
    void onlyARejectionByTheServerLocksTheEntry() {
        assertThat(BetaViewWorkspace.rejected("Anmeldung abgelehnt: Benutzer oder Kennwort falsch")).isTrue();
        assertThat(BetaViewWorkspace.rejected("Anmeldung abgelehnt: HTTP 401")).isTrue();
        assertThat(BetaViewWorkspace.rejected("Anmeldung abgelehnt: HTTP 403 bei login.action")).isTrue();
        assertThat(BetaViewWorkspace.rejected("Anmeldung abgelehnt: HTTP 502")).isFalse();
        assertThat(BetaViewWorkspace.rejected("ConnectException: Connection refused")).isFalse();
    }

    private static void clearLoginState() {
        com.aresstack.spaceharvester.app.ui.deck.DeckLogin.guard().succeeded("Host");
        java.util.Map<String, String> cleared = new java.util.HashMap<>();
        for (String key : new String[] {BetaViewConnectForm.CONFIGURED_KEY, BetaViewConnectForm.ENTRY_KEY,
                BetaViewConnectForm.LOGIN_KEY, BetaViewConnectForm.ADDRESS_KEY, "deck.terminal.entry",
                "deck.ndv.entry", "deck.ftp.entry"}) {
            cleared.put(key, null);
        }
        com.aresstack.spaceharvester.app.ui.AppState.shared().putAll(cleared);
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
    void theFirstLoginFormHasNoBuiltInAddress() throws Exception {
        String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(BetaViewConnectForm.ADDRESS_KEY);
        try {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY, (String) null);
            build(ServerAccounts.NONE, new FakePrompts(folder.resolve("x.txt")));
            // Keine internen Adressen im Programm (Angelo, 24.11.): weder vorbelegt noch als Platzhalter.
            assertThat(onEdt(() -> workspace.form().address.getText())).isEmpty();
            assertThat(onEdt(() -> workspace.form().address.placeholder()))
                    .isEqualTo(BetaViewConnectForm.ADDRESS_HINT)
                    .doesNotContain("://");
        } finally {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ADDRESS_KEY, before);
        }
    }

    @Test
    void withAnEmptyAddressFieldTheUrlOfTheKeePassEntryIsUsed() throws Exception {
        try (FakeBetaViewServer server = new FakeBetaViewServer()) {
            ServerAccount account = new ServerAccount("BetaView", server.address().toString(),
                    FakeBetaViewServer.USER);
            ServerAccounts shared = new ServerAccounts() {
                @Override
                public List<ServerAccount> accounts() {
                    return List.of(account);
                }

                @Override
                public ServerCredentials credentials(String entry) {
                    return new ServerCredentials(account, FakeBetaViewServer.PASSWORD.toCharArray());
                }
            };
            clearLoginState();
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(BetaViewConnectForm.ENTRY_KEY, "BetaView");
            build(shared, new FakePrompts(folder.resolve("x")));
            SwingUtilities.invokeAndWait(workspace::newLogin);
            connected();
            assertThat(onEdt(() -> workspace.form().address.getText())).isEmpty();
        } finally {
            clearLoginState();
        }
    }

    @Test
    void aKeePassEntryWithoutUrlAsksForTheAddress() throws Exception {
        ServerAccount account = new ServerAccount("Ohne URL", "", "U1");
        ServerAccounts shared = new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, "geheim".toCharArray());
            }
        };
        try {
            clearLoginState();
            build(shared, new FakePrompts(folder.resolve("x")));
            SwingUtilities.invokeAndWait(() -> {
                BetaViewConnectForm form = workspace.form();
                form.login.select(BetaViewConnectForm.KEEPASS);
                form.entries(List.of("Ohne URL"));
                form.entry.setSelectedItem("Ohne URL");
                form.address.setText("");
                form.submit();
            });
            await(() -> workspace.form().problemText().contains("Keine BetaView-Adresse"), "Hinweis im Formular");
            assertThat(onEdt(workspace::sessionCount)).isZero();
        } finally {
            clearLoginState();
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
