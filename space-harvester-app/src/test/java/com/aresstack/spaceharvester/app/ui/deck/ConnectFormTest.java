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

    /**
     * Auswahllisten und Kontrollkästchen des Formulars sind die der App, nicht die von Swing (Angelo, 23.11.2026):
     * Comic-Auswahlliste mit ihrem Renderer, Kästchen mit dem Comic-Haken.
     */
    @Test
    void dropdownsAndCheckBoxesAreTheControlsOfTheApp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConnectForm form = new ConnectForm(accounts, (profile, source) -> { });
            form.show(List.of(HOST_A, HOST_B));
            for (javax.swing.JComboBox<?> combo : List.of(form.server, form.model)) {
                assertThat(combo.getUI())
                        .isInstanceOf(com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI.class);
                assertThat(combo.getRenderer().getClass().getName()).doesNotContain("DefaultListCellRenderer");
            }
            for (javax.swing.JCheckBox box : List.of(form.autoLogin, form.tls)) {
                assertThat(box.getIcon())
                        .isInstanceOf(com.aresstack.enterpriseai.ui.comic.paint.ResearchCheckIcon.class);
            }
            assertThat(form.model.getItemCount())
                    .isEqualTo(com.aresstack.spaceharvester.deck.terminal.TerminalModel.values().length);
            // Der Eintrag zeigt Namen und Benutzer, „nichts gewählt“ die Aufforderung.
            assertThat(ConnectForm.accountText(HOST_A)).isEqualTo(HOST_A + " · TESTUSR");
            assertThat(ConnectForm.accountText(null)).isEqualTo("KeePass-Eintrag wählen …");
            javax.swing.JLabel cell = (javax.swing.JLabel) form.server.getRenderer().getListCellRendererComponent(
                    new javax.swing.JList<>(), HOST_A, 0, false, false);
            assertThat(cell.getText()).isEqualTo(HOST_A + " · TESTUSR");
        });
    }

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

            // Eine abweichende Adresse für B gilt nicht für A: Zugangsdaten gehen nur an die Adresse des Eintrags.
            form.host.setText("10.9.9.9");
            form.server.setSelectedItem(HOST_A);
            assertThat(form.host.getText()).isEmpty();
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
