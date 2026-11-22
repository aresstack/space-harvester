package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.terminal.TerminalLogin;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Verbindungsformular: Server = KeePass-Eintrag, Adresse aus der URL, Anmeldung erst nach dem Verbinden gelesen. */
class ConnectFormTest {

    private static final ServerAccount HOST_A = new ServerAccount("Testhost A", "tn3270://10.0.0.7:2023", "TESTUSR");
    private static final ServerAccount HOST_B = new ServerAccount("Testhost B", "", "TESTUSR");

    private final List<String> read = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ServerAccounts accounts = new ServerAccounts() {
        @Override
        public List<ServerAccount> accounts() {
            return List.of(HOST_A, HOST_B);
        }

        @Override
        public ServerCredentials credentials(String entry) {
            read.add(entry);
            return new ServerCredentials(HOST_A, "geheim1".toCharArray());
        }
    };

    @Test
    void addressPortAndLoginComeFromTheEntry() throws Exception {
        AtomicReference<TerminalProfile> opened = new AtomicReference<>();
        AtomicReference<TerminalSessions.LoginSource> login = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ConnectForm form = new ConnectForm(accounts, (profile, source) -> {
                opened.set(profile);
                login.set(source);
            });
            form.show(List.of(HOST_A, HOST_B));
            assertThat(form.serverStateText()).contains("2 Einträge");
            form.server.setSelectedItem(null);
            form.host.setText(""); // gemerkte Werte anderer Tests
            form.submit();
            assertThat(form.problemText()).contains("Server wählen");

            form.server.setSelectedItem(HOST_B);
            form.host.setText("");
            form.submit();
            assertThat(form.problemText()).contains("keine Adresse");

            form.server.setSelectedItem(HOST_A);
            form.port.setText("");
            form.autoLogin.setSelected(true);
            form.command.setText("MENU");
            form.submit();
        });
        assertThat(opened.get().host()).isEqualTo("10.0.0.7");
        assertThat(opened.get().port()).isEqualTo(2023);
        assertThat(read).as("das Passwort wird erst nach dem Verbinden gelesen").isEmpty();
        try (TerminalLogin credentials = login.get().login()) {
            assertThat(credentials.user()).isEqualTo("TESTUSR");
            assertThat(credentials.copyPassword()).containsExactly("geheim1".toCharArray());
            assertThat(credentials.command()).isEqualTo("MENU");
            assertThat(credentials.toString()).doesNotContain("geheim1");
        }
        assertThat(read).containsExactly("Testhost A");
    }

    @Test
    void withoutAutoLoginNothingIsRead() throws Exception {
        AtomicReference<TerminalSessions.LoginSource> login = new AtomicReference<>(() -> null);
        SwingUtilities.invokeAndWait(() -> {
            ConnectForm form = new ConnectForm(accounts, (profile, source) -> login.set(source));
            form.show(List.of(HOST_A));
            form.server.setSelectedItem(HOST_A);
            form.host.setText("");
            form.port.setText("");
            form.autoLogin.setSelected(false);
            form.submit();
        });
        assertThat(login.get()).isNull();
        assertThat(read).isEmpty();
    }
}
