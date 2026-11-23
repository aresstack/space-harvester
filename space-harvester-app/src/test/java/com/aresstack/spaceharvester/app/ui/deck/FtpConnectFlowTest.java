package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * „Neue Verbindung“ im FTP-Bereich wie von Angelo gewünscht (23.11.2026): Einmal erfolgreich verbunden, verbindet sie
 * danach direkt; das Formular kommt nur beim ersten Mal und wenn es scheitert. Das ✕ am Reiter trennt und schließt.
 */
class FtpConnectFlowTest {

    private static final List<String> KEYS = List.of(FtpConnectForm.ENTRY_KEY, FtpConnectForm.CONFIGURED_KEY,
            FtpConnectForm.PORT_KEY, FtpConnectForm.ATTEMPTS_KEY, FtpConnectForm.TIMEOUT_KEY);

    private CommandDeck deck;

    @BeforeEach
    @AfterEach
    void clean() {
        if (deck != null) {
            deck.shutdown();
            deck = null;
        }
        KEYS.forEach(key -> AppState.shared().put(key, (String) null));
        DeckLogin.guard().succeeded("Raumhafen");
    }

    private static ServerAccounts accounts(String host) {
        ServerAccount account = new ServerAccount("Raumhafen", host, "KKR");
        return new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, "geheim".toCharArray());
            }
        };
    }

    @Test
    void afterOneGoodLoginNewConnectionConnectsWithoutTheFormAndTheCrossClosesInOneStep() throws Exception {
        try (FakeMvsFtpHost host = FakeMvsFtpHost.sample()) {
            ServerAccounts keePass = accounts(host.host());
            List<ServerAccount> entries = keePass.accounts();
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(keePass);
                deck.show(DeckArea.FTP);
                assertThat(FtpConnectForm.isConfigured()).isFalse();
                deck.ftp().newConnection();
                assertThat(deck.ftp().selected()).as("beim ersten Mal das Formular").isNull();
                assertThat(deck.ftp().statusNote().text()).isEqualTo(NdvWorkspace.NO_SESSION);
                java.awt.Component frame = SwingUtilities.getAncestorOfClass(
                        com.aresstack.spaceharvester.app.ui.controls.InputFrame.class, deck.ftp().form().server);
                assertThat(frame.getPreferredSize().width).as("so breit wie beim Terminal")
                        .isEqualTo(FtpConnectForm.FIELD_WIDTH);
                FtpConnectForm form = deck.ftp().form();
                form.show(entries);
                form.server.setSelectedIndex(1);
                form.port.setText(String.valueOf(host.port()));
                form.timeout.setText("10");
                assertThat(form.submit()).isTrue();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> FtpConnectForm.isConfigured(), until);
            FtpSession first = deck.ftpSessions().all().get(0);
            assertThat(first.state()).isEqualTo(ConnectionState.CONNECTED);

            SwingUtilities.invokeAndWait(() -> deck.ftp().newConnection());
            waitFor(() -> deck.ftpSessions().all().size() == 2
                    && deck.ftpSessions().all().get(1).state() == ConnectionState.CONNECTED, until);
            FtpSession second = deck.ftpSessions().all().get(1);
            waitFor(() -> onEdt(() -> deck.ftp().selected() == second), until);
            assertThat(second.profile().port()).as("die gemerkten Einstellungen").isEqualTo(host.port());

            SwingUtilities.invokeAndWait(() -> {
                // Die Aktionen der gewählten Sitzung stehen in der Fußleiste des Command Deck neben der Lupe.
                assertThat(deck.ftp()).isInstanceOf(DeckBars.FootTools.class);
                assertThat(deck.ftp().view(second).footTools().getParent()).isSameAs(deck.ftp().footTools());
                assertThat(deck.ftp().view(second).footTools().isVisible()).isTrue();
                assertThat(deck.ftp().view(first).footTools().isVisible()).isFalse();
                deck.ftp().closeTab(second);
                assertThat(deck.ftpSessions().all()).containsExactly(first);
                assertThat(second.state().ended()).as("✕ trennt").isTrue();
                assertThat(deck.ftp().selected()).isSameAs(first);
                deck.ftp().closeTab(first);
                assertThat(deck.ftpSessions().all()).isEmpty();
                assertThat(deck.ftp().selected()).isNull();
            });
        }
    }

    @Test
    void showingTheAreaWithoutSessionConnectsAtOnceEveryTime() throws Exception {
        try (FakeMvsFtpHost host = FakeMvsFtpHost.sample()) {
            AppState state = AppState.shared();
            state.put(FtpConnectForm.ENTRY_KEY, "Raumhafen");
            state.put(FtpConnectForm.PORT_KEY, String.valueOf(host.port()));
            state.put(FtpConnectForm.CONFIGURED_KEY, "true");
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(accounts(host.host()));
                deck.ftp().shown();
                deck.ftp().shown();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> onEdt(() -> deck.ftp().selected() != null
                    && deck.ftp().selected().state() == ConnectionState.CONNECTED), until);
            assertThat(deck.ftpSessions().all()).as("zweimal gezeigt, eine Verbindung").hasSize(1);
            FtpSession session = deck.ftpSessions().all().get(0);
            SwingUtilities.invokeAndWait(() -> {
                deck.ftp().shown();
                assertThat(deck.ftpSessions().all()).as("mit Sitzung nichts Neues").containsExactly(session);
                deck.ftp().closeTab(session);
                deck.ftp().shown();
            });
            waitFor(() -> onEdt(() -> deck.ftp().selected() != null && deck.ftp().selected() != session), until);
        }
    }

    @Test
    void aRefusedLoginLocksTheEntryAndNoAutomaticAttemptFollows() throws Exception {
        try (FakeMvsFtpHost host = new FakeMvsFtpHost("KKR", "abgelaufen")) {
            AppState state = AppState.shared();
            state.put(FtpConnectForm.ENTRY_KEY, "Raumhafen");
            state.put(FtpConnectForm.PORT_KEY, String.valueOf(host.port()));
            state.put(FtpConnectForm.CONFIGURED_KEY, "true");
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(accounts(host.host()));
                deck.ftp().shown();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> !DeckLogin.guard().mayTry("Raumhafen"), until);
            waitFor(() -> onEdt(() -> deck.ftpSessions().all().isEmpty()), until);
            long logins = host.commands().stream().filter(line -> line.startsWith("PASS")).count();
            assertThat(logins).as("abgelehnte Anmeldung nicht wiederholt").isEqualTo(1);
            SwingUtilities.invokeAndWait(() -> {
                // Bereichswechsel und „Neue Verbindung“ versuchen es nicht noch einmal, das Formular nennt den Grund.
                deck.ftp().shown();
                deck.ftp().newConnection();
                assertThat(deck.ftpSessions().all()).isEmpty();
                assertThat(deck.ftp().selected()).isNull();
                assertThat(deck.ftp().form().problemText()).startsWith("Letzte Anmeldung mit „Raumhafen“ fehlgeschlagen");
            });
            Thread.sleep(300);
            assertThat(host.commands().stream().filter(line -> line.startsWith("PASS")).count()).isEqualTo(1);
        }
    }

    @Test
    void aLockedEntryIsNotSentAgainWhenAnOpenSessionRebuildsItsConnections() throws Exception {
        try (FakeMvsFtpHost host = FakeMvsFtpHost.sample()) {
            AppState state = AppState.shared();
            state.put(FtpConnectForm.ENTRY_KEY, "Raumhafen");
            state.put(FtpConnectForm.PORT_KEY, String.valueOf(host.port()));
            state.put(FtpConnectForm.CONFIGURED_KEY, "true");
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(accounts(host.host()));
                deck.ftp().newConnection();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> !deck.ftpSessions().all().isEmpty()
                    && deck.ftpSessions().all().get(0).state() == ConnectionState.CONNECTED, until);
            FtpSession session = deck.ftpSessions().all().get(0);
            int logins = host.logins();
            // Das Passwort ist anderswo (etwa im Terminal) abgelehnt worden; dann reißt die Verbindung ab.
            DeckLogin.guard().failed("Raumhafen", "Passwort abgelaufen");
            host.dropConnections();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> session.files(files -> files.list("'KKR.JCL'")).get(10, TimeUnit.SECONDS))
                    .hasMessageContaining("Letzte Anmeldung mit „Raumhafen“ fehlgeschlagen");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> session.jobs(jes -> jes.jobs(com.aresstack.spaceharvester.deck.ftp.jes.JobFilter.mine()))
                            .get(10, TimeUnit.SECONDS))
                    .hasMessageContaining("Letzte Anmeldung mit „Raumhafen“ fehlgeschlagen");
            assertThat(host.logins()).as("kein Passwort an den Host, solange gesperrt").isEqualTo(logins);
        }
    }

    @Test
    void aFailedFirstConnectionLeavesNoTabAndShowsTheFormWithTheReason() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ServerAccounts keePass = accounts("127.0.0.1");
        List<ServerAccount> entries = keePass.accounts();
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck(keePass);
            FtpConnectForm form = deck.ftp().form();
            form.show(entries);
            form.server.setSelectedIndex(1);
            form.port.setText(String.valueOf(port));
            form.attempts.setText("1");
            form.timeout.setText("5");
            form.submit();
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        waitFor(() -> onEdt(() -> deck.ftpSessions().all().isEmpty()
                && deck.ftp().form().problemText().startsWith("Raumhafen")), until);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(deck.ftp().selected()).as("das Formular steht").isNull();
            assertThat(FtpConnectForm.isConfigured()).isFalse();
        });
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> call) throws Exception {
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = call.call();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private static void waitFor(Check condition, long until) throws Exception {
        while (!condition.ok()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Zeit abgelaufen");
            }
            Thread.sleep(30);
        }
    }
}
